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
    )

    fun save(context: Context, media: PlayableMedia?, positionMs: Int) {
        if (media == null) return
        val folder = FolderBrowser.normalizeFolder(media.folderPath)
        if (folder.isEmpty()) return
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
        return Snapshot(media, prefs.getInt(p + "position", 0))
    }

    private fun prefix(folder: String): String {
        val digest = MessageDigest.getInstance("SHA-1")
            .digest(folder.toByteArray(Charsets.UTF_8))
        val hex = digest.joinToString("") { "%02x".format(it) }.take(16)
        return "f_${hex}_"
    }
}
