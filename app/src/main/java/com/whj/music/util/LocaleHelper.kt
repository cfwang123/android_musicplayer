package com.whj.music.util

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.whj.music.data.AppSettings

object LocaleHelper {
    const val SYSTEM = "system"
    const val ZH = "zh"
    const val EN = "en"

    fun applyFromSettings(context: Context) {
        apply(AppSettings.languageKey(context))
    }

    fun apply(languageKey: String) {
        val locales = when (languageKey) {
            ZH -> LocaleListCompat.forLanguageTags("zh-CN")
            EN -> LocaleListCompat.forLanguageTags("en")
            else -> LocaleListCompat.getEmptyLocaleList()
        }
        AppCompatDelegate.setApplicationLocales(locales)
    }
}
