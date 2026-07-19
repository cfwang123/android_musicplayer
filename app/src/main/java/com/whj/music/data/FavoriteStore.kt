package com.whj.music.data

import android.content.Context
import org.json.JSONArray

/**
 * 收藏：媒体 id（BrowseItem.mediaId / PlayableMedia.id）有序；
 * 文件夹用 "f:" + normalizeFolder 作为 key。
 */
object FavoriteStore {
    private const val PREFS = "music_player_prefs"
    private const val KEY = "favorites"
    /** 媒体收藏顺序（JSON 数组，元素为 mediaId 字符串） */
    private const val KEY_MEDIA_ORDER = "favorites_media_order_v1"
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
        val key = mediaId.toString()
        val set = ids(context).toMutableSet()
        val order = mediaOrderList(context).toMutableList()
        val nowFavorite = if (set.contains(key)) {
            set.remove(key)
            order.removeAll { it == mediaId }
            false
        } else {
            set.add(key)
            if (mediaId !in order) order.add(mediaId)
            true
        }
        saveIds(context, set)
        saveMediaOrder(context, order)
        return nowFavorite
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
        saveIds(context, set)
        return nowFavorite
    }

    fun allKeys(context: Context): Set<String> = ids(context)

    /** 已收藏媒体 id（按用户排序；兼容旧版无序 Set） */
    fun mediaIdsOrdered(context: Context): List<Long> {
        val set = ids(context)
        val mediaInSet = set.mapNotNull { k ->
            if (k.startsWith(FOLDER_PREFIX)) null else k.toLongOrNull()
        }.filter { it != 0L }.toSet()
        val order = mediaOrderList(context).filter { it in mediaInSet }
        val missing = mediaInSet - order.toSet()
        return order + missing.sorted()
    }

    fun mediaCount(context: Context): Int = mediaIdsOrdered(context).size

    fun removeMediaIds(context: Context, mediaIds: Set<Long>): Int {
        if (mediaIds.isEmpty()) return 0
        val set = ids(context).toMutableSet()
        var removed = 0
        for (id in mediaIds) {
            if (set.remove(id.toString())) removed++
        }
        val order = mediaOrderList(context).filterNot { it in mediaIds }
        saveIds(context, set)
        saveMediaOrder(context, order)
        return removed
    }

    fun setMediaOrder(context: Context, orderedIds: List<Long>) {
        val set = ids(context).toMutableSet()
        // 同步 set 与顺序：顺序中的 id 必须在收藏内
        val cleaned = orderedIds.filter { it != 0L && set.contains(it.toString()) }
        val rest = set.mapNotNull { k ->
            if (k.startsWith(FOLDER_PREFIX)) null else k.toLongOrNull()
        }.filter { it != 0L && it !in cleaned.toSet() }
        saveMediaOrder(context, cleaned + rest)
    }

    private fun mediaOrderList(context: Context): List<Long> {
        val raw = prefs(context).getString(KEY_MEDIA_ORDER, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val v = arr.optLong(i, 0L)
                    if (v != 0L) add(v)
                    else {
                        arr.optString(i).toLongOrNull()?.takeIf { it != 0L }?.let { add(it) }
                    }
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun saveMediaOrder(context: Context, order: List<Long>) {
        val arr = JSONArray()
        order.forEach { arr.put(it) }
        prefs(context).edit().putString(KEY_MEDIA_ORDER, arr.toString()).apply()
    }

    private fun folderKey(folderPath: String): String? {
        val n = FolderBrowser.normalizeFolder(folderPath)
        if (n.isEmpty()) return null
        return FOLDER_PREFIX + n
    }

    private fun ids(context: Context): Set<String> {
        return prefs(context).getStringSet(KEY, emptySet())?.toSet() ?: emptySet()
    }

    private fun saveIds(context: Context, set: Set<String>) {
        prefs(context).edit().putStringSet(KEY, set).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
