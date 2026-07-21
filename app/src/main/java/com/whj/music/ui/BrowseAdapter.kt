package com.whj.music.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.whj.music.R
import com.whj.music.data.CoverArtLoader
import com.whj.music.databinding.ItemSongBinding
import com.whj.music.model.BrowseItem
import com.whj.music.model.BrowseItemType
import com.whj.music.model.PlayableMedia
import com.whj.music.util.DisplayCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

    private val adapterScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

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
        binding.coverFrame.clipToOutline = true
        binding.coverFrame.outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
        return Holder(binding)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(getItem(position))
    }

    override fun onViewRecycled(holder: Holder) {
        holder.cancelCoverLoad()
        super.onViewRecycled(holder)
    }

    inner class Holder(
        private val binding: ItemSongBinding,
    ) : RecyclerView.ViewHolder(binding.root) {
        private var coverJob: Job? = null
        private var boundMediaId: Long = 0L

        fun cancelCoverLoad() {
            coverJob?.cancel()
            coverJob = null
            boundMediaId = 0L
        }

        @SuppressLint("ClickableViewAccessibility")
        fun bind(item: BrowseItem) {
            binding.titleText.text = item.name
            val ctx = binding.root.context
            val key = selectionKeyOf(item)
            val selected = selectionMode && selectedKeys.contains(key)
            val selectable = item.type == BrowseItemType.FILE ||
                (item.type == BrowseItemType.FOLDER && foldersSelectable)

            val isPlaying = !selectionMode && when (item.type) {
                BrowseItemType.FOLDER ->
                    playingFolderPath.isNotEmpty() && item.folderPath == playingFolderPath
                BrowseItemType.FILE ->
                    playingMediaId != null && item.mediaId == playingMediaId
            }

            // 彩色屏：淡色主题高亮；墨水屏：较深蓝灰底（能看出灰色即可）+ 深色字
            val mono = DisplayCompat.isNonColorScreen(ctx)
            val monoPlaying = isPlaying && mono
            // 墨水屏不再用反色竖条，靠灰底区分即可
            binding.playingBar.visibility = View.GONE
            binding.itemRoot.setBackgroundResource(
                when {
                    selected -> R.drawable.bg_item_selected
                    monoPlaying -> R.drawable.bg_item_playing_mono
                    isPlaying -> R.drawable.bg_item_playing
                    else -> R.drawable.bg_song_item
                },
            )
            val titleColor = when {
                monoPlaying -> 0xFF1A1A1A.toInt()
                isPlaying -> AppTheme.resolveColor(ctx, R.attr.colorItemPlayingText)
                else -> AppTheme.resolveColor(ctx, R.attr.colorTextPrimary)
            }
            val subColor = when {
                monoPlaying -> 0xFF2A2A2A.toInt()
                isPlaying -> AppTheme.resolveColor(ctx, R.attr.colorItemPlayingSubtext)
                else -> AppTheme.resolveColor(ctx, R.attr.colorTextSecondary)
            }
            val mutedColor = when {
                monoPlaying -> 0xFF333333.toInt()
                isPlaying -> AppTheme.resolveColor(ctx, R.attr.colorItemPlayingSubtext)
                else -> AppTheme.resolveColor(ctx, R.attr.colorTextMuted)
            }
            binding.titleText.setTextColor(titleColor)
            binding.titleText.paint.isFakeBoldText = monoPlaying
            binding.artistText.setTextColor(subColor)
            binding.durationText.setTextColor(mutedColor)

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
                binding.favoriteIcon.clearColorFilter()
                binding.favoriteIcon.setOnClickListener {
                    onFavoriteClick(item)
                }
            }

            when (item.type) {
                BrowseItemType.FOLDER -> {
                    cancelCoverLoad()
                    showListIconPlaceholder(isVideo = false, isFolder = true)
                    // 文件夹右侧不再显示「xx 首」
                    binding.durationText.visibility = View.GONE
                    binding.durationText.text = ""
                    // 第二行：n 项等有信息时保留
                    val sub = item.subtitle.trim()
                    if (sub.isEmpty()) {
                        binding.artistText.visibility = View.GONE
                        binding.artistText.text = ""
                    } else {
                        binding.artistText.visibility = View.VISIBLE
                        binding.artistText.text = sub
                    }
                }
                BrowseItemType.FILE -> {
                    binding.durationText.visibility = View.VISIBLE
                    binding.durationText.text = PlayableMedia.formatTime(item.durationMs)
                    // 第二行仅为占位「音频」时不显示
                    val sub = item.subtitle.trim()
                    if (sub.isEmpty() || isGenericAudioSubtitle(sub)) {
                        binding.artistText.visibility = View.GONE
                        binding.artistText.text = ""
                    } else {
                        binding.artistText.visibility = View.VISIBLE
                        binding.artistText.text = sub
                    }
                    bindFileCover(item)
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

        private fun bindFileCover(item: BrowseItem) {
            val mediaId = item.mediaId
            boundMediaId = mediaId
            // 缓存命中：立刻显示
            val cached = item.uri?.let {
                CoverArtLoader.peek(mediaId, it, CoverArtLoader.EDGE_THUMB)
            }
            if (cached != null) {
                showListCover(cached)
                return
            }
            showListIconPlaceholder(isVideo = item.isVideo, isFolder = false)
            coverJob?.cancel()
            coverJob = adapterScope.launch {
                val bmp = withContext(Dispatchers.IO) {
                    CoverArtLoader.loadBrowseItem(
                        binding.root.context.applicationContext,
                        item,
                        CoverArtLoader.EDGE_THUMB,
                    )
                }
                if (boundMediaId != mediaId) return@launch
                if (bmp != null) {
                    showListCover(bmp)
                }
            }
        }

        private fun showListCover(bmp: Bitmap) {
            binding.iconView.setPadding(0, 0, 0, 0)
            binding.iconView.scaleType = ImageView.ScaleType.FIT_CENTER
            binding.iconView.setImageBitmap(bmp)
        }

        private fun showListIconPlaceholder(isVideo: Boolean, isFolder: Boolean) {
            val pad = (11 * binding.root.resources.displayMetrics.density).toInt()
            binding.iconView.setPadding(pad, pad, pad, pad)
            binding.iconView.scaleType = ImageView.ScaleType.FIT_CENTER
            binding.iconView.setImageResource(
                when {
                    isFolder -> R.drawable.ic_folder
                    isVideo -> R.drawable.ic_video
                    else -> R.drawable.ic_music_note
                },
            )
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

    companion object {
        /** 无艺人名时的占位副标题，列表第二行不展示 */
        private fun isGenericAudioSubtitle(subtitle: String): Boolean {
            return subtitle.equals("音频", ignoreCase = true) ||
                subtitle.equals("Audio", ignoreCase = true)
        }
    }
}
