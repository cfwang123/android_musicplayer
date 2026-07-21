package com.whj.music.util

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.view.WindowManager
import android.widget.Toast
import com.whj.music.R
import com.whj.music.data.AppSettings
import com.whj.music.player.MusicPlayerService
import java.lang.ref.WeakReference

/**
 * UI 无活动自动关闭：超时后停止播放并退出进程。
 *
 * - 触摸 / [onActivityResumed] / 屏幕常亮（FLAG_KEEP_SCREEN_ON）算有活动，重置计时
 * - 后台、锁屏播放时继续累计空闲时间
 * - 时长由 [AppSettings.autoCloseMinutes] 配置，默认 2 小时；0=不关闭
 */
class IdleAutoCloser(private val app: Application) : Application.ActivityLifecycleCallbacks {

    private val handler = Handler(Looper.getMainLooper())
    /** 按最近 resume 顺序，末项为当前顶层 */
    private val activities = ArrayList<Activity>()
    private var lastUiActivityElapsed: Long = SystemClock.elapsedRealtime()
    private var keepScreenActive: Boolean = false
    private var closing: Boolean = false
    private var started: Boolean = false

    private val checkRunnable = object : Runnable {
        override fun run() {
            if (!closing) {
                checkKeepScreenFromTop()
                maybeAutoClose()
            }
            if (started && !closing) {
                handler.postDelayed(this, CHECK_INTERVAL_MS)
            }
        }
    }

    private val keepScreenBumpRunnable = object : Runnable {
        override fun run() {
            if (closing) return
            if (keepScreenActive) {
                onUiActivity()
                handler.postDelayed(this, KEEP_SCREEN_BUMP_MS)
            }
        }
    }

    fun start() {
        if (started) return
        started = true
        lastUiActivityElapsed = SystemClock.elapsedRealtime()
        app.registerActivityLifecycleCallbacks(this)
        handler.removeCallbacks(checkRunnable)
        handler.postDelayed(checkRunnable, CHECK_INTERVAL_MS)
    }

    fun stop() {
        started = false
        handler.removeCallbacks(checkRunnable)
        handler.removeCallbacks(keepScreenBumpRunnable)
        try {
            app.unregisterActivityLifecycleCallbacks(this)
        } catch (_: Exception) {
            // ignore
        }
    }

    /** 触摸等 UI 交互时调用，重置空闲计时 */
    fun onUiActivity() {
        if (closing) return
        lastUiActivityElapsed = SystemClock.elapsedRealtime()
    }

    /**
     * 屏幕常亮开启时视为持续有活动（不累计空闲）。
     * 由生命周期回调根据顶层 Activity 的 FLAG_KEEP_SCREEN_ON 更新。
     */
    fun setKeepScreenActive(active: Boolean) {
        if (keepScreenActive == active) {
            if (active) onUiActivity()
            return
        }
        keepScreenActive = active
        handler.removeCallbacks(keepScreenBumpRunnable)
        if (active) {
            onUiActivity()
            handler.post(keepScreenBumpRunnable)
        }
    }

    private fun maybeAutoClose() {
        val minutes = AppSettings.autoCloseMinutes(app)
        if (minutes <= 0) return
        if (keepScreenActive) {
            lastUiActivityElapsed = SystemClock.elapsedRealtime()
            return
        }
        val idleMs = SystemClock.elapsedRealtime() - lastUiActivityElapsed
        if (idleMs < minutes * 60_000L) return
        performAutoClose()
    }

    private fun performAutoClose() {
        if (closing) return
        closing = true
        handler.removeCallbacks(checkRunnable)
        handler.removeCallbacks(keepScreenBumpRunnable)

        try {
            Toast.makeText(app, R.string.auto_close_exiting, Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {
            // ignore
        }

        // 保存进度并释放播放服务
        try {
            val intent = Intent(app, MusicPlayerService::class.java)
                .setAction(MusicPlayerService.ACTION_AUTO_CLOSE)
            app.startService(intent)
        } catch (_: Exception) {
            try {
                androidx.core.content.ContextCompat.startForegroundService(
                    app,
                    Intent(app, MusicPlayerService::class.java)
                        .setAction(MusicPlayerService.ACTION_AUTO_CLOSE),
                )
            } catch (_: Exception) {
                // service may already be gone
            }
        }

        // 结束所有界面
        for (activity in activities.toList()) {
            try {
                activity.finishAndRemoveTask()
            } catch (_: Exception) {
                try {
                    activity.finish()
                } catch (_: Exception) {
                    // ignore
                }
            }
        }

        // 稍候确保服务收尾后退出进程
        handler.postDelayed({
            try {
                app.stopService(Intent(app, MusicPlayerService::class.java))
            } catch (_: Exception) {
                // ignore
            }
            Process.killProcess(Process.myPid())
            System.exit(0)
        }, EXIT_DELAY_MS)
    }

    private fun checkKeepScreenFromTop() {
        val top = activities.lastOrNull() ?: run {
            setKeepScreenActive(false)
            return
        }
        val keepOn = try {
            (top.window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) != 0
        } catch (_: Exception) {
            false
        }
        // 仅前台可见且设了常亮时算有活动
        setKeepScreenActive(keepOn && !top.isFinishing)
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
        if (!activities.contains(activity)) activities.add(activity)
    }

    override fun onActivityStarted(activity: Activity) {
        if (!activities.contains(activity)) activities.add(activity)
    }

    override fun onActivityResumed(activity: Activity) {
        activities.remove(activity)
        activities.add(activity)
        // OnResume 算有活动
        onUiActivity()
        checkKeepScreenFromTop()
    }

    override fun onActivityPaused(activity: Activity) {
        // 进入后台 / 锁屏：不再因本 Activity 常亮而挂起计时
        if (activities.lastOrNull() === activity) {
            setKeepScreenActive(false)
        }
    }

    override fun onActivityStopped(activity: Activity) = Unit

    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

    override fun onActivityDestroyed(activity: Activity) {
        activities.remove(activity)
    }

    companion object {
        private const val CHECK_INTERVAL_MS = 30_000L
        private const val KEEP_SCREEN_BUMP_MS = 60_000L
        private const val EXIT_DELAY_MS = 400L

        @Volatile
        private var instanceRef: WeakReference<IdleAutoCloser>? = null

        fun get(context: Context): IdleAutoCloser? {
            instanceRef?.get()?.let { return it }
            val app = context.applicationContext as? Application ?: return null
            // 正常由 MusicApp 注入；兜底
            return (app as? com.whj.music.MusicApp)?.idleAutoCloser
        }

        internal fun attach(closer: IdleAutoCloser) {
            instanceRef = WeakReference(closer)
        }

        /** 任意处上报 UI 活动（触摸等） */
        fun notifyUiActivity(context: Context) {
            get(context)?.onUiActivity()
        }
    }
}
