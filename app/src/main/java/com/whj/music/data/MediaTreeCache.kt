package com.whj.music.data

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import com.whj.music.R
import com.whj.music.model.BrowseItem
import com.whj.music.model.BrowseItemType
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * MediaStore 媒体目录树缓存（对齐 reader 的 LinkedTreeCacheStore）：
 * - 整库扫描一次建树，切换目录时内存直出
 * - 进入目录时用 MediaStore 版本号校验，不一致则重扫并更新
 * - 「重新扫描文件」强制全量重建
 */
object MediaTreeCache {
    private const val TAG = "MediaTreeCache"
    private const val DIR_NAME = "media_tree_cache"
    private const val FILE_NAME = "tree.json"

    /** 视频 mediaId 高位置位，与 [FolderBrowser.VIDEO_ID_FLAG] 一致 */
    private const val VIDEO_ID_FLAG = 1L shl 62

    @Volatile
    private var memory: Snapshot? = null

    private val scanning = AtomicBoolean(false)

    data class FileRec(
        val mediaId: Long,
        val storeId: Long,
        val name: String,
        val subtitle: String,
        val durationMs: Long,
        val isVideo: Boolean,
        val folderPath: String,
        val filePath: String?,
    )

    data class DirRec(
        val name: String,
        val folderPath: String,
        val directFileCount: Int,
        val childFolderCount: Int,
    ) {
        val itemCount: Int get() = directFileCount + childFolderCount
    }

    data class Level(
        val dirs: List<DirRec>,
        val files: List<FileRec>,
    )

    data class Snapshot(
        val scannedAt: Long,
        /** [MediaStore.getVersion] 或 count 签名 */
        val signature: String,
        val levels: Map<String, Level>,
        val totalFiles: Int,
    ) {
        fun listingOrNull(folderPath: String): Level? {
            val key = FolderBrowser.normalizeFolder(folderPath)
            return levels[key]
        }

        fun hasLevel(folderPath: String): Boolean =
            levels.containsKey(FolderBrowser.normalizeFolder(folderPath))
    }

    data class RefreshResult(
        val items: List<BrowseItem>,
        val contentChanged: Boolean,
        val snapshot: Snapshot,
    )

    fun peek(): Snapshot? = memory

    fun peekItems(ctx: Context, folderPath: String): List<BrowseItem>? {
        val level = memory?.listingOrNull(folderPath) ?: return null
        return levelToItems(ctx, level)
    }

    fun peekRootItems(ctx: Context, rootPaths: List<String>): List<BrowseItem>? {
        val snap = memory ?: return null
        val roots = rootPaths
            .map { FolderBrowser.normalizeFolder(it) }
            .filter { it.isNotEmpty() }
            .distinct()
        if (roots.isEmpty()) return null
        // 已有完整快照即可展示（某主目录无媒体时也会给出 0 项）
        return roots.map { path -> rootFolderItem(ctx, snap, path) }
            .sortedBy { it.name.lowercase() }
    }

    fun load(ctx: Context): Snapshot? {
        memory?.let { return it }
        val f = cacheFile(ctx)
        if (!f.isFile) return null
        val snap = runCatching {
            parseSnapshot(f.readText(Charsets.UTF_8))
        }.onFailure {
            Log.w(TAG, "load cache failed: ${it.message}")
        }.getOrNull()
        if (snap != null) {
            memory = snap
        }
        return snap
    }

    fun save(ctx: Context, snapshot: Snapshot) {
        memory = snapshot
        val f = cacheFile(ctx)
        runCatching {
            f.writeText(toJson(snapshot).toString(), Charsets.UTF_8)
        }.onFailure {
            Log.e(TAG, "save cache failed", it)
        }
    }

    fun invalidate(clearDisk: Boolean = false, ctx: Context? = null) {
        memory = null
        if (clearDisk && ctx != null) {
            runCatching { cacheFile(ctx).delete() }
        }
    }

