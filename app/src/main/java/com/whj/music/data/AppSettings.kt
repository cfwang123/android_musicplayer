package com.whj.music.data

import android.content.Context
import com.whj.music.model.PlayMode

/** 应用参数设置（设置界面读写） */
object AppSettings {
    private const val PREFS = "music_player_prefs"

    private const val KEY_AUTO_LOCATE = "set_auto_locate"
    private const val KEY_RESUME_ON_START = "set_resume_on_start"
    private const val KEY_DEFAULT_SPEED = "set_default_speed"
    private const val KEY_DEFAULT_MODE = "set_default_mode"
    private const val KEY_LYRIC_SEEK_MAX_MIN = "set_lyric_seek_max_min"
    private const val KEY_KEEP_SPEED = "set_keep_speed"
    private const val KEY_THEME = "set_theme"
    private const val KEY_ROOT_FOLDERS = "set_root_folders"
    private const val KEY_RESUME_FOLDER_ON_OPEN = "set_resume_folder_on_open"
    private const val KEY_EQ_ENABLED = "set_eq_enabled"
    private const val KEY_EQ_PRESET = "set_eq_preset"
    private const val KEY_EQ_BANDS = "set_eq_bands"
    /** system / zh / en */
    private const val KEY_LANGUAGE = "set_language"

    fun autoLocateOnBrowse(context: Context): Boolean =
        prefs(context).getBoolean(KEY_AUTO_LOCATE, true)

    fun setAutoLocateOnBrowse(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_AUTO_LOCATE, value).apply()
    }

    fun resumeOnStart(context: Context): Boolean =
        prefs(context).getBoolean(KEY_RESUME_ON_START, true)

    fun setResumeOnStart(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_RESUME_ON_START, value).apply()
    }

    /** 打开文件夹时，自动恢复该文件夹上次播放的曲目与进度 */
    fun resumeFolderOnOpen(context: Context): Boolean =
        prefs(context).getBoolean(KEY_RESUME_FOLDER_ON_OPEN, true)

    fun setResumeFolderOnOpen(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_RESUME_FOLDER_ON_OPEN, value).apply()
    }

    fun keepSpeedAcrossTracks(context: Context): Boolean =
        prefs(context).getBoolean(KEY_KEEP_SPEED, true)

    fun setKeepSpeedAcrossTracks(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_KEEP_SPEED, value).apply()
    }

    fun defaultPlaybackSpeed(context: Context): Float =
        prefs(context).getFloat(KEY_DEFAULT_SPEED, 1.0f)

    fun setDefaultPlaybackSpeed(context: Context, value: Float) {
        prefs(context).edit().putFloat(KEY_DEFAULT_SPEED, value).apply()
    }

    fun defaultPlayMode(context: Context): PlayMode {
        val name = prefs(context).getString(KEY_DEFAULT_MODE, PlayMode.REPEAT_FOLDER.name)
        return runCatching { PlayMode.valueOf(name ?: PlayMode.REPEAT_FOLDER.name) }
            .getOrDefault(PlayMode.REPEAT_FOLDER)
    }

    fun setDefaultPlayMode(context: Context, mode: PlayMode) {
        prefs(context).edit().putString(KEY_DEFAULT_MODE, mode.name).apply()
    }

    /** 有歌词且时长小于该分钟数时，±60s 变为上一句/下一句 */
    fun lyricSentenceSeekMaxMinutes(context: Context): Int =
        prefs(context).getInt(KEY_LYRIC_SEEK_MAX_MIN, 10).coerceIn(1, 60)

    fun setLyricSentenceSeekMaxMinutes(context: Context, minutes: Int) {
        prefs(context).edit().putInt(KEY_LYRIC_SEEK_MAX_MIN, minutes.coerceIn(1, 60)).apply()
    }

    /** 主题皮肤 key：blue / amber / teal / violet / dark，默认 blue */
    fun themeKey(context: Context): String =
        prefs(context).getString(KEY_THEME, "blue") ?: "blue"

    fun setThemeKey(context: Context, key: String) {
        prefs(context).edit().putString(KEY_THEME, key).apply()
    }

    /**
     * 1 屏根目录主目录列表（MediaStore 相对路径，normalize 后）。
     * 空列表 = 不限制，与原先全盘浏览一致。
     */
    fun rootFolders(context: Context): List<String> {
        val raw = prefs(context).getString(KEY_ROOT_FOLDERS, "") ?: ""
        if (raw.isBlank()) return emptyList()
        return raw.split('\n')
            .map { FolderBrowser.normalizeFolder(it) }
            .filter { it.isNotEmpty() }
            .distinct()
    }

    fun setRootFolders(context: Context, folders: List<String>) {
        val normalized = folders
            .map { FolderBrowser.normalizeFolder(it) }
            .filter { it.isNotEmpty() }
            .distinct()
        prefs(context).edit()
            .putString(KEY_ROOT_FOLDERS, normalized.joinToString("\n"))
            .apply()
    }

    fun addRootFolder(context: Context, path: String) {
        val n = FolderBrowser.normalizeFolder(path)
        if (n.isEmpty()) return
        val list = rootFolders(context).toMutableList()
        if (list.none { it.equals(n, ignoreCase = true) }) {
            list += n
            setRootFolders(context, list)
        }
    }

    fun removeRootFolder(context: Context, path: String) {
        val n = FolderBrowser.normalizeFolder(path)
        setRootFolders(context, rootFolders(context).filterNot { it.equals(n, ignoreCase = true) })
    }

    /** 均衡器：默认关闭 */
    fun eqEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_EQ_ENABLED, false)

    fun setEqEnabled(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_EQ_ENABLED, value).apply()
    }

    /** 系统预设下标；-1 表示自定义频段 */
    fun eqPresetIndex(context: Context): Int =
        prefs(context).getInt(KEY_EQ_PRESET, -1)

    fun setEqPresetIndex(context: Context, index: Int) {
        prefs(context).edit().putInt(KEY_EQ_PRESET, index).apply()
    }

    fun eqBandLevels(context: Context): List<Int> {
        val raw = prefs(context).getString(KEY_EQ_BANDS, "") ?: return emptyList()
        if (raw.isBlank()) return emptyList()
        return raw.split(',').mapNotNull { it.trim().toIntOrNull() }
    }

    fun setEqBandLevels(context: Context, levels: List<Int>) {
        prefs(context).edit().putString(KEY_EQ_BANDS, levels.joinToString(",")).apply()
    }

    /** 语言：system / zh / en，默认跟随系统 */
    fun languageKey(context: Context): String =
        prefs(context).getString(KEY_LANGUAGE, "system") ?: "system"

    fun setLanguageKey(context: Context, key: String) {
        prefs(context).edit().putString(KEY_LANGUAGE, key).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
