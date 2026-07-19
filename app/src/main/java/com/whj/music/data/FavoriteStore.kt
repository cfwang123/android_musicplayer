package com.whj.music.data

import android.content.Context

/**
 * 收藏的媒体 id（BrowseItem.mediaId / PlayableMedia.id）。
 * 文件夹用 "f:" + normalizeFolder 作为 key。
 */
object FavoriteStore {
    private const val PREFS = "music_player_prefs"
    private const val KEY = "favorites"
    private const val FOLDER_PREFIX = "f:"

    fun isFavoriteMedia(context: Context, mediaId: Long): Boolean {
        if (mediaId == 0L) return false
        return ids(context).contains(mediaId.toString())
    }

    fun isFavoriteFolder(context: Context, folderPath: String): Boolean {
        val key = folderKey(folderPath)
        if (key == null) return false
        return ids(context).contains(key)
    }

    fun toggleMedia(context: Context, mediaId: Long): Boolean {
        if (mediaId == 0L) return false
        return toggle(context, mediaId.toString())
    }

    fun toggleFolder(context: Context, folderPath: String): Boolean {
        val key = folderKey(folderPath) ?: return false
        return toggle(context, key)
    }

    /** @return 切换后是否为已收藏 */
    private fun toggle(context: Context, key: String): Boolean {
        val set = ids(context).toMutableSet()
        val nowFavorite = if (set.contains(key)) {
            set.remove(key)
            false
        } else {
            set.add(key)
            true
        }
        prefs(context).edit().putStringSet(KEY, set).apply()
        return nowFavorite
    }

    fun allKeys(context: Context): Set<String> = ids(context)

    private fun folderKey(folderPath: String): String? {
        val n = FolderBrowser.normalizeFolder(folderPath)
        if (n.isEmpty()) return null
        return FOLDER_PREFIX + n
    }

    private fun ids(context: Context): Set<String> {
        return prefs(context).getStringSet(KEY, emptySet())?.toSet() ?: emptySet()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
