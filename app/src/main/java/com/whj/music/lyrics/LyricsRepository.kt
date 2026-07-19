package com.whj.music.lyrics

import com.whj.music.model.LyricDisplay
import com.whj.music.model.LyricLine
import com.whj.music.model.PlayableMedia
import java.io.File
import java.util.concurrent.ConcurrentHashMap

class LyricsRepository {
    private val cache = ConcurrentHashMap<String, CacheEntry>()

    data class CacheEntry(
        val lrcPath: String?,
        val lines: List<LyricLine>,
        val message: String?,
    )

    fun loadFor(media: PlayableMedia?): CacheEntry {
        if (media == null) {
            return CacheEntry(null, emptyList(), null)
        }
        val key = media.filePath ?: media.uri.toString()
        cache[key]?.let { return it }

        val audioPath = media.filePath
        if (audioPath.isNullOrBlank()) {
            val entry = CacheEntry(null, emptyList(), "无法定位音频文件路径，无法匹配歌词")
            cache[key] = entry
            return entry
        }
        val lrc = LrcParser.findLrcPath(audioPath)
        if (lrc == null) {
            val base = File(audioPath).nameWithoutExtension
            val entry = CacheEntry(null, emptyList(), "未找到歌词（期望同目录 $base.lrc）")
            cache[key] = entry
            return entry
        }
        val lines = LrcParser.parseFile(lrc)
        val entry = if (lines.isEmpty()) {
            CacheEntry(lrc, emptyList(), "歌词文件为空或无时间轴")
        } else {
            CacheEntry(lrc, lines, null)
        }
        cache[key] = entry
        return entry
    }

    fun displayAt(media: PlayableMedia?, positionMs: Int): LyricDisplay {
        val entry = loadFor(media)
        if (entry.lines.isEmpty()) {
            return LyricDisplay(
                texts = emptyList(),
                progress = 0f,
                indexStart = -1,
                indexEnd = -1,
                allLines = emptyList(),
                message = entry.message,
            )
        }
        val d = LrcParser.getCurrentDisplay(entry.lines, positionMs / 1000.0)
        return d.copy(message = null)
    }

    fun hasTimedLyrics(media: PlayableMedia?): Boolean {
        return loadFor(media).lines.isNotEmpty()
    }

    /** 上一句 / 下一句 seek 目标（毫秒） */
    fun prevSentencePositionMs(media: PlayableMedia?, positionMs: Int): Int? {
        val lines = loadFor(media).lines
        if (lines.isEmpty()) return null
        return (LrcParser.prevSentenceTimeSec(lines, positionMs / 1000.0) * 1000.0).toInt()
    }

    fun nextSentencePositionMs(media: PlayableMedia?, positionMs: Int): Int? {
        val lines = loadFor(media).lines
        if (lines.isEmpty()) return null
        return (LrcParser.nextSentenceTimeSec(lines, positionMs / 1000.0) * 1000.0).toInt()
    }

    fun clear() = cache.clear()
}
