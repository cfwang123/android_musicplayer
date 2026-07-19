package com.whj.music.model

import android.net.Uri

enum class BrowseItemType {
    FOLDER,
    FILE,
}

/** 播放循环模式 */
enum class PlayMode {
    REPEAT_ONE,
    REPEAT_FOLDER,
    NEXT_FOLDER,
    /** 文件夹内随机；上一曲走播放历史 */
    SHUFFLE,
    ;

    fun next(): PlayMode = when (this) {
        REPEAT_ONE -> REPEAT_FOLDER
        REPEAT_FOLDER -> NEXT_FOLDER
        NEXT_FOLDER -> SHUFFLE
        SHUFFLE -> REPEAT_ONE
    }
}

data class BrowseItem(
    val type: BrowseItemType,
    val name: String,
    val folderPath: String,
    val uri: Uri? = null,
    val mediaId: Long = 0L,
    val durationMs: Long = 0L,
    val isVideo: Boolean = false,
    val subtitle: String = "",
    val directFileCount: Int = 0,
    /** 文件系统绝对路径（若可解析），用于找同目录 .lrc */
    val filePath: String? = null,
) {
    fun toPlayable(): PlayableMedia? {
        if (type != BrowseItemType.FILE || uri == null) return null
        return PlayableMedia(
            id = mediaId,
            title = name,
            subtitle = subtitle.ifBlank { if (isVideo) "视频（仅声音）" else "音频" },
            durationMs = durationMs,
            uri = uri,
            isVideo = isVideo,
            folderPath = folderPath,
            filePath = filePath,
        )
    }
}

data class PlayableMedia(
    val id: Long,
    val title: String,
    val subtitle: String,
    val durationMs: Long,
    val uri: Uri,
    val isVideo: Boolean,
    val folderPath: String = "",
    val filePath: String? = null,
) {
    fun formatDuration(): String = formatTime(durationMs)

    companion object {
        fun formatTime(ms: Long): String {
            if (ms <= 0L) return "0:00"
            val totalSeconds = ms / 1000
            val minutes = totalSeconds / 60
            val seconds = totalSeconds % 60
            return "%d:%02d".format(minutes, seconds)
        }

        fun formatCountdown(ms: Long): String {
            val total = (ms / 1000).coerceAtLeast(0)
            val h = total / 3600
            val m = (total % 3600) / 60
            val s = total % 60
            return if (h > 0) {
                "%d:%02d:%02d".format(h, m, s)
            } else {
                "%d:%02d".format(m, s)
            }
        }
    }
}

data class LyricLine(
    val timeSec: Double,
    val text: String,
)

data class LyricDisplay(
    val texts: List<String>,
    val progress: Float,
    val indexStart: Int,
    val indexEnd: Int,
    val allLines: List<LyricLine>,
    val message: String? = null,
)