    /** 当前 MediaStore 签名（版本号优先） */
    fun currentSignature(ctx: Context): String {
        return runCatching { MediaStore.getVersion(ctx) }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: fallbackSignature(ctx)
    }

    private fun fallbackSignature(ctx: Context): String {
        val audio = countAndMaxModified(ctx, audioCollection())
        val video = countAndMaxModified(ctx, videoCollection())
        return "fb:${audio.first}:${audio.second}:${video.first}:${video.second}"
    }

    private fun countAndMaxModified(ctx: Context, collection: Uri): Pair<Int, Long> {
        var count = 0
        var maxMod = 0L
        val projection = arrayOf(MediaStore.MediaColumns.DATE_MODIFIED)
        runCatching {
            ctx.contentResolver.query(collection, projection, null, null, null)?.use { c ->
                val col = c.getColumnIndex(MediaStore.MediaColumns.DATE_MODIFIED)
                while (c.moveToNext()) {
                    count++
                    if (col >= 0) {
                        val m = c.getLong(col)
                        if (m > maxMod) maxMod = m
                    }
                }
            }
        }
        return count to maxMod
    }

    /**
     * 确保缓存可用：
     * - 内存/磁盘命中且签名一致 → 直接返回
     * - 否则全量扫描
     */
    fun ensure(
        ctx: Context,
        forceRescan: Boolean = false,
    ): Snapshot {
        if (!forceRescan) {
            val mem = memory
            if (mem != null && mem.signature == currentSignature(ctx)) {
                return mem
            }
            val disk = load(ctx)
            if (disk != null && disk.signature == currentSignature(ctx)) {
                return disk
            }
        }
        return fullScan(ctx)
    }

    /**
     * 进入目录时的校验流程（IO 线程）：
     * 1. 若无缓存或强制 → 全扫
     * 2. 签名一致 → 返回缓存 listing，contentChanged=false
     * 3. 签名变化 → 全扫并返回新 listing
     */
    fun ensureAndList(
        ctx: Context,
        folderPath: String,
        forceRescan: Boolean = false,
    ): RefreshResult {
        val path = FolderBrowser.normalizeFolder(folderPath)
        val before = memory
        val snap = ensure(ctx, forceRescan = forceRescan)
        val items = listFromSnapshot(ctx, snap, path)
        val contentChanged = forceRescan ||
            before == null ||
            before.signature != snap.signature ||
            before.listingOrNull(path) != snap.listingOrNull(path)
        return RefreshResult(items = items, contentChanged = contentChanged, snapshot = snap)
    }

    fun listConfiguredRoots(
        ctx: Context,
        rootPaths: List<String>,
        forceRescan: Boolean = false,
    ): RefreshResult {
        val before = memory
        val snap = ensure(ctx, forceRescan = forceRescan)
        val items = rootPaths
            .map { FolderBrowser.normalizeFolder(it) }
            .filter { it.isNotEmpty() }
            .distinct()
            .map { rootFolderItem(ctx, snap, it) }
            .sortedBy { it.name.lowercase() }
        val contentChanged = forceRescan ||
            before == null ||
            before.signature != snap.signature
        return RefreshResult(items = items, contentChanged = contentChanged, snapshot = snap)
    }

    fun listPlayable(
        ctx: Context,
        folderPath: String,
    ): List<BrowseItem> {
        val snap = ensure(ctx, forceRescan = false)
        val level = snap.listingOrNull(folderPath) ?: return emptyList()
        return level.files.map { it.toBrowseItem() }
    }

    fun siblingFoldersWithFiles(ctx: Context, currentFolder: String): List<String> {
        val current = FolderBrowser.normalizeFolder(currentFolder)
        if (current.isEmpty()) return emptyList()
        val parent = FolderBrowser.parentFolder(current) ?: return emptyList()
        val snap = ensure(ctx, forceRescan = false)
        val level = snap.listingOrNull(parent) ?: return emptyList()
        return level.dirs
            .filter { it.directFileCount > 0 }
            .map { it.folderPath }
    }

