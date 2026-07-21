package com.whj.music.data

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.whj.music.model.BrowseItem
import com.whj.music.model.BrowseItemType
import com.whj.music.model.PlayableMedia
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 基于 MediaStore 的文件夹浏览器。
 * folderPath 使用相对路径，统一以 "/" 结尾（根目录为空字符串）。
 *
 * 列表走 [MediaTreeCache]：切换目录内存直出；进入时校验签名，不一致则更新。
 */
class FolderBrowser(private val context: Context) {

    /**
     * 主线程可调用：内存缓存中的当前层列表；无缓存返回 null。
     */
    fun peekList(folderPath: String): List<BrowseItem>? =
        MediaTreeCache.peekItems(context, normalizeFolder(folderPath))

    /**
     * 主线程可调用：配置主目录的入口列表（不含「播放列表」项）。
     */
    fun peekConfiguredRoots(rootPaths: List<String>): List<BrowseItem>? =
        MediaTreeCache.peekRootItems(context, rootPaths)

    suspend fun list(
        folderPath: String,
        forceRescan: Boolean = false,
    ): List<BrowseItem> = withContext(Dispatchers.IO) {
        MediaTreeCache.ensureAndList(
            context,
            normalizeFolder(folderPath),
            forceRescan = forceRescan,
        ).items
    }

    /**
     * 进入目录：返回缓存/更新后的列表，以及内容是否相对进入前有变化。
     */
    suspend fun listWithRefresh(
        folderPath: String,
        forceRescan: Boolean = false,
    ): MediaTreeCache.RefreshResult = withContext(Dispatchers.IO) {
        MediaTreeCache.ensureAndList(
            context,
            normalizeFolder(folderPath),
            forceRescan = forceRescan,
        )
    }

    /**
     * 1 屏根目录：仅展示配置的主目录（快捷入口）。
     * 副标题为「n 项」（直接文件 + 子文件夹）；[BrowseItem.directFileCount] 仍为曲目数。
     */
    suspend fun listConfiguredRoots(
        rootPaths: List<String>,
        forceRescan: Boolean = false,
    ): List<BrowseItem> = withContext(Dispatchers.IO) {
        MediaTreeCache.listConfiguredRoots(context, rootPaths, forceRescan).items
    }

    suspend fun listConfiguredRootsWithRefresh(
        rootPaths: List<String>,
        forceRescan: Boolean = false,
    ): MediaTreeCache.RefreshResult = withContext(Dispatchers.IO) {
        MediaTreeCache.listConfiguredRoots(context, rootPaths, forceRescan)
    }

    /**
     * 扫描 MediaStore，返回所有含媒体文件的相对目录路径（可用于添加主目录）。
     * 不依赖树缓存（设置页选目录时用）。
     */
    suspend fun listAllMediaFolderPaths(): List<String> = withContext(Dispatchers.IO) {
        val paths = linkedSetOf<String>()
        collectRelativePaths(audioCollection(), paths)
        collectRelativePaths(videoCollection(), paths)
        paths.map { normalizeFolder(it) }.filter { it.isNotEmpty() }.sortedBy { it.lowercase() }
    }

