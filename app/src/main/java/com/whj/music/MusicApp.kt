package com.whj.music

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.whj.music.util.IdleAutoCloser
import com.whj.music.util.LocaleHelper

class MusicApp : Application() {
    lateinit var idleAutoCloser: IdleAutoCloser
        private set

    override fun onCreate() {
        super.onCreate()
        LocaleHelper.applyFromSettings(this)
        createNotificationChannel()
        idleAutoCloser = IdleAutoCloser(this).also {
            IdleAutoCloser.attach(it)
            it.start()
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.notification_channel_desc)
            setShowBadge(false)
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    companion object {
        const val CHANNEL_ID = "music_playback"
    }
}
