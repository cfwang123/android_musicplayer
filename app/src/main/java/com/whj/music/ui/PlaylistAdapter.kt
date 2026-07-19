package com.whj.music.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.whj.music.R
import com.whj.music.databinding.ItemPlaylistBinding
import com.whj.music.model.PlayableMedia

class PlaylistAdapter(
    private val onClick: (PlayableMedia, Int) -> Unit,
) : RecyclerView.Adapter<PlaylistAdapter.Holder>() {

    private var items: List<PlayableMedia> = emptyList()
    private var currentIndex: Int = -1

    fun submit(list: List<PlayableMedia>, current: Int) {
        items = list
        currentIndex = current
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val binding = ItemPlaylistBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return Holder(binding)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(items[position], position == currentIndex)
    }

    override fun getItemCount(): Int = items.size

    inner class Holder(
        private val binding: ItemPlaylistBinding,
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: PlayableMedia, playing: Boolean) {
            binding.titleText.text = item.title
            binding.artistText.text = item.subtitle
            val color = AppTheme.resolveColor(
                binding.root.context,
                if (playing) R.attr.colorSheetPlaying else R.attr.colorTextPrimary,
            )
            binding.titleText.setTextColor(color)
            binding.artistText.setTextColor(
                AppTheme.resolveColor(binding.root.context, R.attr.colorTextSecondary),
            )
            binding.playingMark.setTextColor(
                AppTheme.resolveColor(binding.root.context, R.attr.colorSheetPlaying),
            )
            binding.playingMark.visibility = if (playing) View.VISIBLE else View.GONE
            binding.root.setOnClickListener {
                onClick(item, bindingAdapterPosition)
            }
        }
    }
}
