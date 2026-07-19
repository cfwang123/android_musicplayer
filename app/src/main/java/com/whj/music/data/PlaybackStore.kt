package com.whj.music.data

import android.content.Context
import android.net.Uri
import com.whj.music.model.PlayableMedia

/** 上次播放的曲目与进度（切换/退出时写入） */
object PlaybackStore {
    private const val PREFS = "music_player_prefs"
    private const val KEY_ID = "last_media_id"
    private const val KEY_URI = "last_uri"
    private const val KEY_TITLE = "last_title"
    private const val KEY_SUBTITLE = "last_subtitle"
    private const val KEY_DURATION = "last_duration"
    private const val KEY_FOLDER = "last_folder"
    private const val KEY_FILE_PATH = "last_file_path"
    private const val KEY_IS_VIDEO = "last_is_video"
    private const val KEY_POSITION = "last_position"

    data class Snapshot(
        val media: PlayableMedia,
        val positionMs: Int,
    )

    fun save(context: Context, media: PlayableMedia?, positionMs: Int) {
        if (media == null) return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong(KEY_ID, media.id)
            .putString(KEY_URI, media.uri.toString())
            .putString(KEY_TITLE, media.title)
            .putString(KEY_SUBTITLE, media.subtitle)
            .putLong(KEY_DURATION, media.durationMs)
            .putString(KEY_FOLDER, media.folderPath)
            .putString(KEY_FILE_PATH, media.filePath)
            .putBoolean(KEY_IS_VIDEO, media.isVideo)
            .putInt(KEY_POSITION, positionMs.coerceAtLeast(0))
            .apply()
    }

    fun load(context: Context): Snapshot? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val uriStr = prefs.getString(KEY_URI, null) ?: return null
        val uri = runCatching { Uri.parse(uriStr) }.getOrNull() ?: return null
        val id = prefs.getLong(KEY_ID, 0L)
        if (id == 0L && uriStr.isBlank()) return null
        val media = PlayableMedia(
            id = id,
            title = prefs.getString(KEY_TITLE, null) ?: "未知曲目",
            subtitle = prefs.getString(KEY_SUBTITLE, null) ?: "",
            durationMs = prefs.getLong(KEY_DURATION, 0L),
            uri = uri,
            isVideo = prefs.getBoolean(KEY_IS_VIDEO, false),
            folderPath = prefs.getString(KEY_FOLDER, null).orEmpty(),
            filePath = prefs.getString(KEY_FILE_PATH, null),
        )
        val position = prefs.getInt(KEY_POSITION, 0)
        return Snapshot(media, position)
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove(KEY_ID)
            .remove(KEY_URI)
            .remove(KEY_TITLE)
            .remove(KEY_SUBTITLE)
            .remove(KEY_DURATION)
            .remove(KEY_FOLDER)
            .remove(KEY_FILE_PATH)
            .remove(KEY_IS_VIDEO)
            .remove(KEY_POSITION)
            .apply()
    }
}
