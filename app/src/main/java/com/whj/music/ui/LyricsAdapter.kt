package com.whj.music.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.whj.music.databinding.ItemLyricBinding
import com.whj.music.model.LyricLine

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
                // 旧当前句与新当前句刷新（去掉/加上蒙版）
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
                    notifyItemRangeChanged(from.coerceIn(0, lines.lastIndex), count.coerceAtMost(lines.size - from))
                }
            }
            start >= 0 && end >= start -> {
                // 仅进度变化：payload 更新，减少闪烁
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
        holder.bind(lines[position].text, if (current) progress else 0f, current)
    }

    override fun getItemCount(): Int = lines.size

    class Holder(
        private val binding: ItemLyricBinding,
    ) : RecyclerView.ViewHolder(binding.root) {
        private var lastText: String = ""
        private var lastEmphasize: Boolean = false

        fun bind(text: String, progress: Float, emphasize: Boolean) {
            lastText = text
            lastEmphasize = emphasize
            binding.karaokeLine.setLine(text, progress, emphasize)
        }

        fun bindProgress(progress: Float) {
            binding.karaokeLine.setLine(lastText, progress, lastEmphasize)
        }
    }

    companion object {
        private const val PAYLOAD_PROGRESS = "progress"
    }
}
