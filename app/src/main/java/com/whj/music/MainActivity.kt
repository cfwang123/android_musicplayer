package com.whj.music

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ImageView
import android.widget.PopupMenu
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.whj.music.data.AppSettings
import com.whj.music.data.CoverArtLoader
import com.whj.music.data.FavoriteStore
import com.whj.music.data.FolderBrowser
import com.whj.music.data.FolderPlaybackStore
import com.whj.music.data.MediaFileOps
import com.whj.music.data.PlayModeStore
import com.whj.music.data.PlaybackStore
import com.whj.music.data.PlaylistPaths
import com.whj.music.data.PlaylistStore
import com.whj.music.databinding.ActivityMainBinding
import com.whj.music.databinding.IncludePlayerBarBinding
import com.whj.music.databinding.PageBrowseBinding
import com.whj.music.databinding.PageLyricsBinding
import com.whj.music.databinding.PagePlayerBinding
import com.whj.music.databinding.SheetPlaylistBinding
import com.whj.music.lyrics.LyricsRepository
import com.whj.music.model.BrowseItem
import com.whj.music.model.BrowseItemType
import com.whj.music.model.LyricLine
import com.whj.music.model.PlayMode
import com.whj.music.model.PlayableMedia
import com.whj.music.player.MusicPlayerService
import com.whj.music.ui.AppTheme
import com.whj.music.ui.BrowseAdapter
import com.whj.music.ui.LyricsAdapter
import com.whj.music.ui.PlaylistAdapter
import kotlin.math.abs
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var browse: PageBrowseBinding
    private lateinit var player: PagePlayerBinding
    private lateinit var lyrics: PageLyricsBinding
    private lateinit var bar: IncludePlayerBarBinding

    private lateinit var browser: FolderBrowser
    private lateinit var browseAdapter: BrowseAdapter
    private lateinit var lyricsAdapter: LyricsAdapter
    private val lyricsRepo = LyricsRepository()

    private var playerService: MusicPlayerService? = null
    private var bound = false

    private var currentFolder = ""
    private var currentItems: List<BrowseItem> = emptyList()
    private var playableInFolder: List<PlayableMedia> = emptyList()
    private var pendingPlay: PlayableMedia? = null
    /** 文件夹恢复时的起始进度（配合 pendingPlay） */
    private var pendingFolderResumeMs: Int = 0
    private var currentPlayMode: PlayMode = PlayMode.REPEAT_FOLDER
    /** 系统删除确认对话框关联的文件（支持批量） */
    private var pendingDeleteItems: List<BrowseItem> = emptyList()

    /** 1 屏多选 */
    private var selectionMode = false
    private val selectedKeys = linkedSetOf<String>()
    private var itemTouchHelper: ItemTouchHelper? = null
    private var dragOrderDirty = false

    private val deleteConfirmLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        val items = pendingDeleteItems
        pendingDeleteItems = emptyList()
        if (result.resultCode == RESULT_OK && items.isNotEmpty()) {
            MediaFileOps.afterDeleteConfirmedMany(items)
            val playingId = playerService?.currentState()?.item?.id
            if (playingId != null && items.any { it.mediaId == playingId }) {
                playerService?.playNext()
            }
            showToast(
                if (items.size == 1) {
                    getString(R.string.file_delete_ok)
                } else {
                    getString(R.string.file_delete_batch_ok, items.size)
                },
            )
            exitSelectionMode()
            loadFolder(currentFolder)
        } else if (items.isNotEmpty()) {
            showToast(getString(R.string.file_delete_fail))
        }
    }
    private var lastState: MusicPlayerService.PlaybackState? = null
    private var playlistSheet: BottomSheetDialog? = null
    private var playlistAdapter: PlaylistAdapter? = null
    private var lastLyricMediaId: Long? = null
    /** 1 屏上次自动定位的曲目，避免进度刷新重复滚动 */
    private var lastLocatedMediaId: Long? = null
    private var lastLocatedFolder: String = ""
    /** 当前封面对应的 mediaId，避免进度刷新重复加载 */
    private var coverMediaId: Long? = null
    private var coverLoadJob: Job? = null
    private var activeToast: Toast? = null
    private var pendingRestore: PlaybackStore.Snapshot? = null
    private var restoreAttempted = false
    private var appliedThemeKey: String = ""
    /** 歌词页：用户手动滚动浏览中 */
    private var lyricsUserBrowsing = false
    private var lyricsBrowseSeekMs: Int = -1
    private var currentLyricLines: List<LyricLine> = emptyList()
    private val lyricsBrowseResumeRunnable = Runnable { endLyricsBrowsing(resumeFollow = true) }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { _ ->
        if (hasMediaPermission()) {
            restoreAttempted = false
            // 授权后：始终回到上次文件夹并定位文件
            openLastFolderAndLocate()
            if (AppSettings.resumeOnStart(this@MainActivity)) {
                pendingRestore = PlaybackStore.load(this@MainActivity)
                tryConsumeRestore()
            }
        } else {
            showPermissionRequired()
        }
    }

    private val stateListener: (MusicPlayerService.PlaybackState) -> Unit = { state ->
        runOnUiThread { updateAllUi(state) }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as MusicPlayerService.LocalBinder
            playerService = binder.getService()
            bound = true
            playerService?.addListener(stateListener)
            applyUiRefreshMode()
            currentPlayMode = playerService?.getPlayMode() ?: PlayModeStore.load(this@MainActivity)
            updateLoopIcon(currentPlayMode)
            playerService?.currentState()?.let { updateAllUi(it) }
            pendingPlay?.let { item ->
                val seek = pendingFolderResumeMs
                pendingPlay = null
                pendingFolderResumeMs = 0
                playerService?.playItem(
                    item,
                    playableInFolder,
                    currentFolder,
                    startPositionMs = seek,
                    autoPlay = true,
                )
            }
            pendingSleepMinutes?.let { mins ->
                pendingSleepMinutes = null
                playerService?.startSleepTimer(mins)
            }
            tryConsumeRestore()
            if (pendingPlayAfterBind) {
                pendingPlayAfterBind = false
                onPlayPauseClick()
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            playerService = null
            bound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        AppTheme.apply(this)
        appliedThemeKey = AppSettings.themeKey(this)
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        browser = FolderBrowser(this)
        currentPlayMode = PlayModeStore.load(this)

        bar = binding.sharedPlayerBar
        setupViewPager()
        setupBrowsePage()
        setupPlayerPage()
        setupSharedPlayerBar()
        setupLyricsPage()
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    when {
                        selectionMode -> exitSelectionMode()
                        binding.viewPager.currentItem == PAGE_PLAYER ||
                            binding.viewPager.currentItem == PAGE_LYRICS -> {
                            binding.viewPager.setCurrentItem(PAGE_BROWSE, false)
                        }
                        navigateUp() -> Unit
                        else -> {
                            isEnabled = false
                            moveTaskToBack(true)
                            isEnabled = true
                        }
                    }
                }
            },
        )

        // 启动：始终打开上次文件夹并定位上次文件；是否自动播放由设置控制
        val lastSnap = PlaybackStore.load(this)
        if (AppSettings.resumeOnStart(this)) {
            pendingRestore = lastSnap
        }

        if (hasMediaPermission()) {
            openLastFolderAndLocate(lastSnap)
            tryConsumeRestore()
        } else {
            showPermissionRequired()
            requestPermissions()
        }
    }

    override fun onStart() {
        super.onStart()
        // 设置页切换主题后返回时重建
        val key = AppSettings.themeKey(this)
        if (appliedThemeKey.isNotEmpty() && key != appliedThemeKey) {
            recreate()
            return
        }
        bindPlayerService()
        // 从设置返回后刷新根目录主目录配置
        if (hasMediaPermission() && currentFolder.isEmpty()) {
            loadFolder("")
        }
    }

    override fun onStop() {
        // 退出/切后台时保存进度，并降到后台刷新档后解绑监听（服务继续播）
        playerService?.saveCurrentPlayback()
        playerService?.setUiRefreshMode(MusicPlayerService.UiRefreshMode.BACKGROUND)
        if (bound) {
            playerService?.removeListener(stateListener)
            try {
                unbindService(connection)
            } catch (_: Exception) {
            }
            bound = false
            playerService = null
        }
        super.onStop()
    }

    override fun onPause() {
        playerService?.saveCurrentPlayback()
        super.onPause()
    }

    /**
     * 启动时打开上次所在文件夹，并滚动定位到上次文件（与是否自动播放无关）。
     */
    private fun openLastFolderAndLocate(snap: PlaybackStore.Snapshot? = PlaybackStore.load(this)) {
        if (!hasMediaPermission()) return
        if (snap == null) {
            loadFolder("")
            return
        }
        val folder = FolderBrowser.normalizeFolder(snap.media.folderPath)
        loadFolder(folder, scrollToMediaId = snap.media.id)
    }

    /**
     * 仅当设置开启时：恢复上次曲目进度并自动播放。
     * 浏览定位由 [openLastFolderAndLocate] 负责，此处不再依赖开关。
     */
    private fun tryConsumeRestore() {
        if (restoreAttempted) return
        if (!hasMediaPermission()) return
        if (!AppSettings.resumeOnStart(this)) {
            restoreAttempted = true
            pendingRestore = null
            return
        }
        val snap = pendingRestore ?: PlaybackStore.load(this) ?: run {
            restoreAttempted = true
            return
        }
        val service = playerService
        if (service == null) {
            // 先确保服务起来，等 onServiceConnected 再恢复播放
            MusicPlayerService.start(this)
            ensureBound()
            return
        }
        // 若已在播放则不打断，浏览位置已在 openLastFolderAndLocate 处理
        if (service.hasActiveSession() && service.currentState().isPlaying) {
            pendingRestore = null
            restoreAttempted = true
            return
        }
        // 已有会话但未在播（例如仅 prepare 失败）：仍用恢复逻辑重起
        restoreAttempted = true
        pendingRestore = null
        lifecycleScope.launch {
            startPlaybackFromSnapshot(snap, autoPlay = true, relocateBrowse = true)
        }
    }

    /**
     * 底栏 / 歌词页播放键：无会话时从上次进度起播，有会话时 toggle。
     */
    private fun onPlayPauseClick() {
        MusicPlayerService.start(this)
        ensureBound()
        val service = playerService
        if (service == null) {
            // 连上后再试
            pendingPlayAfterBind = true
            return
        }
        if (service.hasActiveSession() && service.hasPreparedOrPreparingPlayer()) {
            service.togglePlayPause()
            return
        }
        if (service.hasActiveSession()) {
            // 有列表无播放器：由服务内部 re-play
            service.togglePlayPause()
            return
        }
        // 无会话：加载上次曲目并播放
        lifecycleScope.launch {
            val snap = PlaybackStore.load(this@MainActivity)
            if (snap == null) {
                showToast(getString(R.string.no_playing_file))
                return@launch
            }
            startPlaybackFromSnapshot(snap, autoPlay = true, relocateBrowse = false)
        }
    }

    private var pendingPlayAfterBind = false

    /**
     * 根据快照构建播放列表并 playItem（支持普通文件夹 / 播放列表 / 收藏）。
     */
    private suspend fun startPlaybackFromSnapshot(
        snap: PlaybackStore.Snapshot,
        autoPlay: Boolean,
        relocateBrowse: Boolean,
    ) {
        try {
            val folder = FolderBrowser.normalizeFolder(snap.media.folderPath)
            val items = resolvePlayableList(folder, snap.media)
            var list = items
            var index = list.indexOfFirst {
                it.id == snap.media.id || it.uri == snap.media.uri
            }
            if (index < 0) {
                list = listOf(snap.media) + list
                index = 0
            }
            playableInFolder = list
            MusicPlayerService.start(this@MainActivity)
            ensureBound()
            val service = playerService
            if (service == null) {
                pendingPlay = list[index]
                pendingFolderResumeMs = snap.positionMs
                return
            }
            service.playItem(
                list[index],
                list,
                folder.ifBlank { snap.media.folderPath },
                startPositionMs = snap.positionMs,
                autoPlay = autoPlay,
            )
            if (relocateBrowse) {
                // 已在目标目录则只滚动，避免重复 load 触发其它恢复逻辑
                if (FolderBrowser.normalizeFolder(currentFolder) == folder) {
                    scrollBrowseToMedia(list[index].id)
                } else {
                    loadFolder(folder, scrollToMediaId = list[index].id)
                }
            }
        } catch (e: Exception) {
            android.util.Log.w("MainActivity", "startPlaybackFromSnapshot failed", e)
        }
    }

    /** 按文件夹类型解析可播列表 */
    private suspend fun resolvePlayableList(
        folder: String,
        fallback: PlayableMedia,
    ): List<PlayableMedia> {
        return when {
            PlaylistPaths.isFavorites(folder) -> {
                browser.resolveMediaIds(
                    FavoriteStore.mediaIdsOrdered(this),
                    PlaylistPaths.favoritesPath(),
                ).mapNotNull { it.toPlayable() }
            }
            PlaylistPaths.isUserPlaylist(folder) -> {
                val id = PlaylistPaths.playlistIdOf(folder) ?: return listOf(fallback)
                PlaylistStore.get(this, id)?.tracks?.mapNotNull { it.toPlayable() }
                    .orEmpty()
            }
            folder.isNotEmpty() && !PlaylistPaths.isInPlaylistSpace(folder) -> {
                browser.listPlayableInFolder(folder)
            }
            else -> emptyList()
        }
    }

    private fun setupViewPager() {
        val inflater = LayoutInflater.from(this)
        browse = PageBrowseBinding.inflate(inflater)
        player = PagePlayerBinding.inflate(inflater)
        lyrics = PageLyricsBinding.inflate(inflater)

        val pages = listOf(browse.root, player.root, lyrics.root)
        binding.viewPager.adapter = object : RecyclerView.Adapter<PageHolder>() {
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageHolder {
                val page = pages[viewType]
                (page.parent as? ViewGroup)?.removeView(page)
                page.layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
                return PageHolder(page)
            }

            override fun onBindViewHolder(holder: PageHolder, position: Int) = Unit
            override fun getItemCount(): Int = 3
            override fun getItemViewType(position: Int): Int = position
        }
        binding.viewPager.offscreenPageLimit = 2
        binding.viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                // 歌词页自带底部进度，隐藏全局控制条
                bar.playerBarRoot.visibility =
                    if (position == PAGE_LYRICS) View.GONE else View.VISIBLE
                // 进入文件夹页时自动定位到当前播放目录与文件
                if (position == PAGE_BROWSE) {
                    autoLocatePlayingFile()
                }
                // 按页面调节服务进度刷新频率（省电）
                applyUiRefreshMode()
            }
        })
    }

    /** 歌词页 24fps，其它页约 4fps，后台约 1fps */
    private fun applyUiRefreshMode() {
        val svc = playerService ?: return
        val mode = if (binding.viewPager.currentItem == PAGE_LYRICS) {
            MusicPlayerService.UiRefreshMode.LYRICS
        } else {
            MusicPlayerService.UiRefreshMode.FOREGROUND
        }
        svc.setUiRefreshMode(mode)
    }

    private class PageHolder(view: View) : RecyclerView.ViewHolder(view)

    private fun setupBrowsePage() {
        browseAdapter = BrowseAdapter(
            onClick = { onBrowseItemClick(it) },
            onFavoriteClick = { onFavoriteToggle(it) },
            onItemLongClick = { enterSelectionMode(it) },
            onStartDrag = { holder -> itemTouchHelper?.startDrag(holder) },
        )
        browse.songList.layoutManager = LinearLayoutManager(this)
        browse.songList.adapter = browseAdapter
        // 切换文件夹时不要列表动画，直接刷新
        browse.songList.itemAnimator = null
        setupItemTouchHelper()
        refreshFavoritesUi()

        browse.backBtn.setOnClickListener {
            if (selectionMode) exitSelectionMode() else navigateUp()
        }
        browse.menuBtn.setOnClickListener { showOverflowMenu(it) }
        browse.permissionBtn.setOnClickListener { requestPermissions() }
        browse.sleepTimerChip.setOnClickListener { confirmCancelSleepTimer() }
        browse.browseSpeedBtn.setOnClickListener { onPlayerSpeedBtnClick(it) }
        browse.selectionCancelBtn.setOnClickListener { exitSelectionMode() }
        browse.selectionSelectAllBtn.setOnClickListener { selectAllSelectable() }
        browse.selectionAddPlaylistBtn.setOnClickListener {
            val files = selectedFileItems()
            if (files.isEmpty()) {
                showToast(getString(R.string.selection_empty))
                return@setOnClickListener
            }
            showAddToPlaylistMenu(browse.selectionAddPlaylistBtn, files)
        }
        browse.selectionRemovePlaylistBtn.setOnClickListener { removeSelectedFromPlaylist() }
        browse.selectionDeleteBtn.setOnClickListener { onSelectionDeleteClick() }
        updateSpeedButton(
            playerService?.getPlaybackSpeed()
                ?: AppSettings.defaultPlaybackSpeed(this),
        )
        updateSelectionUi()
    }

    private fun setupItemTouchHelper() {
        val callback = object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN,
            0,
        ) {
            override fun isLongPressDragEnabled(): Boolean = false

            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder,
            ): Boolean {
                if (!selectionMode || !isReorderableList()) return false
                val from = viewHolder.bindingAdapterPosition
                val to = target.bindingAdapterPosition
                if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION) return false
                browseAdapter.moveItem(from, to)
                if (from in currentItems.indices && to in currentItems.indices) {
                    val mutable = currentItems.toMutableList()
                    val item = mutable.removeAt(from)
                    mutable.add(to, item)
                    currentItems = mutable
                    playableInFolder = currentItems.mapNotNull { it.toPlayable() }
                }
                dragOrderDirty = true
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) = Unit

            override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(recyclerView, viewHolder)
                if (dragOrderDirty) {
                    persistPlaylistOrder()
                    dragOrderDirty = false
                }
            }
        }
        itemTouchHelper = ItemTouchHelper(callback).also { it.attachToRecyclerView(browse.songList) }
    }

    private fun onFavoriteToggle(item: BrowseItem) {
        val favorited = when (item.type) {
            BrowseItemType.FILE -> FavoriteStore.toggleMedia(this, item.mediaId)
            BrowseItemType.FOLDER -> FavoriteStore.toggleFolder(this, item.folderPath)
        }
        refreshFavoritesUi()
        showToast(
            getString(if (favorited) R.string.favorite_added else R.string.favorite_removed),
        )
        // 在「收藏歌曲」列表内取消收藏后刷新列表
        if (!favorited && item.type == BrowseItemType.FILE && PlaylistPaths.isFavorites(currentFolder)) {
            loadFolder(currentFolder)
        }
    }

    private fun onCurrentTrackFavoriteToggle() {
        val media = playerService?.currentState()?.item ?: lastState?.item
        if (media == null) {
            showToast(getString(R.string.no_playing_file))
            return
        }
        val favorited = FavoriteStore.toggleMedia(this, media.id)
        refreshFavoritesUi()
        showToast(
            getString(if (favorited) R.string.favorite_added else R.string.favorite_removed),
        )
        if (!favorited && PlaylistPaths.isFavorites(currentFolder)) {
            loadFolder(currentFolder)
        }
    }

    private fun refreshFavoritesUi() {
        if (::browseAdapter.isInitialized) {
            browseAdapter.setFavorites(FavoriteStore.allKeys(this))
        }
        updateFavoriteButtons(playerService?.currentState()?.item?.id ?: lastState?.item?.id)
    }

    private fun updateFavoriteButtons(mediaId: Long?) {
        val favorited = mediaId != null && FavoriteStore.isFavoriteMedia(this, mediaId)
        val icon = if (favorited) R.drawable.ic_favorite else R.drawable.ic_favorite_border
        val hasTrack = mediaId != null
        if (::player.isInitialized) {
            player.playerFavoriteBtn.setImageResource(icon)
            player.playerFavoriteBtn.isEnabled = hasTrack
            player.playerFavoriteBtn.alpha = if (hasTrack) 1f else 0.35f
            player.playerAddPlaylistBtn.isEnabled = hasTrack
            player.playerAddPlaylistBtn.alpha = if (hasTrack) 1f else 0.35f
        }
        if (::lyrics.isInitialized) {
            lyrics.lyricsFavoriteBtn.setImageResource(icon)
            lyrics.lyricsFavoriteBtn.isEnabled = hasTrack
            lyrics.lyricsFavoriteBtn.alpha = if (hasTrack) 1f else 0.35f
        }
    }

    private fun setupPlayerPage() {
        player.playerCollapseBtn.setOnClickListener {
            binding.viewPager.setCurrentItem(PAGE_BROWSE, false)
        }
        player.playerSleepBtn.setOnClickListener { onPlayerSleepBtnClick(it) }
        player.playerSleepChip.setOnClickListener { confirmCancelSleepTimer() }
        player.playerSpeedBtn.setOnClickListener { onPlayerSpeedBtnClick(it) }
        player.playerFavoriteBtn.setOnClickListener { onCurrentTrackFavoriteToggle() }
        player.playerAddPlaylistBtn.setOnClickListener { onPlayerAddToPlaylistClick(it) }
        // 圆角裁剪封面
        player.coverFrame.clipToOutline = true
        player.coverFrame.outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
    }

    private fun onPlayerAddToPlaylistClick(anchor: View) {
        val media = playerService?.currentState()?.item ?: lastState?.item
        if (media == null) {
            showToast(getString(R.string.no_playing_file))
            return
        }
        val item = BrowseItem(
            type = BrowseItemType.FILE,
            name = media.title,
            folderPath = media.folderPath,
            uri = media.uri,
            mediaId = media.id,
            durationMs = media.durationMs,
            isVideo = media.isVideo,
            subtitle = media.subtitle,
            filePath = media.filePath,
        )
        showAddToPlaylistMenu(anchor, listOf(item))
    }

    private fun setupSharedPlayerBar() {
        bar.barPlayPauseBtn.setOnClickListener {
            onPlayPauseClick()
        }
        bar.barPrevBtn.setOnClickListener {
            ensureBound()
            playerService?.playPrevious()
        }
        bar.barNextBtn.setOnClickListener {
            ensureBound()
            playerService?.playNext()
        }
        bar.barLoopBtn.setOnClickListener { cyclePlayMode() }
        bar.barEqBtn.setOnClickListener { showEqualizerDialog() }
        bar.barPlaylistBtn.setOnClickListener { showPlaylistSheet() }

        bar.barSeekBack60Btn.setOnClickListener { onSeek60OrLyric(prev = true) }
        bar.barSeekBack5Btn.setOnClickListener { seekRelative(-5_000) }
        bar.barSeekForward5Btn.setOnClickListener { seekRelative(5_000) }
        bar.barSeekForward60Btn.setOnClickListener { onSeek60OrLyric(prev = false) }

        bar.barSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                val duration = playerService?.currentState()?.durationMs ?: return
                if (duration <= 0) return
                val position = (progress / 1000f * duration).toInt()
                bar.barPositionText.text = PlayableMedia.formatTime(position.toLong())
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                playerService?.setUserSeeking(true)
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                val progress = seekBar?.progress ?: 0
                val duration = playerService?.currentState()?.durationMs ?: 0
                if (duration > 0) {
                    playerService?.seekTo((progress / 1000f * duration).toInt())
                }
                playerService?.setUserSeeking(false)
            }
        })
    }

    private fun setupLyricsPage() {
        lyricsAdapter = LyricsAdapter()
        lyrics.lyricsList.layoutManager = LinearLayoutManager(this)
        lyrics.lyricsList.adapter = lyricsAdapter
        lyrics.lyricsList.itemAnimator = null
        lyrics.lyricsFavoriteBtn.setOnClickListener { onCurrentTrackFavoriteToggle() }
        lyrics.lyricsSeekChip.visibility = View.GONE
        lyrics.lyricsSeekChip.setOnClickListener {
            if (lyricsBrowseSeekMs < 0) return@setOnClickListener
            ensureBound()
            playerService?.seekTo(lyricsBrowseSeekMs)
            endLyricsBrowsing(resumeFollow = true)
        }
        lyrics.lyricsList.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                when (newState) {
                    RecyclerView.SCROLL_STATE_DRAGGING -> {
                        // 仅用户手势进入浏览模式（程序 scrollTo 也会触发 IDLE/SETTLING）
                        enterLyricsBrowsing()
                    }
                    RecyclerView.SCROLL_STATE_IDLE -> {
                        if (lyricsUserBrowsing) {
                            updateLyricsSeekChipFromCenter()
                            scheduleLyricsBrowseResume()
                        }
                    }
                }
            }

            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (lyricsUserBrowsing && recyclerView.scrollState != RecyclerView.SCROLL_STATE_IDLE) {
                    updateLyricsSeekChipFromCenter()
                }
            }
        })
        lyrics.lyricsPlayPauseBtn.setOnClickListener {
            onPlayPauseClick()
        }
        lyrics.lyricsLoopBtn.setOnClickListener { cyclePlayMode() }
        lyrics.lyricsPlaylistBtn.setOnClickListener { showPlaylistSheet() }
        lyrics.lyricsPrevSentenceBtn.setOnClickListener { seekLyricSentence(prev = true) }
        lyrics.lyricsNextSentenceBtn.setOnClickListener { seekLyricSentence(prev = false) }
        lyrics.lyricsSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                val duration = playerService?.currentState()?.durationMs ?: return
                if (duration <= 0) return
                val position = (progress / 1000f * duration).toInt()
                lyrics.lyricsPositionText.text = PlayableMedia.formatTime(position.toLong())
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                playerService?.setUserSeeking(true)
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                val progress = seekBar?.progress ?: 0
                val duration = playerService?.currentState()?.durationMs ?: 0
                if (duration > 0) {
                    playerService?.seekTo((progress / 1000f * duration).toInt())
                }
                playerService?.setUserSeeking(false)
            }
        })
    }

    private fun enterLyricsBrowsing() {
        if (currentLyricLines.isEmpty()) return
        lyricsUserBrowsing = true
        updateLyricsSeekChipFromCenter()
        scheduleLyricsBrowseResume()
    }

    private fun scheduleLyricsBrowseResume() {
        lyrics.lyricsList.removeCallbacks(lyricsBrowseResumeRunnable)
        lyrics.lyricsList.postDelayed(lyricsBrowseResumeRunnable, LYRICS_BROWSE_IDLE_MS)
    }

    private fun endLyricsBrowsing(resumeFollow: Boolean) {
        lyrics.lyricsList.removeCallbacks(lyricsBrowseResumeRunnable)
        lyricsUserBrowsing = false
        lyricsBrowseSeekMs = -1
        lyrics.lyricsSeekChip.visibility = View.GONE
        if (resumeFollow) {
            scrollLyricsToCurrentLine()
        }
    }

    private fun updateLyricsSeekChipFromCenter() {
        if (currentLyricLines.isEmpty()) {
            lyrics.lyricsSeekChip.visibility = View.GONE
            return
        }
        val list = lyrics.lyricsList
        if (list.height <= 0 || list.childCount == 0) {
            lyrics.lyricsSeekChip.visibility = View.GONE
            return
        }
        // 与自动跟唱滚动手感一致：焦点在列表高度约 1/3 处
        val focusY = list.height / 3f
        var bestPos = RecyclerView.NO_POSITION
        var bestDist = Float.MAX_VALUE
        for (i in 0 until list.childCount) {
            val child = list.getChildAt(i) ?: continue
            val pos = list.getChildAdapterPosition(child)
            if (pos == RecyclerView.NO_POSITION) continue
            val mid = (child.top + child.bottom) / 2f
            val dist = abs(mid - focusY)
            if (dist < bestDist) {
                bestDist = dist
                bestPos = pos
            }
        }
        if (bestPos == RecyclerView.NO_POSITION) {
            val lm = list.layoutManager as? LinearLayoutManager
            bestPos = lm?.findFirstVisibleItemPosition() ?: RecyclerView.NO_POSITION
        }
        if (bestPos !in currentLyricLines.indices) {
            lyrics.lyricsSeekChip.visibility = View.GONE
            return
        }
        val line = currentLyricLines[bestPos]
        lyricsBrowseSeekMs = (line.timeSec * 1000.0).toInt().coerceAtLeast(0)
        lyrics.lyricsSeekChip.text = formatLyricChipTime(lyricsBrowseSeekMs.toLong())
        lyrics.lyricsSeekChip.visibility = View.VISIBLE
        // 芯片垂直对齐到焦点句附近
        val child = list.findViewHolderForAdapterPosition(bestPos)?.itemView
        if (child != null) {
            val chipH = if (lyrics.lyricsSeekChip.height > 0) {
                lyrics.lyricsSeekChip.height
            } else {
                (28 * resources.displayMetrics.density).toInt()
            }
            val targetCenterY = (child.top + child.bottom) / 2f
            val parentH = (lyrics.lyricsSeekChip.parent as View).height.toFloat()
            val ty = (targetCenterY - parentH / 2f).coerceIn(
                -parentH / 2f + chipH,
                parentH / 2f - chipH,
            )
            lyrics.lyricsSeekChip.translationY = ty
        }
    }

    private fun scrollLyricsToCurrentLine() {
        val center = lyricsAdapter.currentCenterIndex()
        if (center < 0) return
        val lm = lyrics.lyricsList.layoutManager as? LinearLayoutManager ?: return
        val offset = (lyrics.lyricsList.height / 3).coerceAtLeast(0)
        lm.scrollToPositionWithOffset(center, offset)
    }

    private fun formatLyricChipTime(ms: Long): String {
        val total = (ms / 1000).coerceAtLeast(0)
        val minutes = total / 60
        val seconds = total % 60
        return "%02d:%02d".format(minutes, seconds)
    }

    private fun showOverflowMenu(anchor: View) {
        val mode = playerService?.getPlayMode() ?: currentPlayMode
        val popup = PopupMenu(this, anchor)
        var order = 0
        popup.menu.add(0, MENU_REFRESH, order++, R.string.refresh)
        if (PlaylistPaths.isPlaylistsRoot(currentFolder) || currentFolder.isEmpty()) {
            popup.menu.add(0, MENU_NEW_PLAYLIST, order++, R.string.menu_new_playlist)
        }
        popup.menu.add(0, MENU_PLAY_MODE, order++, getString(R.string.menu_play_mode, modeLabel(mode)))
        popup.menu.add(0, MENU_GOTO_FOLDER, order++, R.string.menu_goto_playing_file)
        val sleep = popup.menu.addSubMenu(0, MENU_SLEEP, order++, R.string.menu_sleep_timer)
        fillSleepMenu(sleep)
        popup.menu.add(0, MENU_SETTINGS, order++, R.string.menu_settings)
        popup.menu.add(0, MENU_STOP, order, R.string.menu_stop)
        popup.setOnMenuItemClickListener { item ->
            when {
                item.itemId == MENU_REFRESH -> {
                    if (hasMediaPermission()) loadFolder(currentFolder) else requestPermissions()
                    true
                }
                item.itemId == MENU_NEW_PLAYLIST -> {
                    showCreatePlaylistDialog { loadFolder(currentFolder) }
                    true
                }
                item.itemId == MENU_PLAY_MODE -> {
                    cyclePlayMode()
                    true
                }
                item.itemId == MENU_GOTO_FOLDER -> {
                    gotoPlayingFile()
                    true
                }
                item.itemId == MENU_SETTINGS -> {
                    startActivity(Intent(this, SettingsActivity::class.java))
                    true
                }
                item.itemId == MENU_STOP -> {
                    ensureBound()
                    playerService?.stopPlayback()
                    showToast(getString(R.string.stopped))
                    true
                }
                item.itemId in MENU_SLEEP_MIN..MENU_SLEEP_MAX -> {
                    setSleep(item.itemId - MENU_SLEEP_MIN)
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    /** 定时关闭：5~120 分钟，每 5 分钟一档。itemId = MENU_SLEEP_MIN + 分钟数 */
    private fun fillSleepMenu(menu: android.view.Menu) {
        var order = 0
        for (minutes in 5..120 step 5) {
            menu.add(
                0,
                MENU_SLEEP_MIN + minutes,
                order++,
                getString(R.string.menu_sleep_minutes, minutes),
            )
        }
    }

    private var pendingSleepMinutes: Int? = null

    private fun setSleep(minutes: Int) {
        MusicPlayerService.start(this)
        ensureBound()
        val service = playerService
        if (service != null) {
            service.startSleepTimer(minutes)
            showToast(getString(R.string.sleep_timer_set, minutes))
        } else {
            pendingSleepMinutes = minutes
            showToast(getString(R.string.sleep_timer_set, minutes))
        }
    }

    private fun confirmCancelSleepTimer() {
        val remaining = playerService?.sleepRemainingMs() ?: 0L
        if (remaining <= 0L) return
        AlertDialog.Builder(this)
            .setTitle(R.string.sleep_cancel_title)
            .setMessage(R.string.sleep_cancel_msg)
            .setPositiveButton(R.string.confirm) { _, _ ->
                playerService?.cancelSleepTimer()
                showToast(getString(R.string.sleep_cancelled))
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun cyclePlayMode() {
        ensureBound()
        val mode = playerService?.cyclePlayMode() ?: currentPlayMode.next().also {
            currentPlayMode = it
            PlayModeStore.save(this, it)
        }
        currentPlayMode = mode
        updateLoopIcon(mode)
        showToast(getString(R.string.mode_switched, modeLabel(mode)))
    }

    /** 立即取消上一条 Toast 再显示新内容 */
    private fun showToast(message: CharSequence) {
        activeToast?.cancel()
        activeToast = Toast.makeText(this, message, Toast.LENGTH_SHORT).also { it.show() }
    }

    private fun updateLoopIcon(mode: PlayMode) {
        val res = when (mode) {
            PlayMode.REPEAT_ONE -> R.drawable.ic_repeat_one
            PlayMode.REPEAT_FOLDER -> R.drawable.ic_repeat_folder
            PlayMode.NEXT_FOLDER -> R.drawable.ic_next_folder
            PlayMode.SHUFFLE -> R.drawable.ic_shuffle
        }
        bar.barLoopBtn.setImageResource(res)
        bar.barLoopBtn.contentDescription = modeLabel(mode)
        if (::lyrics.isInitialized) {
            lyrics.lyricsLoopBtn.setImageResource(res)
            lyrics.lyricsLoopBtn.contentDescription = modeLabel(mode)
        }
        // 随机模式下「下一曲」换图标，便于识别
        val nextIcon = if (mode == PlayMode.SHUFFLE) {
            R.drawable.ic_shuffle
        } else {
            R.drawable.ic_skip_next
        }
        bar.barNextBtn.setImageResource(nextIcon)
        bar.barNextBtn.contentDescription = if (mode == PlayMode.SHUFFLE) {
            getString(R.string.next_shuffle)
        } else {
            getString(R.string.next)
        }
    }

    private fun gotoPlayingFile() {
        val state = playerService?.currentState() ?: lastState
        val folder = state?.folderPath.orEmpty()
        val mediaId = state?.item?.id
        if (folder.isEmpty() || mediaId == null) {
            showToast(getString(R.string.no_playing_file))
            return
        }
        binding.viewPager.setCurrentItem(PAGE_BROWSE, false)
        loadFolder(folder, scrollToMediaId = mediaId)
    }

    /** 进入 1 屏时静默定位当前曲目（无播放则不提示） */
    private fun autoLocatePlayingFile() {
        if (!AppSettings.autoLocateOnBrowse(this)) return
        val state = playerService?.currentState() ?: lastState
        locatePlayingFile(state, force = true)
    }

    /**
     * 切歌时：若当前在 1 屏，自动打开对应文件夹并滚动到文件。
     * 受设置「进入文件夹页时定位当前曲」开关控制；多选中不打断。
     */
    private fun maybeAutoLocateOnTrackChange(state: MusicPlayerService.PlaybackState) {
        if (!AppSettings.autoLocateOnBrowse(this)) return
        if (selectionMode) return
        if (!::binding.isInitialized) return
        if (binding.viewPager.currentItem != PAGE_BROWSE) return
        locatePlayingFile(state, force = false)
    }

    /**
     * @param force true 时即使与上次定位相同也再滚一次（用于进入 1 屏）
     */
    private fun locatePlayingFile(state: MusicPlayerService.PlaybackState?, force: Boolean) {
        val mediaId = state?.item?.id ?: return
        val folder = FolderBrowser.normalizeFolder(state.folderPath)
        if (folder.isEmpty()) return
        if (!force && mediaId == lastLocatedMediaId && folder == lastLocatedFolder) return
        lastLocatedMediaId = mediaId
        lastLocatedFolder = folder
        if (folder == currentFolder) {
            scrollBrowseToMedia(mediaId)
        } else {
            loadFolder(folder, scrollToMediaId = mediaId)
        }
    }

    private fun onPlayerSleepBtnClick(anchor: View) {
        val remaining = playerService?.sleepRemainingMs() ?: 0L
        if (remaining > 0L) {
            confirmCancelSleepTimer()
            return
        }
        val popup = PopupMenu(this, anchor)
        fillSleepMenu(popup.menu)
        popup.setOnMenuItemClickListener { item ->
            if (item.itemId in MENU_SLEEP_MIN..MENU_SLEEP_MAX) {
                setSleep(item.itemId - MENU_SLEEP_MIN)
                true
            } else {
                false
            }
        }
        popup.show()
    }

    private fun onPlayerSpeedBtnClick(anchor: View) {
        val popup = PopupMenu(this, anchor)
        SPEED_OPTIONS.forEachIndexed { index, speed ->
            val label = formatSpeedLabel(speed)
            popup.menu.add(0, MENU_SPEED_BASE + index, index, getString(R.string.playback_speed_label, label))
        }
        popup.setOnMenuItemClickListener { item ->
            val idx = item.itemId - MENU_SPEED_BASE
            if (idx in SPEED_OPTIONS.indices) {
                setPlaybackSpeed(SPEED_OPTIONS[idx])
                true
            } else {
                false
            }
        }
        popup.show()
    }

    private fun setPlaybackSpeed(speed: Float) {
        ensureBound()
        MusicPlayerService.start(this)
        playerService?.setPlaybackSpeed(speed)
        updateSpeedButton(speed)
        showToast(getString(R.string.playback_speed_set, formatSpeedLabel(speed)))
    }

    private fun updateSpeedButton(speed: Float) {
        val label = getString(R.string.playback_speed_label, formatSpeedLabel(speed))
        if (::player.isInitialized) {
            player.playerSpeedBtn.text = label
        }
        if (::browse.isInitialized) {
            browse.browseSpeedBtn.text = label
        }
    }

    private fun formatSpeedLabel(speed: Float): String {
        return if (speed == speed.toLong().toFloat()) {
            speed.toLong().toString()
        } else {
            // 0.5 / 0.75 / 1.25 / 1.5
            String.format(java.util.Locale.US, "%.2f", speed).trimEnd('0').trimEnd('.')
        }
    }

    private fun showEqualizerDialog() {
        ensureBound()
        MusicPlayerService.start(this)
        val service = playerService
        val dialog = BottomSheetDialog(this)
        val root = layoutInflater.inflate(R.layout.dialog_equalizer, null)
        dialog.setContentView(root)

        val enableSwitch = root.findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.eqEnableSwitch)
        val presetSpinner = root.findViewById<android.widget.Spinner>(R.id.eqPresetSpinner)
        val bandsContainer = root.findViewById<android.widget.LinearLayout>(R.id.eqBandsContainer)
        val unavailable = root.findViewById<android.widget.TextView>(R.id.eqUnavailableText)
        val closeBtn = root.findViewById<com.google.android.material.button.MaterialButton>(R.id.eqCloseBtn)

        fun refreshUi() {
            val snap = service?.equalizerSnapshot()
            if (snap == null || !snap.available) {
                unavailable.visibility = View.VISIBLE
                enableSwitch.isEnabled = false
                presetSpinner.isEnabled = false
                bandsContainer.removeAllViews()
                enableSwitch.isChecked = AppSettings.eqEnabled(this)
                return
            }
            unavailable.visibility = View.GONE
            enableSwitch.isEnabled = true
            presetSpinner.isEnabled = true
            enableSwitch.isChecked = snap.enabled

            val presetLabels = snap.presetNames.toMutableList().also {
                it.add(getString(R.string.equalizer_custom))
            }
            presetSpinner.adapter = ArrayAdapter(
                this,
                android.R.layout.simple_spinner_dropdown_item,
                presetLabels,
            )
            val customPos = presetLabels.lastIndex
            val sel = if (snap.presetIndex in snap.presetNames.indices) {
                snap.presetIndex
            } else {
                customPos
            }
            presetSpinner.setSelection(sel)

            bandsContainer.removeAllViews()
            val density = resources.displayMetrics.density
            snap.bands.forEach { band ->
                val row = android.widget.LinearLayout(this).apply {
                    orientation = android.widget.LinearLayout.VERTICAL
                    setPadding(0, (8 * density).toInt(), 0, (4 * density).toInt())
                }
                val label = android.widget.TextView(this).apply {
                    val hz = if (band.centerHz >= 1000) {
                        String.format(java.util.Locale.US, "%.1fk", band.centerHz / 1000f)
                    } else {
                        band.centerHz.toString()
                    }
                    text = getString(R.string.equalizer_band_label, hz)
                    setTextColor(AppTheme.resolveColor(this@MainActivity, R.attr.colorTextSecondary))
                    textSize = 12f
                }
                val seek = android.widget.SeekBar(this).apply {
                    max = (band.maxLevel - band.minLevel).toInt().coerceAtLeast(1)
                    progress = (band.levelMilliBel - band.minLevel).toInt().coerceIn(0, max)
                    isEnabled = snap.enabled
                    setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                        override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                            if (!fromUser) return
                            val level = (band.minLevel + progress).toShort()
                            service?.setEqualizerBandLevel(band.index, level)
                        }

                        override fun onStartTrackingTouch(sb: SeekBar?) = Unit
                        override fun onStopTrackingTouch(sb: SeekBar?) = Unit
                    })
                }
                row.addView(label)
                row.addView(seek)
                bandsContainer.addView(row)
            }
        }

        enableSwitch.setOnCheckedChangeListener { _, checked ->
            service?.setEqualizerEnabled(checked)
            showToast(
                getString(if (checked) R.string.equalizer_enabled_toast else R.string.equalizer_disabled_toast),
            )
            refreshUi()
        }
        presetSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: android.widget.AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long,
            ) {
                val snap = service?.equalizerSnapshot() ?: return
                if (!snap.available) return
                if (position in snap.presetNames.indices) {
                    service.setEqualizerPreset(position)
                    refreshUi()
                }
                // 自定义：不切预设，由滑条调整
            }

            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
        }
        closeBtn.setOnClickListener { dialog.dismiss() }
        refreshUi()
        dialog.show()
    }

    private fun showPlaylistSheet() {
        ensureBound()
        val service = playerService ?: return
        val dialog = BottomSheetDialog(this)
        val sheet = SheetPlaylistBinding.inflate(layoutInflater)
        dialog.setContentView(sheet.root)

        val adapter = PlaylistAdapter { item, _ ->
            val list = service.getPlaylist()
            service.playItem(item, list, service.currentState().folderPath)
            dialog.dismiss()
        }
        playlistAdapter = adapter
        sheet.playlistList.layoutManager = LinearLayoutManager(this)
        sheet.playlistList.adapter = adapter
        val list = service.getPlaylist()
        val idx = service.getCurrentIndex()
        adapter.submit(list, idx)
        sheet.playlistModeText.text = getString(
            R.string.playlist_count,
            list.size,
        ) + " · " + modeLabel(service.getPlayMode())
        sheet.playlistCloseBtn.setOnClickListener { dialog.dismiss() }
        if (idx >= 0) sheet.playlistList.scrollToPosition(idx)
        playlistSheet = dialog
        dialog.show()
    }

    private fun seekRelative(deltaMs: Int) {
        ensureBound()
        playerService?.seekBy(deltaMs)
    }

    /** 有歌词且总时长 < 10 分钟时，±60s 变为上一句/下一句 */
    private fun useLyricSentenceSeek(state: MusicPlayerService.PlaybackState?): Boolean {
        if (state == null) return false
        val media = state.item ?: return false
        val duration = state.durationMs.takeIf { it > 0 } ?: media.durationMs.toInt()
        val maxMs = AppSettings.lyricSentenceSeekMaxMinutes(this) * 60 * 1000
        if (duration <= 0 || duration >= maxMs) return false
        return lyricsRepo.hasTimedLyrics(media)
    }

    private fun updateSeek60Buttons(state: MusicPlayerService.PlaybackState?) {
        if (useLyricSentenceSeek(state)) {
            bar.barSeekBack60Btn.text = getString(R.string.seek_lyric_prev)
            bar.barSeekForward60Btn.text = getString(R.string.seek_lyric_next)
        } else {
            bar.barSeekBack60Btn.text = getString(R.string.seek_back_60)
            bar.barSeekForward60Btn.text = getString(R.string.seek_forward_60)
        }
    }

    private fun onSeek60OrLyric(prev: Boolean) {
        ensureBound()
        val state = playerService?.currentState()
        if (useLyricSentenceSeek(state) && state != null) {
            seekLyricSentence(prev)
        } else {
            seekRelative(if (prev) -60_000 else 60_000)
        }
    }

    /**
     * 3 屏专用：上一句 / 下一句（有时间轴歌词即可，不受曲长限制）。
     */
    private fun seekLyricSentence(prev: Boolean) {
        ensureBound()
        val state = playerService?.currentState() ?: lastState
        val media = state?.item
        if (media == null) {
            showToast(getString(R.string.no_playing_file))
            return
        }
        if (!lyricsRepo.hasTimedLyrics(media)) {
            showToast(getString(R.string.lyrics_empty))
            return
        }
        val pos = state.positionMs
        val target = if (prev) {
            lyricsRepo.prevSentencePositionMs(media, pos)
        } else {
            lyricsRepo.nextSentencePositionMs(media, pos)
        }
        if (target != null) {
            playerService?.seekTo(target)
            // 句跳转后退出手动浏览，回到跟唱
            endLyricsBrowsing(resumeFollow = true)
        }
    }

    private fun onBrowseItemClick(item: BrowseItem) {
        if (selectionMode) {
            if (isItemSelectable(item)) {
                toggleSelection(item)
            }
            return
        }
        when (item.type) {
            BrowseItemType.FOLDER -> loadFolder(item.folderPath)
            BrowseItemType.FILE -> {
                val playable = item.toPlayable() ?: return
                playableInFolder = currentItems.mapNotNull { it.toPlayable() }
                if (playableInFolder.isEmpty()) playableInFolder = listOf(playable)
                playMedia(playable)
                // 1 屏选歌后停留在列表，不跳转到播放页
            }
        }
    }

    // region 多选与播放列表

    /** 用户自建列表或「收藏歌曲」：可多选移出、拖动排序 */
    private fun isReorderableList(): Boolean =
        PlaylistPaths.isUserPlaylist(currentFolder) || PlaylistPaths.isFavorites(currentFolder)

    private fun isPlaylistsRoot(): Boolean = PlaylistPaths.isPlaylistsRoot(currentFolder)

    private fun isItemSelectable(item: BrowseItem): Boolean {
        return when (item.type) {
            BrowseItemType.FILE -> true
            // 播放列表总览仅可选中用户列表（不可删「收藏歌曲」入口）
            BrowseItemType.FOLDER -> isPlaylistsRoot() &&
                PlaylistPaths.isUserPlaylist(item.folderPath)
        }
    }

    private fun enterSelectionMode(item: BrowseItem) {
        if (!isItemSelectable(item)) return
        if (!selectionMode) {
            selectionMode = true
            selectedKeys.clear()
        }
        selectedKeys.add(browseAdapter.selectionKeyOf(item))
        updateSelectionUi()
    }

    private fun exitSelectionMode() {
        if (dragOrderDirty) {
            persistPlaylistOrder()
            dragOrderDirty = false
        }
        selectionMode = false
        selectedKeys.clear()
        updateSelectionUi()
    }

    private fun toggleSelection(item: BrowseItem) {
        if (!isItemSelectable(item)) return
        val key = browseAdapter.selectionKeyOf(item)
        if (selectedKeys.contains(key)) selectedKeys.remove(key) else selectedKeys.add(key)
        if (selectedKeys.isEmpty()) {
            exitSelectionMode()
        } else {
            updateSelectionUi()
        }
    }

    private fun selectAllSelectable() {
        selectedKeys.clear()
        currentItems.filter { isItemSelectable(it) }.forEach {
            selectedKeys.add(browseAdapter.selectionKeyOf(it))
        }
        if (selectedKeys.isEmpty()) {
            showToast(getString(R.string.selection_empty))
            return
        }
        selectionMode = true
        updateSelectionUi()
    }

    private fun updateSelectionUi() {
        if (!::browse.isInitialized || !::browseAdapter.isInitialized) return
        val reorderable = isReorderableList()
        val inFavorites = PlaylistPaths.isFavorites(currentFolder)
        val playlistsRoot = isPlaylistsRoot()
        browse.selectionBar.visibility = if (selectionMode) View.VISIBLE else View.GONE
        browse.selectionCountText.text = getString(R.string.selection_count, selectedKeys.size)
        // 列表/收藏：移出 + 拖动；总览：删列表；普通目录：加入列表 + 删文件
        browse.selectionAddPlaylistBtn.visibility =
            if (selectionMode && !reorderable && !playlistsRoot) View.VISIBLE else View.GONE
        browse.selectionRemovePlaylistBtn.visibility =
            if (selectionMode && reorderable) View.VISIBLE else View.GONE
        browse.selectionRemovePlaylistBtn.text = if (inFavorites) {
            getString(R.string.remove_from_favorites)
        } else {
            getString(R.string.remove_from_playlist)
        }
        browse.selectionDeleteBtn.visibility =
            if (selectionMode && !reorderable) View.VISIBLE else View.GONE
        browse.selectionDeleteBtn.text = if (playlistsRoot) {
            getString(R.string.playlist_delete)
        } else {
            getString(R.string.file_delete)
        }
        browseAdapter.setSelectionState(
            enabled = selectionMode,
            selected = selectedKeys.toSet(),
            dragEnabled = selectionMode && reorderable,
            // 播放列表总览下用户列表可长按多选（删除列表）
            foldersSelectable = playlistsRoot,
        )
    }

    private fun selectedFileItems(): List<BrowseItem> {
        return currentItems.filter {
            it.type == BrowseItemType.FILE &&
                selectedKeys.contains(browseAdapter.selectionKeyOf(it))
        }
    }

    private fun selectedPlaylistFolders(): List<BrowseItem> {
        return currentItems.filter {
            it.type == BrowseItemType.FOLDER &&
                selectedKeys.contains(browseAdapter.selectionKeyOf(it))
        }
    }

    private fun showAddToPlaylistMenu(anchor: View, items: List<BrowseItem>) {
        val tracks = items.mapNotNull { PlaylistStore.Track.fromBrowseItem(it) }
        if (tracks.isEmpty()) {
            showToast(getString(R.string.selection_empty))
            return
        }
        val playlists = PlaylistStore.list(this)
        val popup = PopupMenu(this, anchor)
        playlists.forEachIndexed { index, pl ->
            popup.menu.add(0, MENU_PLAYLIST_BASE + index, index, pl.name)
        }
        popup.menu.add(0, MENU_PLAYLIST_NEW, playlists.size, R.string.playlist_new)
        popup.setOnMenuItemClickListener { menuItem ->
            when {
                menuItem.itemId == MENU_PLAYLIST_NEW -> {
                    showCreatePlaylistDialog { created ->
                        addTracksToPlaylist(created.id, created.name, tracks)
                    }
                    true
                }
                menuItem.itemId >= MENU_PLAYLIST_BASE -> {
                    val idx = menuItem.itemId - MENU_PLAYLIST_BASE
                    val pl = playlists.getOrNull(idx) ?: return@setOnMenuItemClickListener false
                    addTracksToPlaylist(pl.id, pl.name, tracks)
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    private fun showCreatePlaylistDialog(onCreated: (PlaylistStore.Playlist) -> Unit) {
        val input = EditText(this).apply {
            setText(getString(R.string.playlist_name_default))
            setSelection(text.length)
            hint = getString(R.string.playlist_new_hint)
            setPadding(48, 32, 48, 32)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.playlist_new_title)
            .setView(input)
            .setPositiveButton(R.string.confirm) { _, _ ->
                val name = input.text?.toString().orEmpty().trim()
                if (name.isEmpty()) {
                    showToast(getString(R.string.file_name_empty))
                    return@setPositiveButton
                }
                val created = PlaylistStore.create(this, name)
                onCreated(created)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun addTracksToPlaylist(
        playlistId: String,
        playlistName: String,
        tracks: List<PlaylistStore.Track>,
    ) {
        val added = PlaylistStore.addTracks(this, playlistId, tracks)
        if (added > 0) {
            showToast(getString(R.string.playlist_added, playlistName, added))
        } else {
            showToast(getString(R.string.playlist_added_none))
        }
        if (selectionMode) exitSelectionMode()
        // 若当前在播放列表总览，刷新数量
        if (PlaylistPaths.isInPlaylistSpace(currentFolder)) {
            loadFolder(currentFolder)
        }
    }

    private fun removeSelectedFromPlaylist() {
        val files = selectedFileItems()
        if (files.isEmpty()) {
            showToast(getString(R.string.selection_empty))
            return
        }
        val ids = files.map { it.mediaId }.toSet()
        if (PlaylistPaths.isFavorites(currentFolder)) {
            val removed = FavoriteStore.removeMediaIds(this, ids)
            showToast(getString(R.string.playlist_removed, removed))
            exitSelectionMode()
            refreshFavoritesUi()
            loadFolder(currentFolder)
            return
        }
        val playlistId = PlaylistPaths.playlistIdOf(currentFolder) ?: return
        val removed = PlaylistStore.removeTracks(this, playlistId, ids)
        showToast(getString(R.string.playlist_removed, removed))
        exitSelectionMode()
        loadFolder(currentFolder)
    }

    private fun onSelectionDeleteClick() {
        if (isPlaylistsRoot()) {
            confirmDeleteSelectedPlaylists()
        } else {
            confirmDeleteSelectedFiles()
        }
    }

    private fun confirmDeleteSelectedPlaylists() {
        val folders = selectedPlaylistFolders()
        if (folders.isEmpty()) {
            showToast(getString(R.string.selection_empty))
            return
        }
        val names = folders.joinToString("、") { it.name }
        AlertDialog.Builder(this)
            .setTitle(R.string.playlist_delete)
            .setMessage(getString(R.string.playlist_delete_confirm, names))
            .setPositiveButton(R.string.confirm) { _, _ ->
                folders.forEach { folder ->
                    PlaylistPaths.playlistIdOf(folder.folderPath)?.let {
                        PlaylistStore.delete(this, it)
                    }
                }
                showToast(getString(R.string.playlist_deleted))
                exitSelectionMode()
                loadFolder(currentFolder)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun confirmDeleteSelectedFiles() {
        val files = selectedFileItems()
        if (files.isEmpty()) {
            showToast(getString(R.string.selection_empty))
            return
        }
        val msg = if (files.size == 1) {
            getString(R.string.file_delete_confirm_msg, files[0].name)
        } else {
            getString(R.string.file_delete_batch_confirm_msg, files.size)
        }
        AlertDialog.Builder(this)
            .setTitle(
                if (files.size == 1) R.string.file_delete_confirm_title
                else R.string.file_delete_batch_confirm_title,
            )
            .setMessage(msg)
            .setPositiveButton(R.string.file_delete) { _, _ ->
                performDeleteFiles(files)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun performDeleteFiles(items: List<BrowseItem>) {
        lifecycleScope.launch {
            val result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                MediaFileOps.deleteMany(this@MainActivity, items)
            }
            when {
                result.userConfirmPendingIntent != null -> {
                    pendingDeleteItems = items
                    try {
                        deleteConfirmLauncher.launch(
                            IntentSenderRequest.Builder(result.userConfirmPendingIntent.intentSender)
                                .build(),
                        )
                    } catch (_: Exception) {
                        pendingDeleteItems = emptyList()
                        showToast(getString(R.string.file_delete_fail))
                    }
                }
                result.ok -> {
                    val playingId = playerService?.currentState()?.item?.id
                    if (playingId != null && items.any { it.mediaId == playingId }) {
                        playerService?.playNext()
                    }
                    showToast(
                        if (items.size == 1) {
                            getString(R.string.file_delete_ok)
                        } else {
                            getString(R.string.file_delete_batch_ok, items.size)
                        },
                    )
                    exitSelectionMode()
                    loadFolder(currentFolder)
                }
                else -> showToast(getString(R.string.file_delete_fail))
            }
        }
    }

    private fun persistPlaylistOrder() {
        val orderedIds = currentItems
            .filter { it.type == BrowseItemType.FILE }
            .map { it.mediaId }
        if (PlaylistPaths.isFavorites(currentFolder)) {
            FavoriteStore.setMediaOrder(this, orderedIds)
            return
        }
        val playlistId = PlaylistPaths.playlistIdOf(currentFolder) ?: return
        if (playlistId == PlaylistPaths.FAVORITES_ID) return
        PlaylistStore.setTrackOrder(this, playlistId, orderedIds)
    }

    private fun playlistsRootItem(): BrowseItem {
        val count = PlaylistStore.list(this).size
        return BrowseItem(
            type = BrowseItemType.FOLDER,
            name = getString(R.string.playlists_title),
            folderPath = PlaylistPaths.ROOT,
            directFileCount = count,
            subtitle = getString(R.string.folder_song_count, count),
        )
    }

    private fun listPlaylistsAsBrowseItems(): List<BrowseItem> {
        val favCount = FavoriteStore.mediaCount(this)
        val favorites = BrowseItem(
            type = BrowseItemType.FOLDER,
            name = getString(R.string.favorites_songs),
            folderPath = PlaylistPaths.favoritesPath(),
            directFileCount = favCount,
            subtitle = getString(R.string.folder_song_count, favCount),
        )
        val lists = PlaylistStore.list(this).map { pl ->
            BrowseItem(
                type = BrowseItemType.FOLDER,
                name = pl.name,
                folderPath = PlaylistPaths.ofPlaylist(pl.id),
                directFileCount = pl.tracks.size,
                subtitle = getString(R.string.folder_song_count, pl.tracks.size),
            )
        }
        return listOf(favorites) + lists
    }

    private fun listPlaylistTracks(playlistId: String): List<BrowseItem> {
        val path = PlaylistPaths.ofPlaylist(playlistId)
        val pl = PlaylistStore.get(this, playlistId) ?: return emptyList()
        return pl.tracks.map { it.toBrowseItem(path) }
    }

    private suspend fun listFavoriteTracks(): List<BrowseItem> {
        val path = PlaylistPaths.favoritesPath()
        val ids = FavoriteStore.mediaIdsOrdered(this)
        return browser.resolveMediaIds(ids, path)
    }

    // endregion

    private fun playMedia(item: PlayableMedia) {
        MusicPlayerService.start(this)
        pendingPlay = item
        pendingFolderResumeMs = 0
        ensureBound()
        if (bound && playerService != null) {
            val toPlay = pendingPlay
            pendingPlay = null
            if (toPlay != null) {
                playerService?.playItem(toPlay, playableInFolder, currentFolder)
            }
        }
    }

    private fun navigateUp(): Boolean {
        if (selectionMode) {
            exitSelectionMode()
            return true
        }
        // 播放列表虚拟路径
        if (PlaylistPaths.isPlaylistsRoot(currentFolder)) {
            loadFolder("")
            return true
        }
        val plId = PlaylistPaths.playlistIdOf(currentFolder)
        if (plId != null) {
            loadFolder(PlaylistPaths.ROOT)
            return true
        }
        val parent = FolderBrowser.parentFolder(currentFolder) ?: return false
        loadFolder(parent)
        return true
    }

    private fun loadFolder(folderPath: String, scrollToMediaId: Long? = null) {
        if (selectionMode) exitSelectionMode()
        currentFolder = FolderBrowser.normalizeFolder(folderPath)
        browse.loadingBar.visibility = View.VISIBLE
        browse.emptyState.visibility = View.GONE
        browse.permissionBtn.visibility = View.GONE
        browse.songList.visibility = View.VISIBLE
        updatePathUi()

        lifecycleScope.launch {
            try {
                val roots = AppSettings.rootFolders(this@MainActivity)
                currentItems = when {
                    PlaylistPaths.isPlaylistsRoot(currentFolder) -> listPlaylistsAsBrowseItems()
                    PlaylistPaths.isFavorites(currentFolder) -> listFavoriteTracks()
                    PlaylistPaths.isUserPlaylist(currentFolder) -> {
                        listPlaylistTracks(PlaylistPaths.playlistIdOf(currentFolder)!!)
                    }
                    currentFolder.isEmpty() && roots.isNotEmpty() -> {
                        listOf(playlistsRootItem()) + browser.listConfiguredRoots(roots)
                    }
                    currentFolder.isEmpty() -> {
                        listOf(playlistsRootItem()) + browser.list(currentFolder)
                    }
                    else -> browser.list(currentFolder)
                }
                playableInFolder = currentItems.mapNotNull { it.toPlayable() }
                // 无动画提交列表
                browseAdapter.submitList(null)
                browseAdapter.submitList(currentItems.toList()) {
                    if (scrollToMediaId != null) {
                        scrollBrowseToMedia(scrollToMediaId)
                    }
                }
                val folderCount = currentItems.count { it.type == BrowseItemType.FOLDER }
                val fileCount = currentItems.count { it.type == BrowseItemType.FILE }
                browse.songCountText.text = getString(R.string.folder_summary, folderCount, fileCount)
                if (currentItems.isEmpty()) {
                    browse.emptyMessage.text = when {
                        PlaylistPaths.isPlaylistsRoot(currentFolder) ->
                            getString(R.string.playlists_empty)
                        PlaylistPaths.isFavorites(currentFolder) ->
                            getString(R.string.favorites_empty)
                        currentFolder.isEmpty() -> {
                            if (roots.isNotEmpty()) {
                                getString(R.string.settings_roots_empty)
                            } else {
                                getString(R.string.empty_library)
                            }
                        }
                        else -> getString(R.string.empty_folder)
                    }
                    browse.emptyState.visibility = View.VISIBLE
                    browse.songList.visibility = View.GONE
                }
                lastState?.let { applyPlayingHighlight(it) }
                updateSelectionUi()
                // 打开文件夹时，按需恢复该文件夹上次播放（外部已指定滚动目标则不抢播）
                // 播放列表总览不恢复播放
                if (scrollToMediaId == null &&
                    !PlaylistPaths.isPlaylistsRoot(currentFolder)
                ) {
                    maybeResumeFolderPlayback()
                }
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, e.message ?: "load failed", Toast.LENGTH_SHORT).show()
            } finally {
                browse.loadingBar.visibility = View.GONE
            }
        }
    }

    /**
     * 打开文件夹后：滚动到上次曲目；若当前未在播放，则自动恢复播放与进度。
     */
    private fun maybeResumeFolderPlayback() {
        if (!AppSettings.resumeFolderOnOpen(this)) return
        if (currentFolder.isEmpty()) return
        // 启动全局恢复尚未完成时，不抢播
        if (AppSettings.resumeOnStart(this) && !restoreAttempted) return
        if (pendingRestore != null) return
        val snap = FolderPlaybackStore.load(this, currentFolder) ?: return
        // 列表中定位
        val inList = playableInFolder.firstOrNull {
            it.id == snap.media.id || it.uri == snap.media.uri
        }
        val target = inList ?: snap.media
        if (inList != null || playableInFolder.none { it.id == target.id }) {
            // 保证列表含目标
            if (inList == null) {
                playableInFolder = listOf(target) + playableInFolder
            }
        }
        scrollBrowseToMedia(target.id)

        val state = playerService?.currentState() ?: lastState
        // 正在播放其它内容时不打断，只定位列表
        if (state?.isPlaying == true) {
            val playingFolder = FolderBrowser.normalizeFolder(state.folderPath)
            if (playingFolder != currentFolder) {
                return
            }
            // 同文件夹已在播：只滚动
            return
        }
        // 未播放 → 恢复该文件夹上次进度并开始播放
        MusicPlayerService.start(this)
        ensureBound()
        val list = if (playableInFolder.any { it.id == target.id || it.uri == target.uri }) {
            playableInFolder
        } else {
            listOf(target) + playableInFolder
        }
        playableInFolder = list
        val svc = playerService
        if (svc != null) {
            svc.playItem(
                target,
                list,
                currentFolder,
                startPositionMs = snap.positionMs,
                autoPlay = true,
            )
            showToast(getString(R.string.folder_resume_toast, target.title))
        } else {
            // 服务尚未绑定：记入 pending，连上后再播
            pendingPlay = target
            pendingFolderResumeMs = snap.positionMs
        }
    }

    private fun scrollBrowseToMedia(mediaId: Long) {
        val index = currentItems.indexOfFirst {
            it.type == BrowseItemType.FILE && it.mediaId == mediaId
        }
        if (index < 0) return
        val lm = browse.songList.layoutManager as? LinearLayoutManager ?: return
        // 直接定位，不用 smoothScroll
        lm.scrollToPositionWithOffset(index, browse.songList.height / 4)
    }

    private fun updatePathUi() {
        val atRoot = currentFolder.isEmpty()
        browse.backBtn.visibility = if (atRoot) View.GONE else View.VISIBLE
        val hasRoots = AppSettings.rootFolders(this).isNotEmpty()
        when {
            PlaylistPaths.isPlaylistsRoot(currentFolder) -> {
                browse.titleText.text = getString(R.string.playlists_title)
                browse.pathText.text = getString(R.string.playlists_title)
            }
            PlaylistPaths.isFavorites(currentFolder) -> {
                val name = getString(R.string.favorites_songs)
                browse.titleText.text = name
                browse.pathText.text = getString(R.string.playlists_title) + " / " + name
            }
            PlaylistPaths.isUserPlaylist(currentFolder) -> {
                val id = PlaylistPaths.playlistIdOf(currentFolder)!!
                val name = PlaylistStore.get(this, id)?.name
                    ?: getString(R.string.playlists_title)
                browse.titleText.text = name
                browse.pathText.text = getString(R.string.playlists_title) + " / " + name
            }
            else -> {
                browse.titleText.text = when {
                    atRoot && hasRoots -> getString(R.string.roots_home)
                    atRoot -> getString(R.string.app_name)
                    else -> currentFolder.trim('/').substringAfterLast('/')
                }
                browse.pathText.text = when {
                    atRoot && hasRoots -> getString(R.string.settings_section_roots)
                    else -> FolderBrowser.displayPath(currentFolder)
                }
            }
        }
    }

    private fun showPermissionRequired() {
        browse.loadingBar.visibility = View.GONE
        browse.songList.visibility = View.GONE
        browse.songCountText.text = getString(R.string.folder_summary, 0, 0)
        browse.emptyMessage.text = getString(R.string.permission_required)
        browse.emptyState.visibility = View.VISIBLE
        browse.permissionBtn.visibility = View.VISIBLE
        browse.backBtn.visibility = View.GONE
        browse.pathText.text = ""
        browse.titleText.text = getString(R.string.app_name)
    }

    private fun updateAllUi(state: MusicPlayerService.PlaybackState) {
        val prevId = lastState?.item?.id
        val prevFolder = lastState?.folderPath
        lastState = state
        currentPlayMode = state.playMode
        updatePlayerUi(state)
        updateSleepChips(state.sleepRemainingMs)
        applyPlayingHighlight(state)
        updateLyricsUi(state)
        playlistAdapter?.submit(playerService?.getPlaylist().orEmpty(), state.currentIndex)
        // 切歌（曲目或所在文件夹变化）时，1 屏自动定位
        val newId = state.item?.id
        val newFolder = state.folderPath
        if (newId != null && (newId != prevId || newFolder != prevFolder)) {
            maybeAutoLocateOnTrackChange(state)
        }
    }

    private fun applyPlayingHighlight(state: MusicPlayerService.PlaybackState) {
        browseAdapter.setPlaying(state.item?.id, state.folderPath)
        updateBrowseIndexText(state)
    }

    /** 2 屏封面：嵌入图 / 专辑图 / 同目录 cover */
    private fun updateCoverArt(media: PlayableMedia?) {
        if (!::player.isInitialized) return
        if (media == null) {
            coverLoadJob?.cancel()
            coverMediaId = null
            showCoverPlaceholder()
            return
        }
        if (coverMediaId == media.id) return
        coverMediaId = media.id
        coverLoadJob?.cancel()
        // 先占位，避免切歌闪旧图
        showCoverPlaceholder()
        coverLoadJob = lifecycleScope.launch {
            val bmp = CoverArtLoader.load(this@MainActivity, media)
            if (coverMediaId != media.id) return@launch
            if (bmp != null) {
                // 按比例缩放居中；透明圆角底（无底色，仍可 clip）
                player.coverFrame.setBackgroundResource(R.drawable.bg_cover_large_clear)
                player.coverImage.scaleType = ImageView.ScaleType.FIT_CENTER
                player.coverImage.setImageBitmap(bmp)
            } else {
                showCoverPlaceholder()
            }
        }
    }

    private fun showCoverPlaceholder() {
        if (!::player.isInitialized) return
        player.coverFrame.setBackgroundResource(R.drawable.bg_cover_large)
        player.coverImage.scaleType = ImageView.ScaleType.CENTER
        player.coverImage.setImageResource(R.drawable.ic_music_note)
    }

    /** 1 屏显示当前曲目序号，如 10/299 */
    private fun updateBrowseIndexText(state: MusicPlayerService.PlaybackState?) {
        if (!::browse.isInitialized) return
        val s = state ?: lastState
        if (s?.item == null) {
            browse.browseIndexText.visibility = View.GONE
            return
        }
        val total = s.playlistSize.coerceAtLeast(0)
        val index = if (total > 0 && s.currentIndex >= 0) s.currentIndex + 1 else 0
        if (total <= 0 || index <= 0) {
            browse.browseIndexText.visibility = View.GONE
            return
        }
        browse.browseIndexText.visibility = View.VISIBLE
        browse.browseIndexText.text = "$index/$total"
    }

    private fun updatePlayerUi(state: MusicPlayerService.PlaybackState) {
        updateLoopIcon(state.playMode)
        updateSeek60Buttons(state)
        updateSpeedButton(state.playbackSpeed)
        updateBrowseIndexText(state)

        val item = state.item
        if (item == null) {
            player.playerTitle.text = getString(R.string.now_playing_none)
            player.playerArtist.text = ""
            player.playerIndexText.text = "0/0"
            bar.barPositionText.text = "0:00"
            bar.barDurationText.text = "0:00"
            bar.barSeekBar.progress = 0
            bar.barPlayPauseBtn.setImageResource(R.drawable.ic_play)
            lyrics.lyricsTitle.text = getString(R.string.now_playing_none)
            lyrics.lyricsArtist.text = ""
            lyrics.lyricsPlayPauseBtn.setImageResource(R.drawable.ic_play)
            lyrics.lyricsPositionText.text = "0:00"
            lyrics.lyricsDurationText.text = "0:00"
            lyrics.lyricsSeekBar.progress = 0
            updatePlayerMiniLyrics(emptyList(), 0f, emptyMessage = true)
            updateFavoriteButtons(null)
            updateCoverArt(null)
            return
        }

        player.playerTitle.text = item.title
        player.playerArtist.text = item.subtitle
        // 文件夹内第几首 / 总数，如 10/299
        val total = state.playlistSize.coerceAtLeast(0)
        val index = if (total > 0 && state.currentIndex >= 0) state.currentIndex + 1 else 0
        player.playerIndexText.text = "$index/$total"

        lyrics.lyricsTitle.text = item.title
        lyrics.lyricsArtist.text = item.subtitle
        updateFavoriteButtons(item.id)
        updateCoverArt(item)

        val durationText = PlayableMedia.formatTime(state.durationMs.toLong())
        bar.barDurationText.text = durationText
        lyrics.lyricsDurationText.text = durationText

        // 淡蓝实心钮 + 白色图标
        val playIcon = if (state.isPlaying) R.drawable.ic_pause else R.drawable.ic_play
        bar.barPlayPauseBtn.setImageResource(playIcon)
        lyrics.lyricsPlayPauseBtn.setImageResource(playIcon)

        if (playerService?.isUserSeeking() != true) {
            val posText = PlayableMedia.formatTime(state.positionMs.toLong())
            bar.barPositionText.text = posText
            lyrics.lyricsPositionText.text = posText
            val progress = if (state.durationMs > 0) {
                ((state.positionMs.toLong() * 1000L) / state.durationMs).toInt().coerceIn(0, 1000)
            } else {
                0
            }
            bar.barSeekBar.progress = progress
            lyrics.lyricsSeekBar.progress = progress
        }
    }

    private fun updateSleepChips(remainingMs: Long) {
        if (remainingMs > 0L) {
            val countdown = PlayableMedia.formatCountdown(remainingMs)
            val browseText = getString(R.string.sleep_timer_chip_full, countdown)
            browse.sleepTimerChip.visibility = View.VISIBLE
            browse.sleepTimerChip.text = browseText
            // 播放页：图标旁显示倒计时
            player.playerSleepChip.visibility = View.VISIBLE
            player.playerSleepChip.text = countdown
            player.playerSleepBtn.alpha = 1f
        } else {
            browse.sleepTimerChip.visibility = View.GONE
            player.playerSleepChip.visibility = View.GONE
            player.playerSleepBtn.alpha = 0.85f
        }
    }

    private fun updateLyricsUi(state: MusicPlayerService.PlaybackState) {
        val media = state.item
        if (media == null) {
            currentLyricLines = emptyList()
            endLyricsBrowsing(resumeFollow = false)
            lyricsAdapter.submit(emptyList(), -1, -1, 0f)
            lyrics.lyricsEmpty.visibility = View.VISIBLE
            lyrics.lyricsEmptyTitle.text = getString(R.string.lyrics_empty)
            lyrics.lyricsEmptyHint.text = getString(R.string.lyrics_hint)
            updatePlayerMiniLyrics(emptyList(), 0f, emptyMessage = true)
            return
        }

        if (lastLyricMediaId != media.id) {
            lastLyricMediaId = media.id
            lyricsRepo.loadFor(media)
            endLyricsBrowsing(resumeFollow = false)
        }
        val display = lyricsRepo.displayAt(media, state.positionMs)
        currentLyricLines = display.allLines
        if (display.allLines.isEmpty()) {
            endLyricsBrowsing(resumeFollow = false)
            lyrics.lyricsEmpty.visibility = View.VISIBLE
            lyrics.lyricsEmptyTitle.text = display.message ?: getString(R.string.lyrics_empty)
            lyrics.lyricsEmptyHint.text = getString(R.string.lyrics_hint)
            lyricsAdapter.submit(emptyList(), -1, -1, 0f)
            updatePlayerMiniLyrics(emptyList(), 0f, emptyMessage = true)
            return
        }
        lyrics.lyricsEmpty.visibility = View.GONE
        // 仅换句时滚动；进度更新不滚动，避免跳动；用户手动浏览时不强制跟唱
        val shouldScroll = lyricsAdapter.submit(
            display.allLines,
            display.indexStart,
            display.indexEnd,
            display.progress,
        )
        if (shouldScroll && !lyricsUserBrowsing) {
            scrollLyricsToCurrentLine()
        }
        // 播放页中间：只显示当前句组（中英最多 3 行）+ 句内进度
        updatePlayerMiniLyrics(display.texts, display.progress, emptyMessage = false)
    }

    /** 播放页中间 2–3 行当前歌词（含中英文同时间戳多行） */
    private fun updatePlayerMiniLyrics(
        texts: List<String>,
        progress: Float,
        emptyMessage: Boolean,
    ) {
        val lines = listOf(
            player.playerLyricLine0,
            player.playerLyricLine1,
            player.playerLyricLine2,
        )
        if (emptyMessage || texts.isEmpty()) {
            lines.forEach {
                it.visibility = View.GONE
                it.setLine("", 0f)
            }
            player.playerLyricEmpty.visibility = View.VISIBLE
            return
        }
        player.playerLyricEmpty.visibility = View.GONE
        // 最多 3 行：当前时间戳组（如中+英），均为当前句组
        val show = texts.take(3)
        lines.forEachIndexed { i, view ->
            if (i < show.size) {
                view.visibility = View.VISIBLE
                view.setLine(show[i], progress, emphasize = true)
            } else {
                view.visibility = View.GONE
                view.setLine("", 0f, emphasize = false)
            }
        }
    }

    private fun modeLabel(mode: PlayMode): String = when (mode) {
        PlayMode.REPEAT_ONE -> getString(R.string.mode_repeat_one)
        PlayMode.REPEAT_FOLDER -> getString(R.string.mode_repeat_folder)
        PlayMode.NEXT_FOLDER -> getString(R.string.mode_next_folder)
        PlayMode.SHUFFLE -> getString(R.string.mode_shuffle)
    }

    private fun bindPlayerService() {
        if (bound) return
        val intent = Intent(this, MusicPlayerService::class.java)
        bindService(intent, connection, Context.BIND_AUTO_CREATE)
    }

    private fun ensureBound() {
        if (!bound || playerService == null) bindPlayerService()
    }

    private fun hasMediaPermission(): Boolean {
        return mediaPermissions().all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestPermissions() {
        permissionLauncher.launch(allRequestedPermissions())
    }

    private fun mediaPermissions(): Array<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.READ_MEDIA_AUDIO, Manifest.permission.READ_MEDIA_VIDEO)
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }

    private fun allRequestedPermissions(): Array<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(
                Manifest.permission.READ_MEDIA_AUDIO,
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.POST_NOTIFICATIONS,
            )
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }

    companion object {
        private const val LYRICS_BROWSE_IDLE_MS = 4000L
        private const val PAGE_BROWSE = 0
        private const val PAGE_PLAYER = 1
        private const val PAGE_LYRICS = 2

        private const val MENU_REFRESH = 1
        private const val MENU_PLAY_MODE = 2
        private const val MENU_GOTO_FOLDER = 3
        private const val MENU_SLEEP = 4
        /** itemId = MENU_SLEEP_MIN + minutes（5..120） */
        private const val MENU_SLEEP_MIN = 1000
        private const val MENU_SLEEP_MAX = 1000 + 120
        private const val MENU_SETTINGS = 6
        private const val MENU_STOP = 5
        private const val MENU_SPEED_BASE = 2000
        private const val MENU_NEW_PLAYLIST = 7
        private const val MENU_PLAYLIST_BASE = 3000
        private const val MENU_PLAYLIST_NEW = 3999

        private val SPEED_OPTIONS = floatArrayOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f, 3.0f)
    }
}
