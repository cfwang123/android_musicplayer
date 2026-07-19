package com.whj.music.ui

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.whj.music.R
import com.whj.music.databinding.ItemSongBinding
import com.whj.music.model.BrowseItem
import com.whj.music.model.BrowseItemType
import com.whj.music.model.PlayableMedia

class BrowseAdapter(
    private val onClick: (BrowseItem) -> Unit,
    private val onFavoriteClick: (BrowseItem) -> Unit,
    private val onItemLongClick: (BrowseItem) -> Unit,
    private val onStartDrag: ((RecyclerView.ViewHolder) -> Unit)? = null,
) : ListAdapter<BrowseItem, BrowseAdapter.Holder>(Diff) {

    private var playingMediaId: Long? = null
    private var playingFolderPath: String = ""
    private var favoriteKeys: Set<String> = emptySet()
    private var selectionMode: Boolean = false
    private var selectedKeys: Set<String> = emptySet()
    private var showDragHandle: Boolean = false
    private var foldersSelectable: Boolean = false

    fun setPlaying(mediaId: Long?, folderPath: String) {
        val changed = playingMediaId != mediaId || playingFolderPath != folderPath
        playingMediaId = mediaId
        playingFolderPath = folderPath
        if (changed) notifyDataSetChanged()
    }

    fun setFavorites(keys: Set<String>) {
        if (favoriteKeys == keys) return
        favoriteKeys = keys
        notifyDataSetChanged()
    }

    fun setSelectionState(
        enabled: Boolean,
        selected: Set<String>,
        dragEnabled: Boolean,
        foldersSelectable: Boolean = false,
    ) {
        val changed = selectionMode != enabled ||
            selectedKeys != selected ||
            showDragHandle != dragEnabled ||
            this.foldersSelectable != foldersSelectable
        selectionMode = enabled
        selectedKeys = selected
        showDragHandle = dragEnabled
        this.foldersSelectable = foldersSelectable
        if (changed) notifyDataSetChanged()
    }

    fun selectionKeyOf(item: BrowseItem): String = when (item.type) {
        BrowseItemType.FILE -> "m:" + item.mediaId
        BrowseItemType.FOLDER -> "f:" + item.folderPath
    }

    fun moveItem(from: Int, to: Int) {
        if (from == to) return
        if (from !in currentList.indices || to !in currentList.indices) return
        val mutable = currentList.toMutableList()
        val item = mutable.removeAt(from)
        mutable.add(to, item)
        submitList(mutable)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val binding = ItemSongBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return Holder(binding)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class Holder(
        private val binding: ItemSongBinding,
    ) : RecyclerView.ViewHolder(binding.root) {
        @SuppressLint("ClickableViewAccessibility")
        fun bind(item: BrowseItem) {
            binding.titleText.text = item.name
            binding.artistText.text = item.subtitle
            val ctx = binding.root.context
            val key = selectionKeyOf(item)
            val selected = selectionMode && selectedKeys.contains(key)
            val selectable = item.type == BrowseItemType.FILE ||
                (item.type == BrowseItemType.FOLDER && foldersSelectable)

            val isPlaying = when (item.type) {
                BrowseItemType.FOLDER ->
                    playingFolderPath.isNotEmpty() && item.folderPath == playingFolderPath
                BrowseItemType.FILE ->
                    playingMediaId != null && item.mediaId == playingMediaId
            }

            binding.playingBar.visibility =
                if (isPlaying && !selectionMode) View.VISIBLE else View.GONE
            binding.itemRoot.setBackgroundResource(
                when {
                    selected -> R.drawable.bg_item_selected
                    isPlaying -> R.drawable.bg_item_playing
                    else -> R.drawable.bg_song_item
                },
            )
            binding.titleText.setTextColor(
                AppTheme.resolveColor(
                    ctx,
                    if (isPlaying && !selected) {
                        R.attr.colorItemPlayingText
                    } else {
                        R.attr.colorTextPrimary
                    },
                ),
            )
            binding.artistText.setTextColor(
                AppTheme.resolveColor(ctx, R.attr.colorTextSecondary),
            )
            binding.durationText.setTextColor(
                AppTheme.resolveColor(ctx, R.attr.colorTextMuted),
            )

            val favorited = when (item.type) {
                BrowseItemType.FILE ->
                    item.mediaId != 0L && favoriteKeys.contains(item.mediaId.toString())
                BrowseItemType.FOLDER ->
                    favoriteKeys.contains("f:" + item.folderPath)
            }
            if (selectionMode) {
                binding.favoriteIcon.visibility = View.GONE
            } else {
                binding.favoriteIcon.visibility = View.VISIBLE
                binding.favoriteIcon.setImageResource(
                    if (favorited) R.drawable.ic_favorite else R.drawable.ic_favorite_border,
                )
                binding.favoriteIcon.setOnClickListener {
                    onFavoriteClick(item)
                }
            }

            when (item.type) {
                BrowseItemType.FOLDER -> {
                    binding.iconView.setImageResource(R.drawable.ic_folder)
                    binding.durationText.text = ctx.getString(
                        R.string.folder_song_count,
                        item.directFileCount,
                    )
                }
                BrowseItemType.FILE -> {
                    binding.iconView.setImageResource(
                        if (item.isVideo) R.drawable.ic_video else R.drawable.ic_music_note,
                    )
                    binding.durationText.text = PlayableMedia.formatTime(item.durationMs)
                }
            }

            if (selectionMode && selectable) {
                binding.checkBox.visibility = View.VISIBLE
                binding.checkBox.setImageResource(
                    if (selected) R.drawable.ic_check_box else R.drawable.ic_check_box_outline,
                )
            } else {
                binding.checkBox.visibility = View.GONE
            }

            val dragVisible = showDragHandle && item.type == BrowseItemType.FILE
            binding.dragHandle.visibility = if (dragVisible) View.VISIBLE else View.GONE
            if (dragVisible) {
                binding.dragHandle.setOnTouchListener { _, event ->
                    if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                        onStartDrag?.invoke(this)
                    }
                    false
                }
            } else {
                binding.dragHandle.setOnTouchListener(null)
            }

            binding.root.setOnClickListener { onClick(item) }
            binding.root.setOnLongClickListener {
                // 文件可长按多选；播放列表总览下文件夹（列表项）也可长按
                val canLong = item.type == BrowseItemType.FILE ||
                    (item.type == BrowseItemType.FOLDER && foldersSelectable)
                if (canLong) {
                    onItemLongClick(item)
                    true
                } else {
                    false
                }
            }
        }
    }

    private object Diff : DiffUtil.ItemCallback<BrowseItem>() {
        override fun areItemsTheSame(oldItem: BrowseItem, newItem: BrowseItem): Boolean {
            return oldItem.type == newItem.type &&
                oldItem.folderPath == newItem.folderPath &&
                oldItem.mediaId == newItem.mediaId &&
                oldItem.name == newItem.name &&
                oldItem.uri == newItem.uri
        }

        override fun areContentsTheSame(oldItem: BrowseItem, newItem: BrowseItem): Boolean {
            return oldItem == newItem
        }
    }
}