    fun fullScan(ctx: Context): Snapshot {
        // 避免并发重复全扫；后来者等当前扫完后读内存
        if (!scanning.compareAndSet(false, true)) {
            // 简单自旋等待（扫库通常 < 几秒）
            var waited = 0
            while (scanning.get() && waited < 30_000) {
                Thread.sleep(50)
                waited += 50
                memory?.let { if (it.signature == currentSignature(ctx)) return it }
            }
            memory?.let { return it }
        }
        return try {
            doFullScan(ctx)
        } finally {
            scanning.set(false)
        }
    }

    private fun doFullScan(ctx: Context): Snapshot {
        val t0 = System.currentTimeMillis()
        val signature = currentSignature(ctx)
        val filesByFolder = linkedMapOf<String, MutableList<FileRec>>()
        val directFileCounts = mutableMapOf<String, Int>()
        val childFolderNames = mutableMapOf<String, MutableSet<String>>()

        scanAudio(ctx, filesByFolder, directFileCounts, childFolderNames)
        scanVideo(ctx, filesByFolder, directFileCounts, childFolderNames)

        val levelKeys = LinkedHashSet<String>().apply {
            addAll(childFolderNames.keys)
            addAll(filesByFolder.keys)
            // 保证根键存在
            add("")
        }

        val levels = LinkedHashMap<String, Level>()
        for (path in levelKeys) {
            val childNames = childFolderNames[path].orEmpty()
            val dirs = childNames.map { name ->
                val childPath = path + name + "/"
                val fileCount = directFileCounts[childPath] ?: 0
                val subCount = childFolderNames[childPath]?.size ?: 0
                DirRec(
                    name = name,
                    folderPath = childPath,
                    directFileCount = fileCount,
                    childFolderCount = subCount,
                )
            }.sortedBy { it.name.lowercase() }

            val files = (filesByFolder[path] ?: emptyList())
                .sortedBy { it.name.lowercase() }
            levels[path] = Level(dirs = dirs, files = files)
        }

        // 主目录可能仅作为前缀出现、本身无任何直接子项键：补空 level，便于 hasLevel
        // （listConfiguredRoots 对「仅有更深文件」的目录用 startsWith 兜底）

        val totalFiles = filesByFolder.values.sumOf { it.size }
        val snap = Snapshot(
            scannedAt = System.currentTimeMillis(),
            signature = signature,
            levels = levels,
            totalFiles = totalFiles,
        )
        save(ctx, snap)
        Log.i(
            TAG,
            "scan done levels=${levels.size} files=$totalFiles " +
                "in ${System.currentTimeMillis() - t0}ms",
        )
        return snap
    }

