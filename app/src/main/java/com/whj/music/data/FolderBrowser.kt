package com.whj.music.data

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.whj.music.R
import com.whj.music.model.BrowseItem
import com.whj.music.model.BrowseItemType
import com.whj.music.model.PlayableMedia
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 基于 MediaStore 的文件夹浏览器。
 * folderPath 使用相对路径，统一以 "/" 结尾（根目录为空字符串）。
 */
class FolderBrowser(private val context: Context) {

    suspend fun list(folderPath: String): List<BrowseItem> = withContext(Dispatchers.IO) {
        buildListing(normalizeFolder(folderPath)).items
    }

    /**
     * 1 屏根目录：仅展示配置的主目录（快捷入口）。
     * 副标题为「n 项」（直接文件 + 子文件夹）；[BrowseItem.directFileCount] 仍为曲目数。
     */
    suspend fun listConfiguredRoots(rootPaths: List<String>): List<BrowseItem> = withContext(Dispatchers.IO) {
        rootPaths
            .map { normalizeFolder(it) }
            .filter { it.isNotEmpty() }
            .distinct()
            .map { path ->
                val listing = buildListing(path)
                val directFiles = listing.items.count { it.type == BrowseItemType.FILE }
                val subFolders = listing.items.count { it.type == BrowseItemType.FOLDER }
                val name = path.trim('/').substringAfterLast('/').ifBlank { path.trim('/') }
                BrowseItem(
                    type = BrowseItemType.FOLDER,
                    name = name,
                    folderPath = path,
                    directFileCount = directFiles,
                    subtitle = context.getString(R.string.folder_item_count, subFolders + directFiles),
                )
            }
            .sortedBy { it.name.lowercase() }
    }

    /**
     * 扫描 MediaStore，返回所有含媒体文件的相对目录路径（可用于添加主目录）。
     */
    suspend fun listAllMediaFolderPaths(): List<String> = withContext(Dispatchers.IO) {
        val paths = linkedSetOf<String>()
        collectRelativePaths(audioCollection(), MediaStore.Audio.Media.RELATIVE_PATH, paths)
        collectRelativePaths(videoCollection(), MediaStore.Video.Media.RELATIVE_PATH, paths)
        paths.map { normalizeFolder(it) }.filter { it.isNotEmpty() }.sortedBy { it.lowercase() }
    }

    private fun collectRelativePaths(collection: Uri, relativeColumn: String, out: MutableSet<String>) {
        val projection = arrayOf(relativeColumn)
        runCatching {
            context.contentResolver.query(collection, projection, null, null, null)?.use { cursor ->
                val col = cursor.getColumnIndex(relativeColumn)
                if (col < 0) return
                while (cursor.moveToNext()) {
                    val rel = cursor.getString(col) ?: continue
                    val n = normalizeFolder(rel)
                    if (n.isNotEmpty()) out += n
                }
            }
        }
    }

    /** 当前文件夹内可连续播放的媒体（不含子文件夹） */
    suspend fun listPlayableInFolder(folderPath: String): List<PlayableMedia> = withContext(Dispatchers.IO) {
        buildListing(normalizeFolder(folderPath)).items.mapNotNull { it.toPlayable() }
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
        val parent = parentFolder(current) ?: return null
        val listing = buildListing(parent)
        val siblingFolders = listing.items
            .filter { it.type == BrowseItemType.FOLDER && it.directFileCount > 0 }
            .map { it.folderPath }

        if (siblingFolders.isEmpty()) return null

        val idx = siblingFolders.indexOf(current).let { if (it < 0) 0 else it }
        val nextIdx = if (forward) {
            (idx + 1) % siblingFolders.size
        } else {
            (idx - 1 + siblingFolders.size) % siblingFolders.size
        }
        val nextPath = siblingFolders[nextIdx]
        val playables = buildListing(nextPath).items.mapNotNull { it.toPlayable() }
        if (playables.isEmpty()) return null
        return nextPath to playables
    }

    private data class Listing(
        val items: List<BrowseItem>,
        val directFileCounts: Map<String, Int>,
    )

    private fun buildListing(folderPath: String): Listing {
        val folders = linkedMapOf<String, BrowseItem>()
        val files = mutableListOf<BrowseItem>()
        val directFileCounts = mutableMapOf<String, Int>()
        // 每个目录下的直接子文件夹名（用于「n 项」= 文件 + 子文件夹）
        val childFolderNames = mutableMapOf<String, MutableSet<String>>()

        scanAudio(folderPath, folders, files, directFileCounts, childFolderNames)
        scanVideo(folderPath, folders, files, directFileCounts, childFolderNames)

        val folderList = folders.values.map { folder ->
            val fileCount = directFileCounts[folder.folderPath] ?: 0
            val subCount = childFolderNames[folder.folderPath]?.size ?: 0
            folder.copy(
                directFileCount = fileCount,
                // 副标题：n 项；右侧仍用 directFileCount 显示「n 首」
                subtitle = context.getString(R.string.folder_item_count, fileCount + subCount),
            )
        }.sortedBy { it.name.lowercase() }

        val fileList = files.sortedBy { it.name.lowercase() }
        return Listing(folderList + fileList, directFileCounts)
    }

    private fun scanAudio(
        folderPath: String,
        folders: MutableMap<String, BrowseItem>,
        files: MutableList<BrowseItem>,
        directFileCounts: MutableMap<String, Int>,
        childFolderNames: MutableMap<String, MutableSet<String>>,
    ) {
        val collection = audioCollection()
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.RELATIVE_PATH,
            MediaStore.Audio.Media.IS_MUSIC,
            MediaStore.Audio.Media.DATA,
        )