    private fun collectRelativePaths(collection: Uri, out: MutableSet<String>) {
        // 兼容 API 28- 无 RELATIVE_PATH、部分 OEM 无 _data
        MediaStoreCompat.query(
            resolver = context.contentResolver,
            uri = collection,
            projectionBuilder = { includeData, includeRelative ->
                buildList {
                    if (includeRelative && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        add(MediaStore.MediaColumns.RELATIVE_PATH)
                    }
                    if (includeData) add(MediaStore.MediaColumns.DATA)
                    // 至少要有一列，避免空 projection
                    if (isEmpty()) add(MediaStore.MediaColumns._ID)
                }.toTypedArray()
            },
        )?.use { cursor ->
            val relCol = cursor.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH)
            val dataCol = cursor.getColumnIndex(MediaStore.MediaColumns.DATA)
            while (cursor.moveToNext()) {
                val n = MediaStoreCompat.readRelativeFolder(cursor, relCol, dataCol)
                if (n.isNotEmpty()) out += n
            }
        }
    }

    /** 当前文件夹内可连续播放的媒体（不含子文件夹） */
    suspend fun listPlayableInFolder(folderPath: String): List<PlayableMedia> =
        withContext(Dispatchers.IO) {
            MediaTreeCache.listPlayable(context, normalizeFolder(folderPath))
                .mapNotNull { it.toPlayable() }
        }

    /**
     * 按 mediaId 列表解析为 [BrowseItem]（保持传入顺序）。
     * video 的 mediaId 带 [VIDEO_ID_FLAG]；找不到的 id 跳过。
     * [listFolderPath] 写入每项的 folderPath（虚拟列表路径）。
     */
    suspend fun resolveMediaIds(
        mediaIds: List<Long>,
        listFolderPath: String,
    ): List<BrowseItem> = withContext(Dispatchers.IO) {
        if (mediaIds.isEmpty()) return@withContext emptyList()
        val map = mutableMapOf<Long, BrowseItem>()
        val audioIds = mediaIds.filter { it and VIDEO_ID_FLAG == 0L }
        val videoIds = mediaIds.filter { it and VIDEO_ID_FLAG != 0L }.map { it and VIDEO_ID_FLAG.inv() }
        if (audioIds.isNotEmpty()) {
            loadMediaByIds(
                collection = audioCollection(),
                storeIds = audioIds,
                isVideo = false,
                listFolderPath = listFolderPath,
                out = map,
            )
        }
        if (videoIds.isNotEmpty()) {
            loadMediaByIds(
                collection = videoCollection(),
                storeIds = videoIds,
                isVideo = true,
                listFolderPath = listFolderPath,
                out = map,
            )
        }
        mediaIds.mapNotNull { map[it] }
    }

    private fun loadMediaByIds(
        collection: Uri,
        storeIds: List<Long>,
        isVideo: Boolean,
        listFolderPath: String,
        out: MutableMap<Long, BrowseItem>,
    ) {
        // MediaStore IN 查询分批，避免过长
        storeIds.chunked(80).forEach { chunk ->
            val placeholders = chunk.joinToString(",") { "?" }
            val selection = MediaStore.MediaColumns._ID + " IN ($placeholders)"
            val args = chunk.map { it.toString() }.toTypedArray()
            runCatching {
                MediaStoreCompat.query(
                    resolver = context.contentResolver,
                    uri = collection,
                    projectionBuilder = { includeData, includeRelative ->
                        MediaStoreCompat.mediaByIdProjection(isVideo, includeData, includeRelative)
                    },
                    selection = selection,
                    selectionArgs = args,
                )?.use { cursor ->
                    val idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                    val displayCol = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                    val titleCol = cursor.getColumnIndex(MediaStore.MediaColumns.TITLE)
                    val durationCol = cursor.getColumnIndex(MediaStore.MediaColumns.DURATION)
                    val pathCol = cursor.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH)
                    val dataCol = cursor.getColumnIndex(MediaStore.MediaColumns.DATA)
                    val artistCol = cursor.getColumnIndex(MediaStore.Audio.Media.ARTIST)
                    val isMusicCol = if (!isVideo) {
                        cursor.getColumnIndex(MediaStore.Audio.Media.IS_MUSIC)
                    } else {
                        -1
                    }
                    while (cursor.moveToNext()) {
                        if (isMusicCol >= 0 && cursor.getInt(isMusicCol) == 0) continue
                        val storeId = cursor.getLong(idCol)
                        val mediaId = if (isVideo) storeId or VIDEO_ID_FLAG else storeId
                        val displayName = if (displayCol >= 0) cursor.getString(displayCol) else null
                        val title = if (titleCol >= 0) cursor.getString(titleCol) else null
                        val name = displayName?.takeIf { it.isNotBlank() }
                            ?: title?.takeIf { it.isNotBlank() }
                            ?: "未命名"
                        val artist = if (artistCol >= 0) cursor.getString(artistCol) else null
                        val subtitle = when {
                            isVideo -> "视频 · 仅声音"
                            !artist.isNullOrBlank() && !artist.equals("<unknown>", true) -> artist
                            else -> "音频"
                        }
                        val relative = MediaStoreCompat.readRelativeFolder(cursor, pathCol, dataCol)
                        val data = if (dataCol >= 0) cursor.getString(dataCol) else null
                        val durationMs = if (durationCol >= 0) {
                            cursor.getLong(durationCol).coerceAtLeast(0L)
                        } else {
                            0L
                        }
                        val uri = ContentUris.withAppendedId(collection, storeId)
                        out[mediaId] = BrowseItem(
                            type = BrowseItemType.FILE,
                            name = name,
                            folderPath = listFolderPath,
                            uri = uri,
                            mediaId = mediaId,
                            durationMs = durationMs,
                            isVideo = isVideo,
                            subtitle = subtitle,
                            filePath = resolveFilePath(data, relative, displayName),
                        )
                    }
                }
            }
        }
    }

    /**
     * 同级下一个「有直接媒体」的文件夹及其曲目。
     * 到最后一个后回到第一个；若仅有一个则返回自身（相当于文件夹循环）。
     */
    suspend fun nextFolderPlaylist(currentFolder: String): Pair<String, List<PlayableMedia>>? =
        withContext(Dispatchers.IO) {
            siblingFolderPlaylist(currentFolder, forward = true)
        }

    suspend fun previousFolderPlaylist(currentFolder: String): Pair<String, List<PlayableMedia>>? =
        withContext(Dispatchers.IO) {
            siblingFolderPlaylist(currentFolder, forward = false)
        }

    private fun siblingFolderPlaylist(
        currentFolder: String,
        forward: Boolean,
    ): Pair<String, List<PlayableMedia>>? {
        val current = normalizeFolder(currentFolder)
        if (current.isEmpty()) return null
        val siblingFolders = MediaTreeCache.siblingFoldersWithFiles(context, current)
        if (siblingFolders.isEmpty()) return null

        val idx = siblingFolders.indexOf(current).let { if (it < 0) 0 else it }
        val nextIdx = if (forward) {
            (idx + 1) % siblingFolders.size
        } else {
            (idx - 1 + siblingFolders.size) % siblingFolders.size
        }
        val nextPath = siblingFolders[nextIdx]
        val playables = MediaTreeCache.listPlayable(context, nextPath).mapNotNull { it.toPlayable() }
        if (playables.isEmpty()) return null
        return nextPath to playables
    }

    /** 强制全量重扫 MediaStore 树缓存 */
    suspend fun rescanTree(): MediaTreeCache.Snapshot = withContext(Dispatchers.IO) {
        MediaTreeCache.fullScan(context)
    }

    /** 媒体增删改后使缓存失效（下次进入会重扫） */
    fun invalidateCache(clearDisk: Boolean = false) {
        MediaTreeCache.invalidate(clearDisk = clearDisk, ctx = context)
    }

    private fun resolveFilePath(dataPath: String?, relative: String, displayName: String?): String? {
        if (!dataPath.isNullOrBlank() && java.io.File(dataPath).exists()) {
            return dataPath
        }
        val name = displayName?.takeIf { it.isNotBlank() } ?: return null
        val rel = relative.trimStart('/')
        val candidates = listOf(
            "/storage/emulated/0/$rel$name",
            "/sdcard/$rel$name",
        )
        return candidates.firstOrNull { java.io.File(it).exists() }
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

    companion object {
        /** 与浏览列表一致：视频 mediaId 高位置位 */
        const val VIDEO_ID_FLAG = 1L shl 62

        fun normalizeFolder(path: String?): String {
            if (path.isNullOrBlank()) return ""
            var p = path.replace('\\', '/')
            while (p.startsWith("/")) p = p.removePrefix("/")
            if (p.isNotEmpty() && !p.endsWith("/")) p += "/"
            return p
        }

        fun parentFolder(path: String): String? {
            val n = normalizeFolder(path)
            if (n.isEmpty()) return null
            val trimmed = n.removeSuffix("/")
            val idx = trimmed.lastIndexOf('/')
            return if (idx < 0) "" else trimmed.substring(0, idx + 1)
        }

        fun displayPath(path: String): String {
            val n = normalizeFolder(path)
            return if (n.isEmpty()) "存储" else n.removeSuffix("/")
        }
    }
}