    private fun scanAudio(
        ctx: Context,
        filesByFolder: MutableMap<String, MutableList<FileRec>>,
        directFileCounts: MutableMap<String, Int>,
        childFolderNames: MutableMap<String, MutableSet<String>>,
    ) {
        val collection = audioCollection()
        MediaStoreCompat.query(
            resolver = ctx.contentResolver,
            uri = collection,
            projectionBuilder = MediaStoreCompat::audioProjection,
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val displayCol = cursor.getColumnIndex(MediaStore.Audio.Media.DISPLAY_NAME)
            val titleCol = cursor.getColumnIndex(MediaStore.Audio.Media.TITLE)
            val artistCol = cursor.getColumnIndex(MediaStore.Audio.Media.ARTIST)
            val durationCol = cursor.getColumnIndex(MediaStore.Audio.Media.DURATION)
            val pathCol = cursor.getColumnIndex(MediaStore.Audio.Media.RELATIVE_PATH)
            val isMusicCol = cursor.getColumnIndex(MediaStore.Audio.Media.IS_MUSIC)
            val dataCol = cursor.getColumnIndex(MediaStore.Audio.Media.DATA)

            while (cursor.moveToNext()) {
                if (isMusicCol >= 0 && cursor.getInt(isMusicCol) == 0) continue
                val relative = MediaStoreCompat.readRelativeFolder(cursor, pathCol, dataCol)
                val displayName = if (displayCol >= 0) cursor.getString(displayCol) else null
                val title = if (titleCol >= 0) cursor.getString(titleCol) else null
                val artist = if (artistCol >= 0) cursor.getString(artistCol) else null
                val name = displayName?.takeIf { it.isNotBlank() }
                    ?: title?.takeIf { it.isNotBlank() }
                    ?: "未命名"
                val subtitle = when {
                    !artist.isNullOrBlank() && !artist.equals("<unknown>", true) -> artist
                    else -> "音频"
                }
                val storeId = cursor.getLong(idCol)
                val data = if (dataCol >= 0) cursor.getString(dataCol) else null
                val durationMs = if (durationCol >= 0) {
                    cursor.getLong(durationCol).coerceAtLeast(0L)
                } else {
                    0L
                }
                addFile(
                    filesByFolder = filesByFolder,
                    directFileCounts = directFileCounts,
                    childFolderNames = childFolderNames,
                    relative = relative,
                    rec = FileRec(
                        mediaId = storeId,
                        storeId = storeId,
                        name = name,
                        subtitle = subtitle,
                        durationMs = durationMs,
                        isVideo = false,
                        folderPath = relative,
                        filePath = resolveFilePath(data, relative, displayName),
                    ),
                )
            }
        }
    }

    private fun scanVideo(
        ctx: Context,
        filesByFolder: MutableMap<String, MutableList<FileRec>>,
        directFileCounts: MutableMap<String, Int>,
        childFolderNames: MutableMap<String, MutableSet<String>>,
    ) {
        val collection = videoCollection()
        MediaStoreCompat.query(
            resolver = ctx.contentResolver,
            uri = collection,
            projectionBuilder = MediaStoreCompat::videoProjection,
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            val displayCol = cursor.getColumnIndex(MediaStore.Video.Media.DISPLAY_NAME)
            val titleCol = cursor.getColumnIndex(MediaStore.Video.Media.TITLE)
            val durationCol = cursor.getColumnIndex(MediaStore.Video.Media.DURATION)
            val pathCol = cursor.getColumnIndex(MediaStore.Video.Media.RELATIVE_PATH)
            val dataCol = cursor.getColumnIndex(MediaStore.Video.Media.DATA)

            while (cursor.moveToNext()) {
                val relative = MediaStoreCompat.readRelativeFolder(cursor, pathCol, dataCol)
                val displayName = if (displayCol >= 0) cursor.getString(displayCol) else null
                val title = if (titleCol >= 0) cursor.getString(titleCol) else null
                val name = displayName?.takeIf { it.isNotBlank() }
                    ?: title?.takeIf { it.isNotBlank() }
                    ?: "未命名"
                val storeId = cursor.getLong(idCol)
                val data = if (dataCol >= 0) cursor.getString(dataCol) else null
                val durationMs = if (durationCol >= 0) {
                    cursor.getLong(durationCol).coerceAtLeast(0L)
                } else {
                    0L
                }
                addFile(
                    filesByFolder = filesByFolder,
                    directFileCounts = directFileCounts,
                    childFolderNames = childFolderNames,
                    relative = relative,
                    rec = FileRec(
                        mediaId = storeId or VIDEO_ID_FLAG,
                        storeId = storeId,
                        name = name,
                        subtitle = "视频 · 仅声音",
                        durationMs = durationMs,
                        isVideo = true,
                        folderPath = relative,
                        filePath = resolveFilePath(data, relative, displayName),
                    ),
                )
            }
        }
    }

