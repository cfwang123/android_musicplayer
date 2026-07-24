package com.whj.music.player

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.media.session.MediaButtonReceiver
import com.whj.music.MainActivity
import com.whj.music.MusicApp
import com.whj.music.R
import com.whj.music.data.AppSettings
import com.whj.music.data.FolderBrowser
import com.whj.music.data.FolderPlaybackStore
import com.whj.music.data.PlayModeStore
import com.whj.music.data.PlaybackStore
import com.whj.music.model.PlayMode
import com.whj.music.model.PlayableMedia
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MusicPlayerService : Service() {
    private val binder = LocalBinder()
    private var mediaPlayer: MediaPlayer? = null
    private val playlist = mutableListOf<PlayableMedia>()
    private var currentIndex = -1
    private var userSeeking = false
    private var playlistFolder: String = ""
    private var playMode: PlayMode = PlayMode.REPEAT_FOLDER
    private var isForeground = false

    /** 定时关闭截止时间（elapsedRealtime），0 表示未启用 */
    private var sleepDeadlineElapsed: Long = 0L

    private var playSession = 0
    private var consecutiveFailures = 0
    private var pendingSeekMs = 0
    private var autoPlayOnPrepared = true
    /** 本曲真正 start 的时间，用于过滤误触发的 onCompletion */
    private var trackStartedElapsed: Long = 0L
    /** 播放倍速，默认 1.0 */
    private var playbackSpeed: Float = 1.0f

    private lateinit var folderBrowser: FolderBrowser
    private lateinit var audioManager: AudioManager
    private lateinit var equalizerController: EqualizerController
    private lateinit var volumeNormalizeController: VolumeNormalizeController
    private var volumeNormJob: Job? = null
    /** 当前曲分析得到的平均 RMS；null 表示未知或未启用 */
    private var currentTrackRms: Float? = null
    /** 是否正在后台分析平均音量 */
    private var volumeNormAnalyzing: Boolean = false
    private var audioFocusRequest: AudioFocusRequest? = null
    private var hasAudioFocus = false
    /** 因短暂音频焦点丢失（导航等）而暂停，焦点恢复后自动续播 */
    private var pausedByTransientFocus = false
    private var noisyReceiverRegistered = false
    /** 锁屏 / 通知栏 / 蓝牙线控 */
    private var mediaSession: MediaSessionCompat? = null
    private var lastSessionMetaId: Long? = null
    private var lastSessionPosUpdateElapsed: Long = 0L

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** 拔出有线耳机 / 断开部分输出设备时暂停 */
    private val becomingNoisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != AudioManager.ACTION_AUDIO_BECOMING_NOISY) return
            if (isPlaying()) {
                pausedByTransientFocus = false
                pauseInternal(updateFocus = true)
            }
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    /**
     * UI 刷新策略（省电）：
     * - LYRICS：歌词页跟唱蒙版，约 24fps
     * - FOREGROUND：界面可见非歌词，约 4fps（进度条够用）
     * - BACKGROUND：无界面监听 / 后台，约 1fps（仅定时关闭等）
     */
    private var uiRefreshMode: UiRefreshMode = UiRefreshMode.BACKGROUND

    private val progressRunnable = object : Runnable {
        override fun run() {
            checkSleepTimer()
            // 无监听者时不必向 Activity 分发（后台省电关键）
            if (listeners.isNotEmpty()) {
                notifyState()
            }
            // 锁屏进度：低频刷新即可（2s）
            maybeUpdateMediaSessionPosition()
            if (isPlaying() || sleepDeadlineElapsed > 0L) {
                mainHandler.postDelayed(this, progressIntervalMs())
            }
        }
    }

    private val listeners = mutableSetOf<(PlaybackState) -> Unit>()

    private val audioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                // 长期抢占：暂停，不自动续播
                pausedByTransientFocus = false
                if (isPlaying()) pauseInternal(updateFocus = false)
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK,
            -> {
                // 导航/通知等短暂占用：暂停，焦点回来后继续
                if (isPlaying()) {
                    pausedByTransientFocus = true
                    pauseInternal(updateFocus = false)
                }
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                // 恢复归一化后的音量（勿强制 1.0）
                if (::volumeNormalizeController.isInitialized) {
                    volumeNormalizeController.reapplyPlayerVolume(mediaPlayer)
                }
                if (pausedByTransientFocus) {
                    pausedByTransientFocus = false
                    resumeAfterFocusGain()
                }
            }
        }
    }

    inner class LocalBinder : Binder() {
        fun getService(): MusicPlayerService = this@MusicPlayerService
    }

    override fun onCreate() {
        super.onCreate()
        folderBrowser = FolderBrowser(this)
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        equalizerController = EqualizerController(this)
        volumeNormalizeController = VolumeNormalizeController(this)
        playMode = AppSettings.defaultPlayMode(this)
        PlayModeStore.save(this, playMode)
        playbackSpeed = AppSettings.defaultPlaybackSpeed(this)
        initMediaSession()
        registerBecomingNoisyReceiver()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // startForegroundService 后必须尽快进入前台，否则会 ANR/被杀
        ensureForeground()
        // 耳机线控 / 蓝牙媒体键
        MediaButtonReceiver.handleIntent(mediaSession, intent)
        when (intent?.action) {
            ACTION_PLAY_PAUSE -> togglePlayPause()
            ACTION_NEXT -> playNext()
            ACTION_PREV -> playPrevious()
            ACTION_STOP -> stopSelfAndRelease()
            ACTION_AUTO_CLOSE -> {
                // 空闲超时：保存进度、停播并结束服务（勿 START_STICKY，避免被系统拉起）
                try {
                    saveCurrentPlayback()
                } catch (_: Exception) {
                    // ignore
                }
                sleepDeadlineElapsed = 0L
                pausedByTransientFocus = false
                stopSelfAndRelease()
                return START_NOT_STICKY
            }
        }
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // 滑掉任务卡片时：若在播放则保持服务；未播放则结束
        if (!isPlaying() && mediaPlayer == null) {
            stopSelfAndRelease()
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        saveCurrentPlayback()
        mainHandler.removeCallbacks(progressRunnable)
        serviceScope.cancel()
        unregisterBecomingNoisyReceiver()
        abandonAudioFocus()
        releasePlayer(invalidateSession = true)
        equalizerController.release()
        releaseMediaSession()
        super.onDestroy()
    }

    fun equalizerSnapshot(): EqualizerController.Snapshot = equalizerController.snapshot()

    fun setEqualizerEnabled(enabled: Boolean) {
        equalizerController.setEnabled(enabled)
    }

    fun setEqualizerPreset(index: Int) {
        equalizerController.usePreset(index)
    }

    fun setEqualizerBandLevel(bandIndex: Int, levelMilliBel: Short) {
        equalizerController.setBandLevel(bandIndex, levelMilliBel)
    }

    /** 写入当前曲目与进度（切换/退出时调用）；同时按文件夹记忆 */
    fun saveCurrentPlayback() {
        val item = playlist.getOrNull(currentIndex) ?: return
        val pos = try {
            mediaPlayer?.currentPosition ?: 0
        } catch (_: Exception) {
            0
        }
        PlaybackStore.save(this, item, pos)
        FolderPlaybackStore.save(this, item, pos)
    }

    fun addListener(listener: (PlaybackState) -> Unit) {
        listeners += listener
        listener(currentState())
        // 有界面监听后按当前模式重启进度节奏
        if (isPlaying() || sleepDeadlineElapsed > 0L) {
            startProgressUpdates()
        }
    }

    fun removeListener(listener: (PlaybackState) -> Unit) {
        listeners -= listener
        if (listeners.isEmpty() && (isPlaying() || sleepDeadlineElapsed > 0L)) {
            // 界面离开：降到后台刷新间隔
            startProgressUpdates()
        }
    }

    /**
     * 由界面根据当前页 / 可见性设置刷新档位。
     * 无监听者时自动按 BACKGROUND 计时，避免后台仍 24fps。
     */
    fun setUiRefreshMode(mode: UiRefreshMode) {
        if (uiRefreshMode == mode) return
        uiRefreshMode = mode
        if (isPlaying() || sleepDeadlineElapsed > 0L) {
            startProgressUpdates()
        }
    }

    fun getUiRefreshMode(): UiRefreshMode = uiRefreshMode

    fun getPlayMode(): PlayMode = playMode

    fun setPlayMode(mode: PlayMode) {
        val wasShuffle = playMode == PlayMode.SHUFFLE
        playMode = mode
        PlayModeStore.save(this, mode)
        // 重新进入随机：对当前列表做一次乱序；之后上/下一首按该顺序
        if (mode == PlayMode.SHUFFLE && !wasShuffle) {
            reshufflePlaylistKeepCurrent()
        }
        notifyState()
    }

    fun cyclePlayMode(): PlayMode {
        val next = playMode.next()
        setPlayMode(next)
        return next
    }

    /**
     * 随机模式：打乱 [playlist] 顺序，保持当前曲目下标正确。
     * 仅在进入 SHUFFLE 或载入新列表且已是 SHUFFLE 时调用。
     */
    private fun reshufflePlaylistKeepCurrent() {
        if (playlist.size <= 1) return
        val current = playlist.getOrNull(currentIndex)
        playlist.shuffle(Random.Default)
        if (current != null) {
            currentIndex = indexOfInPlaylist(current).takeIf { it >= 0 } ?: 0
        }
    }

    private fun indexOfInPlaylist(item: PlayableMedia): Int {
        return playlist.indexOfFirst { it.id == item.id && it.uri == item.uri }
            .takeIf { it >= 0 }
            ?: playlist.indexOfFirst { it.uri == item.uri }.takeIf { it >= 0 }
            ?: playlist.indexOfFirst {
                !item.filePath.isNullOrBlank() && it.filePath == item.filePath
            }.takeIf { it >= 0 }
            ?: playlist.indexOfFirst { it.id == item.id && item.id != 0L }
    }

    /** 启动定时关闭，单位分钟；到时暂停并清空定时 */
    fun startSleepTimer(minutes: Int) {
        if (minutes <= 0) {
            cancelSleepTimer()
            return
        }
        sleepDeadlineElapsed = SystemClock.elapsedRealtime() + minutes * 60_000L
        startProgressUpdates()
        notifyState()
    }

    fun cancelSleepTimer() {
        sleepDeadlineElapsed = 0L
        notifyState()
        if (!isPlaying()) {
            stopProgressUpdates()
        }
    }

    fun getPlaybackSpeed(): Float = playbackSpeed

    fun setPlaybackSpeed(speed: Float) {
        val clamped = speed.coerceIn(0.25f, 4.0f)
        playbackSpeed = clamped
        applyPlaybackSpeed()
        notifyState()
    }

    fun sleepRemainingMs(): Long {
        if (sleepDeadlineElapsed <= 0L) return 0L
        return (sleepDeadlineElapsed - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
    }

    private fun checkSleepTimer() {
        if (sleepDeadlineElapsed <= 0L) return
        if (SystemClock.elapsedRealtime() < sleepDeadlineElapsed) return
        sleepDeadlineElapsed = 0L
        pauseInternal(updateFocus = true)
        updateForegroundNotification()
        notifyState()
    }

    fun setPlaylist(
        items: List<PlayableMedia>,
        startIndex: Int = 0,
        folderPath: String? = null,
        startPositionMs: Int = 0,
        autoPlay: Boolean = true,
    ) {
        if (folderPath != null) {
            playlistFolder = FolderBrowser.normalizeFolder(folderPath)
        } else if (items.isNotEmpty()) {
            playlistFolder = FolderBrowser.normalizeFolder(items.first().folderPath)
        }
        playlist.clear()
        playlist.addAll(items)
        if (playlist.isEmpty()) {
            currentIndex = -1
            releasePlayer(invalidateSession = true)
            notifyState()
            return
        }
        var index = startIndex.coerceIn(0, playlist.lastIndex)
        // 已在随机模式时载入新目录列表：按一次乱序建立播放顺序
        if (playMode == PlayMode.SHUFFLE && playlist.size > 1) {
            val startItem = playlist[index]
            playlist.shuffle(Random.Default)
            index = indexOfInPlaylist(startItem).takeIf { it >= 0 } ?: 0
        }
        playAt(index, startPositionMs = startPositionMs, autoPlay = autoPlay)
    }

    fun playItem(
        item: PlayableMedia,
        items: List<PlayableMedia>,
        folderPath: String = "",
        startPositionMs: Int = 0,
        autoPlay: Boolean = true,
    ) {
        // 优先精确匹配，避免仅 id 撞车时播错曲
        val index = items.indexOfFirst { it.id == item.id && it.uri == item.uri }
            .takeIf { it >= 0 }
            ?: items.indexOfFirst { it.uri == item.uri }.takeIf { it >= 0 }
            ?: items.indexOfFirst {
                !item.filePath.isNullOrBlank() && it.filePath == item.filePath
            }.takeIf { it >= 0 }
            ?: items.indexOfFirst { it.id == item.id && item.id != 0L }
        val folder = folderPath.ifBlank {
            item.folderPath.ifBlank { items.firstOrNull()?.folderPath.orEmpty() }
        }
        val list = if (index >= 0) items else (listOf(item) + items)
        val idx = if (index >= 0) index else 0
        Log.i(
            TAG,
            "playItem title=${item.title} idx=$idx/${list.size} seekMs=$startPositionMs autoPlay=$autoPlay",
        )
        setPlaylist(list, idx, folder, startPositionMs, autoPlay)
    }

    fun togglePlayPause() {
        // 有列表但未 prepare（焦点失败 / 启动恢复中断等）：重新起播当前项
        val player = mediaPlayer
        if (player == null) {
            if (currentIndex in playlist.indices) {
                val pos = lastKnownPositionMs().coerceAtLeast(0)
                Log.i(TAG, "togglePlayPause: no player, re-play index=$currentIndex pos=$pos")
                playAt(currentIndex, startPositionMs = pos, autoPlay = true)
            } else {
                Log.w(TAG, "togglePlayPause: no player and empty playlist")
            }
            return
        }
        try {
            if (player.isPlaying) {
                pausedByTransientFocus = false
                pauseInternal(updateFocus = true)
            } else {
                pausedByTransientFocus = false
                if (!requestAudioFocus()) {
                    Log.w(TAG, "togglePlayPause: audio focus denied")
                    return
                }
                player.start()
                startProgressUpdates()
                updateForegroundNotification()
                notifyState()
            }
        } catch (e: IllegalStateException) {
            Log.w(TAG, "togglePlayPause failed, re-play current", e)
            if (currentIndex in playlist.indices) {
                playAt(currentIndex, startPositionMs = lastKnownPositionMs(), autoPlay = true)
            }
        }
    }

    /** 是否已创建 MediaPlayer（可能已 pause / 正在 prepare） */
    fun hasPreparedOrPreparingPlayer(): Boolean = mediaPlayer != null

    private fun lastKnownPositionMs(): Int {
        return try {
            mediaPlayer?.currentPosition?.coerceAtLeast(0)
        } catch (_: Exception) {
            null
        } ?: PlaybackStore.load(this)?.positionMs?.coerceAtLeast(0) ?: 0
    }

    fun stopPlayback() {
        saveCurrentPlayback()
        sleepDeadlineElapsed = 0L
        pausedByTransientFocus = false
        releasePlayer(invalidateSession = true)
        playlist.clear()
        currentIndex = -1
        playlistFolder = ""
        abandonAudioFocus()
        stopForeground(STOP_FOREGROUND_REMOVE)
        isForeground = false
        notifyState()
        stopSelf()
    }

    fun playNext() {
        if (playlist.isEmpty()) return
        when (playMode) {
            // 随机 = 已打乱的列表顺序前进（与文件夹循环相同）
            PlayMode.REPEAT_ONE, PlayMode.REPEAT_FOLDER, PlayMode.SHUFFLE -> {
                val next = if (currentIndex + 1 < playlist.size) currentIndex + 1 else 0
                if (playlist.size == 1 && next == currentIndex) {
                    restartCurrent()
                } else {
                    playAt(next)
                }
            }
            PlayMode.NEXT_FOLDER -> {
                if (currentIndex + 1 < playlist.size) {
                    playAt(currentIndex + 1)
                } else {
                    loadAdjacentFolder(forward = true)
                }
            }
        }
    }

    fun playPrevious() {
        if (playlist.isEmpty()) return
        val player = mediaPlayer
        try {
            if (player != null && player.currentPosition > 3000) {
                player.seekTo(0)
                notifyState()
                return
            }
        } catch (_: Exception) {
            // fall through
        }
        when (playMode) {
            PlayMode.REPEAT_ONE, PlayMode.REPEAT_FOLDER, PlayMode.SHUFFLE -> {
                val prev = if (currentIndex - 1 >= 0) currentIndex - 1 else playlist.lastIndex
                playAt(prev)
            }
            PlayMode.NEXT_FOLDER -> {
                if (currentIndex - 1 >= 0) {
                    playAt(currentIndex - 1)
                } else {
                    loadAdjacentFolder(forward = false, playLast = true)
                }
            }
        }
    }

    fun seekTo(positionMs: Int) {
        try {
            val duration = mediaPlayer?.duration?.takeIf { it > 0 } ?: Int.MAX_VALUE
            mediaPlayer?.seekTo(positionMs.coerceIn(0, duration))
            saveCurrentPlayback()
            notifyState()
        } catch (e: IllegalStateException) {
            Log.w(TAG, "seekTo failed", e)
        }
    }

    fun seekBy(deltaMs: Int) {
        val player = mediaPlayer ?: return
        try {
            val duration = player.duration.takeIf { it > 0 } ?: return
            val target = (player.currentPosition + deltaMs).coerceIn(0, duration)
            player.seekTo(target)
            saveCurrentPlayback()
            notifyState()
        } catch (e: IllegalStateException) {
            Log.w(TAG, "seekBy failed", e)
        }
    }

    fun setUserSeeking(seeking: Boolean) {
        if (userSeeking && !seeking) {
            saveCurrentPlayback()
        }
        userSeeking = seeking
    }

    fun isUserSeeking(): Boolean = userSeeking

    fun isPlaying(): Boolean = try {
        mediaPlayer?.isPlaying == true
    } catch (_: Exception) {
        false
    }

    fun hasActiveSession(): Boolean = playlist.isNotEmpty() && currentIndex in playlist.indices

    fun getPlaylist(): List<PlayableMedia> = playlist.toList()

    fun getCurrentIndex(): Int = currentIndex

    /** 当前曲目音量归一化信息（详细信息对话框用） */
    fun volumeNormalizeInfo(): VolumeNormalizeInfo {
        val enabled = AppSettings.volumeNormalizeEnabled(this)
        val target = AppSettings.volumeTargetRms(this)
        val gain = if (::volumeNormalizeController.isInitialized) {
            volumeNormalizeController.appliedGain
        } else {
            1f
        }
        return VolumeNormalizeInfo(
            enabled = enabled,
            analyzing = volumeNormAnalyzing,
            trackRms = currentTrackRms,
            appliedGain = if (enabled) gain else 1f,
            targetRms = target,
        )
    }

    /** 设置变更后重算当前曲增益（沿用已缓存 RMS） */
    fun reapplyVolumeNormalize() {
        val item = playlist.getOrNull(currentIndex) ?: return
        val player = mediaPlayer ?: return
        scheduleVolumeNormalize(playSession, item, player)
    }

    fun currentState(): PlaybackState {
        val item = playlist.getOrNull(currentIndex)
        val player = mediaPlayer
        val position = try {
            player?.currentPosition ?: 0
        } catch (_: Exception) {
            0
        }
        val duration = try {
            player?.duration?.takeIf { it > 0 } ?: (item?.durationMs?.toInt() ?: 0)
        } catch (_: Exception) {
            item?.durationMs?.toInt() ?: 0
        }
        return PlaybackState(
            item = item,
            isPlaying = isPlaying(),
            positionMs = position,
            durationMs = duration,
            playlistSize = playlist.size,
            currentIndex = currentIndex,
            playMode = playMode,
            folderPath = playlistFolder,
            sleepRemainingMs = sleepRemainingMs(),
            playbackSpeed = playbackSpeed,
        )
    }

    private fun applyPlaybackSpeed() {
        val player = mediaPlayer ?: return
        try {
            val wasPlaying = player.isPlaying
            val params = try {
                player.playbackParams
            } catch (_: Exception) {
                PlaybackParams()
            }
            player.playbackParams = params.setSpeed(playbackSpeed)
            // 部分机型改倍速会误启动，需暂停回来
            if (!wasPlaying && player.isPlaying) {
                player.pause()
            }
        } catch (e: Exception) {
            Log.w(TAG, "applyPlaybackSpeed failed: $playbackSpeed", e)
        }
    }

    private fun pauseInternal(updateFocus: Boolean) {
        try {
            mediaPlayer?.pause()
        } catch (_: Exception) {
            // ignore
        }
        stopProgressUpdates()
        if (updateFocus) {
            // 用户主动暂停时仍可保留焦点，便于快速继续；此处不 abandon
        }
        saveCurrentPlayback()
        updateForegroundNotification()
        notifyState()
    }

    private fun onTrackCompleted() {
        when (playMode) {
            PlayMode.REPEAT_ONE -> restartCurrent()
            // 随机按打乱后的顺序循环
            PlayMode.REPEAT_FOLDER, PlayMode.SHUFFLE -> {
                val next = if (currentIndex + 1 < playlist.size) currentIndex + 1 else 0
                if (playlist.size == 1) {
                    restartCurrent()
                } else {
                    playAt(next)
                }
            }
            PlayMode.NEXT_FOLDER -> {
                if (currentIndex + 1 < playlist.size) {
                    playAt(currentIndex + 1)
                } else {
                    loadAdjacentFolder(forward = true)
                }
            }
        }
    }

    private fun restartCurrent() {
        try {
            if (!requestAudioFocus()) return
            pausedByTransientFocus = false
            mediaPlayer?.seekTo(0)
            mediaPlayer?.start()
            startProgressUpdates()
            updateForegroundNotification()
            notifyState()
        } catch (e: Exception) {
            Log.w(TAG, "restartCurrent failed", e)
            playAt(currentIndex)
        }
    }

    private fun resumeAfterFocusGain() {
        val player = mediaPlayer ?: return
        try {
            if (!requestAudioFocus()) return
            if (!player.isPlaying) {
                player.start()
                startProgressUpdates()
                updateForegroundNotification()
                notifyState()
            }
        } catch (e: Exception) {
            Log.w(TAG, "resumeAfterFocusGain failed", e)
        }
    }

    private fun registerBecomingNoisyReceiver() {
        if (noisyReceiverRegistered) return
        val filter = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(becomingNoisyReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(becomingNoisyReceiver, filter)
        }
        noisyReceiverRegistered = true
    }

    private fun unregisterBecomingNoisyReceiver() {
        if (!noisyReceiverRegistered) return
        try {
            unregisterReceiver(becomingNoisyReceiver)
        } catch (_: Exception) {
            // ignore
        }
        noisyReceiverRegistered = false
    }

    private fun loadAdjacentFolder(forward: Boolean, playLast: Boolean = false) {
        val folder = playlistFolder
        if (folder.isEmpty()) {
            if (playlist.isNotEmpty()) {
                playAt(if (playLast) playlist.lastIndex else 0)
            }
            return
        }
        serviceScope.launch {
            val result = runCatching {
                if (forward) {
                    folderBrowser.nextFolderPlaylist(folder)
                } else {
                    folderBrowser.previousFolderPlaylist(folder)
                }
            }.getOrNull()

            if (result == null) {
                if (playlist.isNotEmpty()) {
                    playAt(if (playLast) playlist.lastIndex else 0)
                }
                return@launch
            }
            val (nextFolder, items) = result
            if (nextFolder == folder && items.size == playlist.size) {
                playAt(if (playLast) playlist.lastIndex else 0)
                return@launch
            }
            val start = if (playLast) items.lastIndex else 0
            setPlaylist(items, start, nextFolder)
        }
    }

    private fun playAt(index: Int, startPositionMs: Int = 0, autoPlay: Boolean = true) {
        if (index !in playlist.indices) return
        // 切换曲目前先保存上一首进度
        if (currentIndex in playlist.indices && mediaPlayer != null) {
            saveCurrentPlayback()
        }
        // 设置：切换歌曲时是否保持倍速
        if (!AppSettings.keepSpeedAcrossTracks(this) &&
            currentIndex in playlist.indices &&
            currentIndex != index
        ) {
            playbackSpeed = AppSettings.defaultPlaybackSpeed(this)
        }
        currentIndex = index
        val item = playlist[index]
        pendingSeekMs = startPositionMs.coerceAtLeast(0)
        autoPlayOnPrepared = autoPlay

        val session = ++playSession
        releasePlayer(invalidateSession = false)

        ensureForeground()
        notifyState()

        // 焦点失败时仍 prepare，便于用户点播放再抢焦点；不再直接 return 导致「有曲名无播放器」
        val focusOk = requestAudioFocus()
        if (!focusOk) {
            Log.w(TAG, "audio focus denied before prepare, will prepare anyway title=${item.title}")
        }

        val player = MediaPlayer()
        try {
            player.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            // 后台播放保持 CPU 唤醒，避免息屏后解码中断
            player.setWakeMode(applicationContext, PowerManager.PARTIAL_WAKE_LOCK)
            player.setDataSource(applicationContext, item.uri)
            player.setOnPreparedListener { mp ->
                if (session != playSession) {
                    safeRelease(mp)
                    return@setOnPreparedListener
                }
                try {
                    consecutiveFailures = 0
                    val seek = pendingSeekMs
                    pendingSeekMs = 0
                    val dur = try {
                        mp.duration.takeIf { it > 0 } ?: 0
                    } catch (_: Exception) {
                        0
                    }
                    // 进度接近曲末时从头播，避免一恢复就马上播完、看起来像没播。
                    // duration 未知时禁止 seek 大进度，否则可能直接贴曲末 → onCompletion → 跳下一首。
                    val seekTo = when {
                        seek <= 0 -> 0
                        dur > 0 && seek >= dur - 1500 -> 0
                        dur > 0 -> seek.coerceIn(0, (dur - 1).coerceAtLeast(0))
                        else -> 0
                    }
                    if (seekTo > 0) {
                        mp.seekTo(seekTo)
                    }
                    applyPlaybackSpeed()
                    // 绑定均衡器（默认关闭，仅启用时改音色）
                    attachEqualizer(mp.audioSessionId)
                    // 音量归一化：先中性，后台分析后缩放
                    volumeNormalizeController.attach(mp.audioSessionId)
                    scheduleVolumeNormalize(session, item, mp)
                    trackStartedElapsed = 0L
                    if (autoPlayOnPrepared) {
                        if (!hasAudioFocus && !requestAudioFocus()) {
                            Log.w(TAG, "prepared but no focus, wait for user play: ${item.title}")
                            stopProgressUpdates()
                            saveCurrentPlayback()
                            updateForegroundNotification()
                            notifyState()
                            return@setOnPreparedListener
                        }
                        mp.start()
                        trackStartedElapsed = SystemClock.elapsedRealtime()
                        // 再设一次倍速，避免 start 后被重置
                        applyPlaybackSpeed()
                        startProgressUpdates()
                    } else {
                        // 恢复进度但不自动播放
                        stopProgressUpdates()
                    }
                    saveCurrentPlayback()
                    updateForegroundNotification()
                    notifyState()
                } catch (e: Exception) {
                    Log.w(TAG, "start after prepare failed: ${item.title}", e)
                    skipAfterFailure(session)
                }
            }
            player.setOnCompletionListener {
                if (session != playSession) return@setOnCompletionListener
                // 过滤误触发：刚 start 就 completion 时不要切下一首
                val startedAt = trackStartedElapsed
                val playedMs = if (startedAt > 0L) {
                    SystemClock.elapsedRealtime() - startedAt
                } else {
                    0L
                }
                val pos = try {
                    mediaPlayer?.currentPosition ?: 0
                } catch (_: Exception) {
                    0
                }
                val dur = try {
                    mediaPlayer?.duration?.takeIf { it > 0 } ?: 0
                } catch (_: Exception) {
                    0
                }
                if (startedAt > 0L && playedMs < 800L && (dur <= 0 || dur > 2_000)) {
                    Log.w(
                        TAG,
                        "ignore early completion title=${playlist.getOrNull(currentIndex)?.title} " +
                            "playedMs=$playedMs pos=$pos dur=$dur — restart current",
                    )
                    restartCurrent()
                    return@setOnCompletionListener
                }
                saveCurrentPlayback()
                onTrackCompleted()
            }
            player.setOnErrorListener { _, what, extra ->
                Log.w(TAG, "MediaPlayer error what=$what extra=$extra title=${item.title}")
                if (session != playSession) {
                    return@setOnErrorListener true
                }
                skipAfterFailure(session)
                true
            }
            mediaPlayer = player
            player.prepareAsync()
        } catch (e: Exception) {
            Log.e(TAG, "playAt failed: ${item.title}", e)
            safeRelease(player)
            if (mediaPlayer === player) mediaPlayer = null
            skipAfterFailure(session)
        }
    }

    private fun skipAfterFailure(session: Int) {
        if (session != playSession) return
        consecutiveFailures++
        if (playlist.isEmpty() || consecutiveFailures >= playlist.size) {
            Log.w(TAG, "stop after $consecutiveFailures consecutive failures")
            releasePlayer(invalidateSession = true)
            notifyState()
            return
        }
        val next = if (currentIndex + 1 < playlist.size) currentIndex + 1 else 0
        playAt(next)
    }

    private fun ensureForeground() {
        if (isForeground) {
            updateForegroundNotification()
            return
        }
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            } else {
                0
            },
        )
        isForeground = true
    }

    private fun attachEqualizer(audioSessionId: Int) {
        equalizerController.attach(audioSessionId)
    }

    /**
     * 若开启「自动统一音量」：读取/计算曲目平均 RMS，缩放到目标响度。
     * 有缓存时几乎立刻应用；无缓存时后台解码，完成后生效。
     */
    private fun scheduleVolumeNormalize(
        session: Int,
        item: PlayableMedia,
        player: MediaPlayer,
    ) {
        volumeNormJob?.cancel()
        volumeNormAnalyzing = false
        if (!AppSettings.volumeNormalizeEnabled(this)) {
            currentTrackRms = VolumeNormalizeController.getCachedRms(this, item.id)
            volumeNormalizeController.applyNeutral(player)
            return
        }
        val mediaId = item.id
        val targetRms = AppSettings.volumeTargetRms(this)
        val cached = VolumeNormalizeController.getCachedRms(this, mediaId)
        if (cached != null) {
            currentTrackRms = cached
            val gain = VolumeLoudnessAnalyzer.gainForRms(cached, targetRms)
            volumeNormalizeController.applyGain(player, gain)
            Log.i(
                TAG,
                "volume norm cached rms=$cached target=$targetRms gain=$gain title=${item.title}",
            )
            return
        }
        // 先按原音量播，分析完再调
        currentTrackRms = null
        volumeNormalizeController.applyNeutral(player)
        volumeNormAnalyzing = true
        val appCtx = applicationContext
        val uri = item.uri
        volumeNormJob = serviceScope.launch {
            val rms = withContext(Dispatchers.IO) {
                VolumeLoudnessAnalyzer.analyzeRms(appCtx, uri)
            }
            if (session != playSession) return@launch
            volumeNormAnalyzing = false
            if (rms == null) {
                Log.w(TAG, "volume norm analyze failed title=${item.title}")
                currentTrackRms = null
                volumeNormalizeController.applyNeutral(mediaPlayer)
                return@launch
            }
            VolumeNormalizeController.putCachedRms(appCtx, mediaId, rms)
            currentTrackRms = rms
            val target = AppSettings.volumeTargetRms(this@MusicPlayerService)
            val gain = VolumeLoudnessAnalyzer.gainForRms(rms, target)
            volumeNormalizeController.applyGain(mediaPlayer, gain)
            Log.i(TAG, "volume norm rms=$rms target=$target gain=$gain title=${item.title}")
        }
    }

    private fun releasePlayer(invalidateSession: Boolean) {
        if (invalidateSession) {
            playSession++
        }
        volumeNormJob?.cancel()
        volumeNormJob = null
        volumeNormAnalyzing = false
        currentTrackRms = null
        stopProgressUpdates()
        equalizerController.release()
        if (::volumeNormalizeController.isInitialized) {
            volumeNormalizeController.release()
        }
        val player = mediaPlayer
        mediaPlayer = null
        if (player != null) {
            try {
                player.setOnPreparedListener(null)
                player.setOnCompletionListener(null)
                player.setOnErrorListener(null)
            } catch (_: Exception) {
                // ignore
            }
            safeRelease(player)
        }
    }

    private fun safeRelease(player: MediaPlayer) {
        try {
            player.reset()
        } catch (_: Exception) {
            // ignore
        }
        try {
            player.release()
        } catch (_: Exception) {
            // ignore
        }
    }

    private fun stopSelfAndRelease() {
        releasePlayer(invalidateSession = true)
        playlist.clear()
        currentIndex = -1
        abandonAudioFocus()
        stopForeground(STOP_FOREGROUND_REMOVE)
        isForeground = false
        notifyState()
        stopSelf()
    }

    private fun requestAudioFocus(): Boolean {
        if (hasAudioFocus) return true
        val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(attrs)
                .setOnAudioFocusChangeListener(audioFocusChangeListener, mainHandler)
                .setAcceptsDelayedFocusGain(true)
                .build()
            audioFocusRequest = req
            audioManager.requestAudioFocus(req)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                audioFocusChangeListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN,
            )
        }
        hasAudioFocus = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        return hasAudioFocus
    }

    private fun abandonAudioFocus() {
        if (!hasAudioFocus) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
            audioFocusRequest = null
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(audioFocusChangeListener)
        }
        hasAudioFocus = false
    }

    private fun startProgressUpdates() {
        mainHandler.removeCallbacks(progressRunnable)
        mainHandler.post(progressRunnable)
    }

    private fun stopProgressUpdates() {
        mainHandler.removeCallbacks(progressRunnable)
    }

    private fun progressIntervalMs(): Long {
        // 无 UI 监听 → 强制后台档
        val mode = if (listeners.isEmpty()) UiRefreshMode.BACKGROUND else uiRefreshMode
        return when (mode) {
            UiRefreshMode.LYRICS -> INTERVAL_LYRICS_MS
            UiRefreshMode.FOREGROUND -> INTERVAL_FOREGROUND_MS
            UiRefreshMode.BACKGROUND -> {
                // 无定时关闭时再降一档，仅维持锁屏进度
                if (sleepDeadlineElapsed > 0L) INTERVAL_BACKGROUND_MS else INTERVAL_BACKGROUND_IDLE_MS
            }
        }
    }

    private fun notifyState() {
        if (listeners.isEmpty()) return
        val state = currentState()
        listeners.forEach { it(state) }
    }

    private fun initMediaSession() {
        val session = MediaSessionCompat(this, "MusicPlayerService")
        session.setFlags(
            MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or
                MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS,
        )
        session.setCallback(
            object : MediaSessionCompat.Callback() {
                override fun onPlay() {
                    pausedByTransientFocus = false
                    val player = mediaPlayer ?: return
                    try {
                        if (!player.isPlaying) {
                            if (!requestAudioFocus()) return
                            player.start()
                            startProgressUpdates()
                            updateForegroundNotification()
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "MediaSession onPlay failed", e)
                    }
                }

                override fun onPause() {
                    pausedByTransientFocus = false
                    if (isPlaying()) pauseInternal(updateFocus = true)
                }

                override fun onSkipToNext() = playNext()

                override fun onSkipToPrevious() = playPrevious()

                override fun onSeekTo(pos: Long) {
                    seekTo(pos.toInt())
                }

                override fun onStop() {
                    stopPlayback()
                }
            },
            mainHandler,
        )
        session.isActive = true
        mediaSession = session
        updateMediaSession(full = true)
    }

    private fun releaseMediaSession() {
        try {
            mediaSession?.isActive = false
            mediaSession?.release()
        } catch (_: Exception) {
            // ignore
        }
        mediaSession = null
        lastSessionMetaId = null
    }

    /** 曲目/播放状态变化时更新锁屏与媒体会话 */
    private fun updateMediaSession(full: Boolean = true) {
        val session = mediaSession ?: return
        val item = playlist.getOrNull(currentIndex)
        val playing = isPlaying()
        val position = try {
            mediaPlayer?.currentPosition?.toLong() ?: 0L
        } catch (_: Exception) {
            0L
        }
        val duration = try {
            mediaPlayer?.duration?.takeIf { it > 0 }?.toLong()
                ?: item?.durationMs?.coerceAtLeast(0L) ?: 0L
        } catch (_: Exception) {
            item?.durationMs?.coerceAtLeast(0L) ?: 0L
        }

        if (full || item?.id != lastSessionMetaId) {
            lastSessionMetaId = item?.id
            val metaBuilder = MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, item?.title ?: getString(R.string.now_playing_none))
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, item?.subtitle.orEmpty())
                .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, item?.folderPath?.trimEnd('/').orEmpty())
                .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, duration)
            session.setMetadata(metaBuilder.build())
            // 异步补封面到锁屏 / 通知
            if (item != null) {
                val trackId = item.id
                serviceScope.launch {
                    val art = com.whj.music.data.CoverArtLoader.load(this@MusicPlayerService, item)
                    if (art == null || lastSessionMetaId != trackId) return@launch
                    val withArt = MediaMetadataCompat.Builder()
                        .putString(MediaMetadataCompat.METADATA_KEY_TITLE, item.title)
                        .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, item.subtitle)
                        .putString(
                            MediaMetadataCompat.METADATA_KEY_ALBUM,
                            item.folderPath.trimEnd('/'),
                        )
                        .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, duration)
                        .putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, art)
                        .putBitmap(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON, art)
                        .build()
                    mediaSession?.setMetadata(withArt)
                    updateForegroundNotification()
                }
            }
        }

        val state = when {
            item == null -> PlaybackStateCompat.STATE_NONE
            playing -> PlaybackStateCompat.STATE_PLAYING
            else -> PlaybackStateCompat.STATE_PAUSED
        }
        val actions = (
            PlaybackStateCompat.ACTION_PLAY or
                PlaybackStateCompat.ACTION_PAUSE or
                PlaybackStateCompat.ACTION_PLAY_PAUSE or
                PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                PlaybackStateCompat.ACTION_SEEK_TO or
                PlaybackStateCompat.ACTION_STOP
            )
        session.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(actions)
                .setState(
                    state,
                    position,
                    if (playing) playbackSpeed else 0f,
                    SystemClock.elapsedRealtime(),
                )
                .build(),
        )
        lastSessionPosUpdateElapsed = SystemClock.elapsedRealtime()
    }

    private fun maybeUpdateMediaSessionPosition() {
        if (!isPlaying()) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastSessionPosUpdateElapsed < SESSION_POS_INTERVAL_MS) return
        updateMediaSession(full = false)
    }

    /** 通知栏 + 锁屏会话一并刷新（状态切换时用） */
    private fun updatePlaybackChrome() {
        updateForegroundNotification()
    }

    private fun updateForegroundNotification() {
        // 同步锁屏 MediaSession（曲目/播放状态）
        updateMediaSession(full = true)
        if (!isForeground) return
        val manager = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val item = playlist.getOrNull(currentIndex)
        val title = item?.title ?: getString(R.string.now_playing_none)
        val subtitle = item?.subtitle.orEmpty()
        val playing = isPlaying()

        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            pendingFlags(),
        )

        val mediaStyle = androidx.media.app.NotificationCompat.MediaStyle()
            .setShowActionsInCompactView(0, 1, 2)
            .setShowCancelButton(true)
            .setCancelButtonIntent(actionPendingIntent(ACTION_STOP, 4))
        mediaSession?.sessionToken?.let { mediaStyle.setMediaSession(it) }

        return NotificationCompat.Builder(this, MusicApp.CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(subtitle)
            .setSmallIcon(R.drawable.ic_music_note)
            .setContentIntent(contentIntent)
            .setOngoing(playing)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(
                R.drawable.ic_skip_previous,
                getString(R.string.previous),
                actionPendingIntent(ACTION_PREV, 1),
            )
            .addAction(
                if (playing) R.drawable.ic_pause else R.drawable.ic_play,
                getString(if (playing) R.string.pause else R.string.play),
                actionPendingIntent(ACTION_PLAY_PAUSE, 2),
            )
            .addAction(
                R.drawable.ic_skip_next,
                getString(R.string.next),
                actionPendingIntent(ACTION_NEXT, 3),
            )
            .setStyle(mediaStyle)
            .build()
    }

    private fun actionPendingIntent(action: String, requestCode: Int): PendingIntent {
        val intent = Intent(this, MusicPlayerService::class.java).setAction(action)
        return PendingIntent.getService(this, requestCode, intent, pendingFlags())
    }

    private fun pendingFlags(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
    }

    enum class UiRefreshMode {
        /** 歌词页：高刷新跟唱 */
        LYRICS,
        /** 界面可见：进度条即可 */
        FOREGROUND,
        /** 后台 / 无监听：仅维持定时关闭等 */
        BACKGROUND,
    }

    data class PlaybackState(
        val item: PlayableMedia?,
        val isPlaying: Boolean,
        val positionMs: Int,
        val durationMs: Int,
        val playlistSize: Int,
        val currentIndex: Int,
        val playMode: PlayMode,
        val folderPath: String,
        val sleepRemainingMs: Long = 0L,
        val playbackSpeed: Float = 1.0f,
    )

    /** 音量归一化快照（详细信息） */
    data class VolumeNormalizeInfo(
        val enabled: Boolean,
        val analyzing: Boolean,
        val trackRms: Float?,
        val appliedGain: Float,
        val targetRms: Float,
    )

    companion object {
        private const val TAG = "MusicPlayerService"
        const val ACTION_PLAY_PAUSE = "com.whj.music.action.PLAY_PAUSE"
        const val ACTION_NEXT = "com.whj.music.action.NEXT"
        const val ACTION_PREV = "com.whj.music.action.PREV"
        const val ACTION_STOP = "com.whj.music.action.STOP"
        /** 空闲自动关闭：保存进度后释放并 stopSelf */
        const val ACTION_AUTO_CLOSE = "com.whj.music.action.AUTO_CLOSE"
        private const val NOTIFICATION_ID = 1001
        /** 歌词页跟唱 */
        private const val INTERVAL_LYRICS_MS = 40L
        /** 前台进度条 */
        private const val INTERVAL_FOREGROUND_MS = 250L
        /** 后台有定时关闭 */
        private const val INTERVAL_BACKGROUND_MS = 1000L
        /** 后台仅播放、无定时：再省一点 */
        private const val INTERVAL_BACKGROUND_IDLE_MS = 2000L
        /** 锁屏进度刷新 */
        private const val SESSION_POS_INTERVAL_MS = 2000L

        /** 启动为前台服务，解绑 Activity 后仍可继续播放 */
        fun start(context: Context) {
            val intent = Intent(context, MusicPlayerService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }
    }
}
