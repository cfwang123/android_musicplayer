package com.whj.music.data

import android.content.Context
import android.net.Uri
import com.whj.music.model.BrowseItem
import com.whj.music.model.BrowseItemType
import com.whj.music.model.PlayableMedia
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** 用户播放列表持久化（SharedPreferences + JSON） */
object PlaylistStore {
    private const val PREFS = "music_player_prefs"
    private const val KEY = "user_playlists_v1"

    data class Track(
        val id: Long,
        val uri: String,
        val title: String,
        val subtitle: String,
        val durationMs: Long,
        val isVideo: Boolean,
        val folderPath: String,
        val filePath: String?,
    ) {
        fun toPlayable(): PlayableMedia? {
            val parsed = runCatching { Uri.parse(uri) }.getOrNull() ?: return null
            return PlayableMedia(
                id = id,
                title = title,
                subtitle = subtitle,
                durationMs = durationMs,
                uri = parsed,
                isVideo = isVideo,
                folderPath = folderPath,
                filePath = filePath,
            )
        }

        fun toBrowseItem(listFolderPath: String): BrowseItem {
            val parsed = runCatching { Uri.parse(uri) }.getOrNull()
            return BrowseItem(
                type = BrowseItemType.FILE,
                name = title,
                folderPath = listFolderPath,
                uri = parsed,
                mediaId = id,
                durationMs = durationMs,
                isVideo = isVideo,
                subtitle = subtitle,
                filePath = filePath,
            )
        }

        fun toJson(): JSONObject = JSONObject().apply {
            put("id", id)
            put("uri", uri)
            put("title", title)
            put("subtitle", subtitle)
            put("durationMs", durationMs)
            put("isVideo", isVideo)
            put("folderPath", folderPath)
            put("filePath", filePath ?: JSONObject.NULL)
        }

        companion object {
            fun fromJson(o: JSONObject): Track = Track(
                id = o.optLong("id", 0L),
                uri = o.optString("uri", ""),
                title = o.optString("title", ""),
                subtitle = o.optString("subtitle", ""),
                durationMs = o.optLong("durationMs", 0L),
                isVideo = o.optBoolean("isVideo", false),
                folderPath = o.optString("folderPath", ""),
                filePath = if (o.isNull("filePath")) {
                    null
                } else {
                    o.optString("filePath").takeIf { it.isNotBlank() && it != "null" }
                },
            )

            fun fromBrowseItem(item: BrowseItem): Track? {
                if (item.type != BrowseItemType.FILE || item.uri == null || item.mediaId == 0L) return null
                return Track(
                    id = item.mediaId,
                    uri = item.uri.toString(),
                    title = item.name,
                    subtitle = item.subtitle,
                    durationMs = item.durationMs,
                    isVideo = item.isVideo,
                    folderPath = item.folderPath,
                    filePath = item.filePath,
                )
            }

            fun fromPlayable(media: PlayableMedia): Track = Track(
                id = media.id,
                uri = media.uri.toString(),
                title = media.title,
                subtitle = media.subtitle,
                durationMs = media.durationMs,
                isVideo = media.isVideo,
                folderPath = media.folderPath,
                filePath = media.filePath,
            )
        }
    }

    data class Playlist(
        val id: String,
        val name: String,
        val tracks: List<Track>,
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("id", id)
            put("name", name)
            put("tracks", JSONArray().also { arr ->
                tracks.forEach { arr.put(it.toJson()) }
            })
        }

