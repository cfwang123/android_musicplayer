package com.whj.music.ui

import android.app.Activity
import android.content.Context
import android.util.TypedValue
import androidx.annotation.AttrRes
import androidx.annotation.StyleRes
import com.whj.music.R
import com.whj.music.data.AppSettings

enum class AppThemeSkin(
    val key: String,
    @StyleRes val styleRes: Int,
    val labelRes: Int,
) {
    BLUE("blue", R.style.Theme_MusicPlayer, R.string.theme_blue),
    AMBER("amber", R.style.Theme_MusicPlayer_Amber, R.string.theme_amber),
    TEAL("teal", R.style.Theme_MusicPlayer_Teal, R.string.theme_teal),
    VIOLET("violet", R.style.Theme_MusicPlayer_Violet, R.string.theme_violet),
    DARK("dark", R.style.Theme_MusicPlayer_Dark, R.string.theme_dark),
    ;

    companion object {
        fun fromKey(key: String?): AppThemeSkin =
            entries.firstOrNull { it.key == key } ?: BLUE
    }
}

object AppTheme {
    fun apply(activity: Activity) {
        val skin = AppThemeSkin.fromKey(AppSettings.themeKey(activity))
        activity.setTheme(skin.styleRes)
    }

    fun resolveColor(context: Context, @AttrRes attr: Int, fallback: Int = 0): Int {
        val tv = TypedValue()
        return if (context.theme.resolveAttribute(attr, tv, true)) {
            if (tv.resourceId != 0) {
                context.getColor(tv.resourceId)
            } else {
                tv.data
            }
        } else {
            fallback
        }
    }
}
