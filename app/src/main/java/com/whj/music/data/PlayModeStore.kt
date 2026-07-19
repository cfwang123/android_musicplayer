package com.whj.music.data

import android.content.Context
import com.whj.music.model.PlayMode

object PlayModeStore {
    private const val PREFS = "music_player_prefs"
    private const val KEY_MODE = "play_mode"

    fun load(context: Context): PlayMode {
        val name = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_MODE, PlayMode.REPEAT_FOLDER.name)
        return runCatching { PlayMode.valueOf(name ?: PlayMode.REPEAT_FOLDER.name) }
            .getOrDefault(PlayMode.REPEAT_FOLDER)
    }

    fun save(context: Context, mode: PlayMode) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_MODE, mode.name)
            .apply()
    }
}
