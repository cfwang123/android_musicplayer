package com.whj.music.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.whj.music.databinding.ItemLyricBinding
import com.whj.music.model.LyricLine
import kotlin.math.abs

class LyricsAdapter : RecyclerView.Adapter<LyricsAdapter.Holder>() {

    private var lines: List<LyricLine> = emptyList()
    private var currentStart = -1
    private var currentEnd = -1
    private var progress = 0f

    /** 返回是否切换了当前句（用于外部决定是否滚动，进度更新不滚动） */
    fun submit(lines: List<LyricLine>, start: Int, end: Int, progress: Float): Boolean {
        val linesChanged = this.lines.size != lines.size || this.lines !== lines
        val indexChanged = currentStart != start || currentEnd != end
        val oldStart = currentStart
        val oldEnd = currentEnd

        this.lines = lines
        currentStart = start
        currentEnd = end
        this.progress = progress

        when {
            linesChanged -> notifyDataSetChanged()
            indexChanged -> {
                val from = minOf(
                    if (oldStart < 0) start else oldStart,
                    if (start < 0) oldStart else start,
                ).coerceAtLeast(0)
                val to = maxOf(
                    if (oldEnd < 0) end else oldEnd,
                    if (end < 0) oldEnd else end,
                ).coerceAtLeast(from)
                if (lines.isNotEmpty()) {
                    val count = (to - from + 1).coerceAtLeast(1)
                    notifyItemRangeChanged(
                        from.coerceIn(0, lines.lastIndex),
                        count.coerceAtMost(lines.size - from),
                    )
                }
            }
            start >= 0 && end >= start -> {
                notifyItemRangeChanged(start, end - start + 1, PAYLOAD_PROGRESS)
            }
        }
        return indexChanged || linesChanged
    }

    fun currentCenterIndex(): Int {
        if (currentStart < 0) return -1
        return (currentStart + currentEnd) / 2
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val binding = ItemLyricBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return Holder(binding)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        bindFull(holder, position)
    }

    override fun onBindViewHolder(holder: Holder, position: Int, payloads: MutableList<Any>) {
        if (payloads.contains(PAYLOAD_PROGRESS)) {
            val current = position in currentStart..currentEnd && currentStart >= 0
            holder.bindProgress(if (current) progress else 0f)
        } else {
            bindFull(holder, position)
        }
    }

    private fun bindFull(holder: Holder, position: Int) {
        val current = position in currentStart..currentEnd && currentStart >= 0
        // 同时间戳组（中英文）行间距小；不同句之间 bottom=5dp
        val nextSameGroup = position < lines.lastIndex &&
            abs(lines[position].timeSec - lines[position + 1].timeSec) <= SAME_GROUP_T
        holder.bind(
            text = lines[position].text,
            progress = if (current) progress else 0f,
            emphasize = current,
            tightWithNext = nextSameGroup,
        )
    }

    override fun getItemCount(): Int = lines.size

    class Holder(
        private val binding: ItemLyricBinding,
    ) : RecyclerView.ViewHolder(binding.root) {
        private var lastText: String = ""
        private var lastEmphasize: Boolean = false
        private val density = binding.root.resources.displayMetrics.density
        private val padH = (24 * density).toInt()
        private val padTight = (1 * density).toInt()
        private val padSentence = (5 * density).toInt()

        fun bind(text: String, progress: Float, emphasize: Boolean, tightWithNext: Boolean) {
            lastText = text
            lastEmphasize = emphasize
            binding.karaokeLine.setLine(text, progress, emphasize)
            val bottom = if (tightWithNext) padTight else padSentence
            binding.root.setPadding(padH, padTight, padH, bottom)
        }

        fun bindProgress(progress: Float) {
            binding.karaokeLine.setLine(lastText, progress, lastEmphasize)
        }
    }

    companion object {
        private const val PAYLOAD_PROGRESS = "progress"
        /** 与 LrcParser.SAME_T 一致：同时间戳中英文归为一句 */
        private const val SAME_GROUP_T = 0.08
    }
}
