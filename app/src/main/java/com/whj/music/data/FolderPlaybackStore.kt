package com.whj.music.data

import android.content.Context
import android.net.Uri
import com.whj.music.model.PlayableMedia
import java.security.MessageDigest

/**
 * 按文件夹记录上次播放的曲目与进度。
 * folderPath 使用 [FolderBrowser.normalizeFolder] 后的相对路径。
 */
object FolderPlaybackStore {
    private const val PREFS = "folder_playback_prefs"

    data class Snapshot(
        val media: PlayableMedia,
        val positionMs: Int,
        val updatedAtMs: Long = 0L,
    )

    /** 有播放记录的文件夹摘要（「播放记录」列表用） */
    data class FolderEntry(
        val folderPath: String,
        val mediaTitle: String,
        val updatedAtMs: Long,
    )

    fun save(context: Context, media: PlayableMedia?, positionMs: Int) {
        if (media == null) return
        val folder = FolderBrowser.normalizeFolder(media.folderPath)
        if (folder.isEmpty()) return
        // 播放列表虚拟路径不记入
        if (PlaylistPaths.isInPlaylistSpace(folder)) return
        val p = prefix(folder)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong(p + "id", media.id)
            .putString(p + "uri", media.uri.toString())
            .putString(p + "title", media.title)
            .putString(p + "subtitle", media.subtitle)
            .putLong(p + "duration", media.durationMs)
            .putString(p + "folder", folder)
            .putString(p + "file_path", media.filePath)
            .putBoolean(p + "is_video", media.isVideo)
            .putInt(p + "position", positionMs.coerceAtLeast(0))
            .putLong(p + "updated", System.currentTimeMillis())
            .apply()
    }

    fun load(context: Context, folderPath: String): Snapshot? {
        val folder = FolderBrowser.normalizeFolder(folderPath)
        if (folder.isEmpty()) return null
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val p = prefix(folder)
        val uriStr = prefs.getString(p + "uri", null) ?: return null
        val uri = runCatching { Uri.parse(uriStr) }.getOrNull() ?: return null
        val id = prefs.getLong(p + "id", 0L)
        val media = PlayableMedia(
            id = id,
            title = prefs.getString(p + "title", null) ?: "未知曲目",
            subtitle = prefs.getString(p + "subtitle", null) ?: "",
            durationMs = prefs.getLong(p + "duration", 0L),
            uri = uri,
            isVideo = prefs.getBoolean(p + "is_video", false),
            folderPath = prefs.getString(p + "folder", null) ?: folder,
            filePath = prefs.getString(p + "file_path", null),
        )
        return Snapshot(
            media = media,
            positionMs = prefs.getInt(p + "position", 0),
            updatedAtMs = prefs.getLong(p + "updated", 0L),
        )
    }

    /** 该文件夹是否有上次播放记录（用于列表绿点标记） */
    fun has(context: Context, folderPath: String): Boolean {
        val folder = FolderBrowser.normalizeFolder(folderPath)
        if (folder.isEmpty()) return false
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.contains(prefix(folder) + "uri")
    }

    /**
     * 列出全部有记录的文件夹，按 [FolderEntry.updatedAtMs] 降序。
     * 旧数据无 updated 时排在末尾（仍保留）。
     */
    fun listAll(context: Context): List<FolderEntry> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val prefixes = prefs.all.keys
            .asSequence()
            .filter { it.startsWith("f_") && it.endsWith("_uri") }
            .map { it.removeSuffix("uri") }
            .distinct()
            .toList()
        val out = ArrayList<FolderEntry>(prefixes.size)
        for (p in prefixes) {
            val folder = FolderBrowser.normalizeFolder(
                prefs.getString(p + "folder", null).orEmpty(),
            )
            if (folder.isEmpty()) continue
            if (PlaylistPaths.isInPlaylistSpace(folder)) continue
            if (!prefs.contains(p + "uri")) continue
            out.add(
                FolderEntry(
                    folderPath = folder,
                    mediaTitle = prefs.getString(p + "title", null).orEmpty().ifBlank { "未知曲目" },
                    updatedAtMs = prefs.getLong(p + "updated", 0L),
                ),
            )
        }
        return out.sortedWith(
            compareByDescending<FolderEntry> { it.updatedAtMs }
                .thenBy { it.folderPath },
        )
    }

    private fun prefix(folder: String): String {
        val digest = MessageDigest.getInstance("SHA-1")
            .digest(folder.toByteArray(Charsets.UTF_8))
        val hex = digest.joinToString("") { "%02x".format(it) }.take(16)
        return "f_${hex}_"
    }
}