        companion object {
            fun fromJson(o: JSONObject): Playlist {
                val tracksArr = o.optJSONArray("tracks") ?: JSONArray()
                val tracks = buildList {
                    for (i in 0 until tracksArr.length()) {
                        val t = tracksArr.optJSONObject(i) ?: continue
                        add(Track.fromJson(t))
                    }
                }
                return Playlist(
                    id = o.optString("id", UUID.randomUUID().toString()),
                    name = o.optString("name", ""),
                    tracks = tracks,
                )
            }
        }
    }

    fun list(context: Context): List<Playlist> = loadAll(context)

    fun get(context: Context, id: String): Playlist? =
        loadAll(context).firstOrNull { it.id == id }

    fun create(context: Context, name: String): Playlist {
        val trimmed = name.trim().ifBlank { "Playlist" }
        val pl = Playlist(id = UUID.randomUUID().toString(), name = trimmed, tracks = emptyList())
        val all = loadAll(context).toMutableList()
        all.add(pl)
        saveAll(context, all)
        return pl
    }

    fun rename(context: Context, id: String, newName: String): Boolean {
        val trimmed = newName.trim()
        if (trimmed.isEmpty()) return false
        val all = loadAll(context).toMutableList()
        val idx = all.indexOfFirst { it.id == id }
        if (idx < 0) return false
        all[idx] = all[idx].copy(name = trimmed)
        saveAll(context, all)
        return true
    }

    fun delete(context: Context, id: String): Boolean {
        val all = loadAll(context).toMutableList()
        val removed = all.removeAll { it.id == id }
        if (removed) saveAll(context, all)
        return removed
    }

    /** 追加曲目；同 mediaId 已存在则跳过。返回实际新增数量 */
    fun addTracks(context: Context, playlistId: String, tracks: List<Track>): Int {
        if (tracks.isEmpty()) return 0
        val all = loadAll(context).toMutableList()
        val idx = all.indexOfFirst { it.id == playlistId }
        if (idx < 0) return 0
        val existing = all[idx]
        val ids = existing.tracks.map { it.id }.toMutableSet()
        val merged = existing.tracks.toMutableList()
        var added = 0
        for (t in tracks) {
            if (t.id == 0L) continue
            if (ids.contains(t.id)) continue
            ids.add(t.id)
            merged.add(t)
            added++
        }
        if (added > 0) {
            all[idx] = existing.copy(tracks = merged)
            saveAll(context, all)
        }
        return added
    }

    fun removeTracks(context: Context, playlistId: String, mediaIds: Set<Long>): Int {
        if (mediaIds.isEmpty()) return 0
        val all = loadAll(context).toMutableList()
        val idx = all.indexOfFirst { it.id == playlistId }
        if (idx < 0) return 0
        val existing = all[idx]
        val filtered = existing.tracks.filterNot { it.id in mediaIds }
        val removed = existing.tracks.size - filtered.size
        if (removed > 0) {
            all[idx] = existing.copy(tracks = filtered)
            saveAll(context, all)
        }
        return removed
    }

    /** 拖动排序：from 索引移到 to 索引 */
    fun moveTrack(context: Context, playlistId: String, fromIndex: Int, toIndex: Int): Boolean {
        val all = loadAll(context).toMutableList()
        val idx = all.indexOfFirst { it.id == playlistId }
        if (idx < 0) return false
        val tracks = all[idx].tracks.toMutableList()
        if (fromIndex !in tracks.indices || toIndex !in tracks.indices) return false
        if (fromIndex == toIndex) return true
        val item = tracks.removeAt(fromIndex)
        tracks.add(toIndex, item)
        all[idx] = all[idx].copy(tracks = tracks)
        saveAll(context, all)
        return true
    }

    fun setTrackOrder(context: Context, playlistId: String, orderedIds: List<Long>): Boolean {
        val all = loadAll(context).toMutableList()
        val idx = all.indexOfFirst { it.id == playlistId }
        if (idx < 0) return false
        val map = all[idx].tracks.associateBy { it.id }
        val ordered = orderedIds.mapNotNull { map[it] }
        // 保留未出现在 orderedIds 中的尾部
        val rest = all[idx].tracks.filter { it.id !in orderedIds.toSet() }
        all[idx] = all[idx].copy(tracks = ordered + rest)
        saveAll(context, all)
        return true
    }

    private fun loadAll(context: Context): List<Playlist> {
        val raw = prefs(context).getString(KEY, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    add(Playlist.fromJson(o))
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun saveAll(context: Context, list: List<Playlist>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        prefs(context).edit().putString(KEY, arr.toString()).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

/** 虚拟路径：播放列表空间 */
object PlaylistPaths {
    const val ROOT = "__playlists__/"
    /** 固定入口：收藏歌曲（非用户自建列表） */
    const val FAVORITES_ID = "__favorites__"
    /** 固定入口：播放记录（有记录的真实文件夹列表） */
    const val HISTORY_ID = "__history__"

    fun normalize(path: String): String = FolderBrowser.normalizeFolder(path)

    fun isInPlaylistSpace(path: String): Boolean {
        val n = normalize(path)
        return n == ROOT || n.startsWith(ROOT)
    }

    fun isPlaylistsRoot(path: String): Boolean = normalize(path) == ROOT

    fun favoritesPath(): String = ofPlaylist(FAVORITES_ID)

    fun isFavorites(path: String): Boolean = playlistIdOf(path) == FAVORITES_ID

    fun historyPath(): String = ofPlaylist(HISTORY_ID)

    fun isHistory(path: String): Boolean = playlistIdOf(path) == HISTORY_ID

    /** 当前是否在某个具体播放列表内（非列表总览；含收藏歌曲 / 播放记录） */
    fun playlistIdOf(path: String): String? {
        val n = normalize(path)
        if (!n.startsWith(ROOT) || n == ROOT) return null
        val rest = n.removePrefix(ROOT).trim('/')
        if (rest.isEmpty() || rest.contains('/')) return null
        return rest
    }

    /** 用户自建列表（不含「收藏歌曲」「播放记录」） */
    fun isUserPlaylist(path: String): Boolean {
        val id = playlistIdOf(path) ?: return false
        return id != FAVORITES_ID && id != HISTORY_ID
    }

    fun ofPlaylist(id: String): String = ROOT + id.trim('/') + "/"
}
