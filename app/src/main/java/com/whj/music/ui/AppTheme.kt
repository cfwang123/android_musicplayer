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
    ROSE("rose", R.style.Theme_MusicPlayer_Rose, R.string.theme_rose),
    INDIGO("indigo", R.style.Theme_MusicPlayer_Indigo, R.string.theme_indigo),
    LIME("lime", R.style.Theme_MusicPlayer_Lime, R.string.theme_lime),
    CORAL("coral", R.style.Theme_MusicPlayer_Coral, R.string.theme_coral),
    PINK("pink", R.style.Theme_MusicPlayer_Pink, R.string.theme_pink),
    CYAN("cyan", R.style.Theme_MusicPlayer_Cyan, R.string.theme_cyan),
    FOREST("forest", R.style.Theme_MusicPlayer_Forest, R.string.theme_forest),
    WINE("wine", R.style.Theme_MusicPlayer_Wine, R.string.theme_wine),
    GOLD("gold", R.style.Theme_MusicPlayer_Gold, R.string.theme_gold),
    SLATE("slate", R.style.Theme_MusicPlayer_Slate, R.string.theme_slate),
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