    private fun addFile(
        filesByFolder: MutableMap<String, MutableList<FileRec>>,
        directFileCounts: MutableMap<String, Int>,
        childFolderNames: MutableMap<String, MutableSet<String>>,
        relative: String,
        rec: FileRec,
    ) {
        directFileCounts[relative] = (directFileCounts[relative] ?: 0) + 1
        registerChildFolders(relative, childFolderNames)
        filesByFolder.getOrPut(relative) { mutableListOf() }.add(rec)
    }

    private fun registerChildFolders(
        relative: String,
        childFolderNames: MutableMap<String, MutableSet<String>>,
    ) {
        val parts = relative.trim('/').split('/').filter { it.isNotEmpty() }
        if (parts.isEmpty()) return
        var parent = ""
        for (part in parts) {
            childFolderNames.getOrPut(parent) { mutableSetOf() }.add(part)
            parent = parent + part + "/"
        }
    }

    private fun listFromSnapshot(ctx: Context, snap: Snapshot, folderPath: String): List<BrowseItem> {
        val level = snap.listingOrNull(folderPath)
            ?: return emptyList()
        return levelToItems(ctx, level)
    }

    private fun levelToItems(ctx: Context, level: Level): List<BrowseItem> {
        val folders = level.dirs.map { d ->
            BrowseItem(
                type = BrowseItemType.FOLDER,
                name = d.name,
                folderPath = d.folderPath,
                directFileCount = d.directFileCount,
                subtitle = ctx.getString(R.string.folder_item_count, d.itemCount),
            )
        }
        val files = level.files.map { it.toBrowseItem() }
        return folders + files
    }

    private fun rootFolderItem(ctx: Context, snap: Snapshot, path: String): BrowseItem {
        val level = snap.listingOrNull(path)
        val directFiles = level?.files?.size ?: 0
        val subFolders = level?.dirs?.size ?: 0
        // 若该路径无 level 但树中有更深文件，用子路径推算「项」数（子文件夹数）
        val itemCount = if (level != null) {
            directFiles + subFolders
        } else {
            // 仅统计直接子段
            val prefix = path
            val childNames = linkedSetOf<String>()
            snap.levels.keys.forEach { key ->
                if (key.startsWith(prefix) && key != prefix) {
                    val rem = key.removePrefix(prefix).trim('/')
                    if (rem.isNotEmpty()) {
                        childNames.add(rem.substringBefore('/'))
                    }
                }
            }
            childNames.size
        }
        val name = path.trim('/').substringAfterLast('/').ifBlank { path.trim('/') }
        return BrowseItem(
            type = BrowseItemType.FOLDER,
            name = name,
            folderPath = path,
            directFileCount = directFiles,
            subtitle = ctx.getString(R.string.folder_item_count, itemCount),
        )
    }

    private fun FileRec.toBrowseItem(): BrowseItem {
        val collection = if (isVideo) videoCollection() else audioCollection()
        val uri = ContentUris.withAppendedId(collection, storeId)
        return BrowseItem(
            type = BrowseItemType.FILE,
            name = name,
            folderPath = folderPath,
            uri = uri,
            mediaId = mediaId,
            durationMs = durationMs,
            isVideo = isVideo,
            subtitle = subtitle,
            filePath = filePath,
        )
    }

    private fun resolveFilePath(dataPath: String?, relative: String, displayName: String?): String? {
        if (!dataPath.isNullOrBlank() && File(dataPath).exists()) {
            return dataPath
        }
        val name = displayName?.takeIf { it.isNotBlank() } ?: return null
        val rel = relative.trimStart('/')
        val candidates = listOf(
            "/storage/emulated/0/$rel$name",
            "/sdcard/$rel$name",
        )
        return candidates.firstOrNull { File(it).exists() }
    }

