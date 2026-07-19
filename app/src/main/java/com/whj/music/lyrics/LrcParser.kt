package com.whj.music.lyrics

import com.whj.music.model.LyricDisplay
import com.whj.music.model.LyricLine
import java.io.File
import java.nio.charset.Charset
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * LRC 解析与跟唱进度（参考 nvimplugins/music/lyrics.lua）
 * - 同时间戳中英文多行归为一组
 * - progress 0–1 表示当前句内进度（用于蒙版高亮）
 */
object LrcParser {
    private const val SAME_T = 0.08

    fun findLrcPath(audioPath: String?): String? {
        if (audioPath.isNullOrBlank()) return null
        val audio = File(audioPath)
        val base = audio.absolutePath.substringBeforeLast('.')
        val candidates = listOf(
            "$base.lrc",
            "$base.LRC",
            "$base.lrcx",
            "${audio.absolutePath}.lrc",
        )
        return candidates.firstOrNull { File(it).isFile }
    }

    fun parseFile(path: String): List<LyricLine> {
        val file = File(path)
        if (!file.isFile) return emptyList()
        val text = readTextGuessCharset(file)
        return parseContent(text)
    }

    fun parseContent(content: String): List<LyricLine> {
        val lines = mutableListOf<LyricLine>()
        for (raw in content.lineSequence()) {
            val line = raw.trimEnd('\r')
            if (line.isBlank()) continue
            var rest = line
            val times = mutableListOf<Double>()
            while (true) {
                val match = Regex("""^\[([0-9.:]+)](.*)""").find(rest) ?: break
                val tag = match.groupValues[1]
                val after = match.groupValues[2]
                parseTime(tag)?.let { times += it }
                rest = after
            }
            var text = rest.trim()
            if (times.isEmpty()) continue
            if (text.isEmpty()) text = " "
            for (t in times) {
                lines += LyricLine(t, text)
            }
        }
        return lines.sortedWith(compareBy({ it.timeSec }, { it.text }))
    }

    fun getCurrentDisplay(lines: List<LyricLine>, posSec: Double): LyricDisplay {
        if (lines.isEmpty()) {
            return LyricDisplay(emptyList(), 0f, 0, 0, lines)
        }
        val idx = indexAt(lines, posSec)
        if (idx < 0) {
            return LyricDisplay(emptyList(), 0f, -1, -1, lines)
        }
        val (i0, i1) = groupRange(lines, idx)
        val t0 = lines[i0].timeSec
        val t1 = groupEndTime(lines, i1)
        var progress = ((posSec - t0) / max(0.05, t1 - t0)).toFloat()
        progress = max(0f, min(1f, progress))
        val texts = (i0..i1).map { lines[it].text }
        return LyricDisplay(texts, progress, i0, i1, lines)
    }

    /** UTF-8 安全：按字符进度切开（用于句内蒙版） */
    fun splitProgress(text: String, progress: Float): Pair<String, String> {
        if (text.isEmpty()) return "" to ""
        val codePoints = text.codePoints().toArray()
        val n = codePoints.size
        val k = max(0, min(n, (n * progress + 1e-6).toInt()))
        val sung = String(codePoints, 0, k)
        val rest = String(codePoints, k, n - k)
        return sung to rest
    }

    /**
     * 上一句时间（秒）。
     * 若已进入当前句超过 1.5 秒，则回到当前句开头；否则跳到上一句组。
     */
    fun prevSentenceTimeSec(lines: List<LyricLine>, posSec: Double): Double {
        if (lines.isEmpty()) return 0.0
        val idx = indexAt(lines, posSec)
        if (idx < 0) return 0.0
        val (i0, _) = groupRange(lines, idx)
        val t0 = lines[i0].timeSec
        if (posSec - t0 > 1.5) return t0
        if (i0 <= 0) return 0.0
        val (p0, _) = groupRange(lines, i0 - 1)
        return lines[p0].timeSec
    }

    /** 下一句时间（秒）。若在第一句之前则跳到第一句。 */
    fun nextSentenceTimeSec(lines: List<LyricLine>, posSec: Double): Double {
        if (lines.isEmpty()) return 0.0
        val idx = indexAt(lines, posSec)
        if (idx < 0) return lines[0].timeSec
        val (_, i1) = groupRange(lines, idx)
        if (i1 >= lines.lastIndex) return lines[i1].timeSec
        // 下一组起点：i1 之后第一个时间戳
        val (n0, _) = groupRange(lines, i1 + 1)
        return lines[n0].timeSec
    }

    private fun indexAt(lines: List<LyricLine>, pos: Double): Int {
        if (lines.isEmpty()) return -1
        if (pos < lines[0].timeSec) return -1
        var lo = 0
        var hi = lines.lastIndex
        var ans = 0
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (lines[mid].timeSec <= pos) {
                ans = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return ans
    }

    private fun groupRange(lines: List<LyricLine>, idx: Int): Pair<Int, Int> {
        if (idx !in lines.indices) return -1 to -1
        val t0 = lines[idx].timeSec
        var i0 = idx
        var i1 = idx
        while (i0 > 0 && abs(lines[i0 - 1].timeSec - t0) <= SAME_T) i0--
        while (i1 < lines.lastIndex && abs(lines[i1 + 1].timeSec - t0) <= SAME_T) i1++
        return i0 to i1
    }

    private fun groupEndTime(lines: List<LyricLine>, i1: Int): Double {
        if (i1 < 0) return lines.firstOrNull()?.timeSec ?: 0.0
        if (i1 < lines.lastIndex) return lines[i1 + 1].timeSec
        return lines[i1].timeSec + 5.0
    }

    private fun parseTime(tag: String): Double? {
        val withFrac = Regex("""^(\d+):(\d+)\.(\d+)$""").find(tag)
        if (withFrac != null) {
            val m = withFrac.groupValues[1].toInt()
            val s = withFrac.groupValues[2].toInt()
            val cs = withFrac.groupValues[3]
            var frac = cs.toDoubleOrNull() ?: 0.0
            frac /= when (cs.length) {
                1 -> 10.0
                2 -> 100.0
                else -> Math.pow(10.0, cs.length.toDouble())
            }
            return m * 60 + s + frac
        }
        val plain = Regex("""^(\d+):(\d+)$""").find(tag) ?: return null
        return plain.groupValues[1].toInt() * 60.0 + plain.groupValues[2].toInt()
    }

    private fun readTextGuessCharset(file: File): String {
        val bytes = file.readBytes()
        // UTF-8 BOM
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        }
        // try UTF-8
        val utf8 = runCatching { String(bytes, Charsets.UTF_8) }.getOrNull()
        if (utf8 != null && !utf8.contains('\uFFFD')) return utf8
        // GBK common for Chinese LRC
        return runCatching {
            String(bytes, Charset.forName("GBK"))
        }.getOrElse {
            String(bytes, Charsets.UTF_8)
        }
    }
}