        context.contentResolver.query(
            collection,
            projection,
            null,
            null,
            "${MediaStore.Audio.Media.DISPLAY_NAME} COLLATE NOCASE ASC",
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val displayCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
            val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val pathCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.RELATIVE_PATH)
            val isMusicCol = cursor.getColumnIndex(MediaStore.Audio.Media.IS_MUSIC)
            val dataCol = cursor.getColumnIndex(MediaStore.Audio.Media.DATA)

            while (cursor.moveToNext()) {
                if (isMusicCol >= 0 && cursor.getInt(isMusicCol) == 0) continue
                val relative = normalizeFolder(cursor.getString(pathCol).orEmpty())
                val data = if (dataCol >= 0) cursor.getString(dataCol) else null
                processEntry(
                    folderPath = folderPath,
                    relative = relative,
                    id = cursor.getLong(idCol),
                    displayName = cursor.getString(displayCol),
                    title = cursor.getString(titleCol),
                    artist = cursor.getString(artistCol),
                    durationMs = cursor.getLong(durationCol),
                    isVideo = false,
                    collection = collection,
                    folders = folders,
                    files = files,
                    directFileCounts = directFileCounts,
                    childFolderNames = childFolderNames,
                    dataPath = data,
                )
            }
        }
    }

    private fun scanVideo(
        folderPath: String,
        folders: MutableMap<String, BrowseItem>,
        files: MutableList<BrowseItem>,
        directFileCounts: MutableMap<String, Int>,
        childFolderNames: MutableMap<String, MutableSet<String>>,
    ) {
        val collection = videoCollection()
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.TITLE,
            MediaStore.Video.Media.ARTIST,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.RELATIVE_PATH,
            MediaStore.Video.Media.DATA,
        )

        context.contentResolver.query(
            collection,
            projection,
            null,
            null,
            "${MediaStore.Video.Media.DISPLAY_NAME} COLLATE NOCASE ASC",
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            val displayCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
            val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.TITLE)
            val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.ARTIST)
            val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
            val pathCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.RELATIVE_PATH)
            val dataCol = cursor.getColumnIndex(MediaStore.Video.Media.DATA)

            while (cursor.moveToNext()) {
                val relative = normalizeFolder(cursor.getString(pathCol).orEmpty())
                val data = if (dataCol >= 0) cursor.getString(dataCol) else null
                processEntry(
                    folderPath = folderPath,
                    relative = relative,
                    id = cursor.getLong(idCol),
                    displayName = cursor.getString(displayCol),
                    title = cursor.getString(titleCol),
                    artist = cursor.getString(artistCol),
                    durationMs = cursor.getLong(durationCol),
                    isVideo = true,
                    collection = collection,
                    folders = folders,
                    files = files,
                    directFileCounts = directFileCounts,
                    childFolderNames = childFolderNames,
                    dataPath = data,
                )
            }
        }
    }

    private fun processEntry(
        folderPath: String,
        relative: String,
        id: Long,
        displayName: String?,
        title: String?,
        artist: String?,
        durationMs: Long,
        isVideo: Boolean,
        collection: Uri,
        folders: MutableMap<String, BrowseItem>,
        files: MutableList<BrowseItem>,
        directFileCounts: MutableMap<String, Int>,
        childFolderNames: MutableMap<String, MutableSet<String>>,
        dataPath: String?,
    ) {
        // 统计该文件所在目录的直接文件数（不含更深层）
        directFileCounts[relative] = (directFileCounts[relative] ?: 0) + 1
        // 登记路径上的父子文件夹关系，便于算「文件 + 子文件夹」项数
        registerChildFolders(relative, childFolderNames)

        if (!relative.startsWith(folderPath)) return

        val remainder = relative.removePrefix(folderPath)
        if (remainder.isNotEmpty()) {
            // 子文件夹：取下一级目录名
            val childName = remainder.trim('/').substringBefore('/')
            if (childName.isEmpty()) return
            val childPath = folderPath + childName + "/"
            if (!folders.containsKey(childPath)) {
                folders[childPath] = BrowseItem(
                    type = BrowseItemType.FOLDER,
                    name = childName,
                    folderPath = childPath,
                    subtitle = "",
                )
            }
            return
        }

        // 当前目录下的文件
        val name = displayName?.takeIf { it.isNotBlank() }
            ?: title?.takeIf { it.isNotBlank() }
            ?: "未命名"
        val subtitle = when {
            isVideo -> "视频 · 仅声音"
            !artist.isNullOrBlank() && !artist.equals("<unknown>", true) -> artist
            else -> "音频"
        }
        val uri = ContentUris.withAppendedId(collection, id)
        val mediaId = if (isVideo) id or VIDEO_ID_FLAG else id

        val resolvedPath = resolveFilePath(dataPath, relative, displayName)
        files += BrowseItem(
            type = BrowseItemType.FILE,
            name = name,
            folderPath = folderPath,
            uri = uri,
            mediaId = mediaId,
            durationMs = durationMs.coerceAtLeast(0L),
            isVideo = isVideo,
            subtitle = subtitle,
            filePath = resolvedPath,
        )
    }

    /** 将 relative 路径各段登记为父目录的直接子文件夹 */
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
        private const val VIDEO_ID_FLAG = 1L shl 62

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