    private fun audioCollection(): Uri {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        }
    }

    private fun videoCollection(): Uri {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }
    }

    private fun cacheDir(ctx: Context): File =
        File(ctx.filesDir, DIR_NAME).also { it.mkdirs() }

    private fun cacheFile(ctx: Context): File =
        File(cacheDir(ctx), FILE_NAME)

    private fun toJson(snap: Snapshot): JSONObject {
        val levelsObj = JSONObject()
        snap.levels.forEach { (key, level) ->
            val dirsArr = JSONArray()
            level.dirs.forEach { d ->
                dirsArr.put(
                    JSONObject()
                        .put("name", d.name)
                        .put("folderPath", d.folderPath)
                        .put("directFileCount", d.directFileCount)
                        .put("childFolderCount", d.childFolderCount),
                )
            }
            val filesArr = JSONArray()
            level.files.forEach { f ->
                filesArr.put(
                    JSONObject()
                        .put("mediaId", f.mediaId)
                        .put("storeId", f.storeId)
                        .put("name", f.name)
                        .put("subtitle", f.subtitle)
                        .put("durationMs", f.durationMs)
                        .put("isVideo", f.isVideo)
                        .put("folderPath", f.folderPath)
                        .put("filePath", f.filePath ?: JSONObject.NULL),
                )
            }
            // JSONObject 不允许空字符串作 key，根目录用特殊标记
            val jsonKey = if (key.isEmpty()) ROOT_KEY else key
            levelsObj.put(
                jsonKey,
                JSONObject()
                    .put("dirs", dirsArr)
                    .put("files", filesArr),
            )
        }
        return JSONObject()
            .put("scannedAt", snap.scannedAt)
            .put("signature", snap.signature)
            .put("totalFiles", snap.totalFiles)
            .put("levels", levelsObj)
    }

    private fun parseSnapshot(raw: String): Snapshot {
        val o = JSONObject(raw)
        val scannedAt = o.optLong("scannedAt", 0L)
        val signature = o.optString("signature", "")
        val totalFiles = o.optInt("totalFiles", 0)
        val levelsObj = o.getJSONObject("levels")
        val levels = LinkedHashMap<String, Level>()
        val keys = levelsObj.keys()
        while (keys.hasNext()) {
            val jsonKey = keys.next()
            val key = if (jsonKey == ROOT_KEY) "" else jsonKey
            val lo = levelsObj.getJSONObject(jsonKey)
            val dirsArr = lo.optJSONArray("dirs") ?: JSONArray()
            val filesArr = lo.optJSONArray("files") ?: JSONArray()
            val dirs = buildList {
                for (i in 0 until dirsArr.length()) {
                    val d = dirsArr.getJSONObject(i)
                    add(
                        DirRec(
                            name = d.getString("name"),
                            folderPath = d.getString("folderPath"),
                            directFileCount = d.optInt("directFileCount", 0),
                            childFolderCount = d.optInt("childFolderCount", 0),
                        ),
                    )
                }
            }
            val files = buildList {
                for (i in 0 until filesArr.length()) {
                    val f = filesArr.getJSONObject(i)
                    val fp = f.opt("filePath")
                    add(
                        FileRec(
                            mediaId = f.getLong("mediaId"),
                            storeId = f.optLong("storeId", f.getLong("mediaId") and VIDEO_ID_FLAG.inv()),
                            name = f.getString("name"),
                            subtitle = f.optString("subtitle", ""),
                            durationMs = f.optLong("durationMs", 0L),
                            isVideo = f.optBoolean("isVideo", false),
                            folderPath = f.optString("folderPath", key),
                            filePath = if (fp == null || fp == JSONObject.NULL) null else fp.toString(),
                        ),
                    )
                }
            }
            levels[key] = Level(dirs, files)
        }
        return Snapshot(
            scannedAt = scannedAt,
            signature = signature,
            levels = levels,
            totalFiles = totalFiles,
        )
    }

    private const val ROOT_KEY = "__root__"
}
