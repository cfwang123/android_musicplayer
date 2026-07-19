package com.whj.music.data

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.LruCache
import com.whj.music.model.BrowseItem
import com.whj.music.model.BrowseItemType
import com.whj.music.model.PlayableMedia
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 从嵌入标签 / MediaStore 专辑图 / 同目录 cover 文件加载封面。
 * [maxEdge] 控制解码尺寸：列表缩略图用小边长，播放页用大图。
 */
object CoverArtLoader {
    const val EDGE_FULL = 1024
    const val EDGE_THUMB = 128

    private val cache = object : LruCache<String, Bitmap>(48) {
        override fun sizeOf(key: String, value: Bitmap): Int =
            (value.byteCount / 1024).coerceAtLeast(1)
    }

    private fun cacheKey(id: Long, uri: Uri?, maxEdge: Int): String =
        "$id|${uri}|e$maxEdge"

    fun peek(id: Long, uri: Uri?, maxEdge: Int = EDGE_THUMB): Bitmap? =
        cache.get(cacheKey(id, uri, maxEdge))

    suspend fun load(context: Context, media: PlayableMedia, maxEdge: Int = EDGE_FULL): Bitmap? =
        load(
            context = context,
            mediaId = media.id,
            uri = media.uri,
            filePath = media.filePath,
            isVideo = media.isVideo,
            maxEdge = maxEdge,
        )

    suspend fun loadBrowseItem(
        context: Context,
        item: BrowseItem,
        maxEdge: Int = EDGE_THUMB,
    ): Bitmap? {
        if (item.type != BrowseItemType.FILE || item.uri == null || item.mediaId == 0L) {
            return null
        }
        return load(
            context = context,
            mediaId = item.mediaId,
            uri = item.uri,
            filePath = item.filePath,
            isVideo = item.isVideo,
            maxEdge = maxEdge,
        )
    }

    suspend fun load(
        context: Context,
        mediaId: Long,
        uri: Uri,
        filePath: String?,
        isVideo: Boolean,
        maxEdge: Int = EDGE_FULL,
    ): Bitmap? = withContext(Dispatchers.IO) {
        val key = cacheKey(mediaId, uri, maxEdge)
        cache.get(key)?.let { return@withContext it }

        val bmp = loadEmbedded(context, uri, maxEdge)
            ?: loadFromFilePath(filePath, maxEdge)
            ?: loadMediaStoreAlbumArt(context, mediaId, isVideo, maxEdge)
            ?: loadSidecarCover(filePath, maxEdge)

        if (bmp != null) {
            cache.put(key, bmp)
        }
        bmp
    }

    fun clearCache() {
        cache.evictAll()
    }

    private fun loadEmbedded(context: Context, uri: Uri, maxEdge: Int): Bitmap? {
        val mmr = MediaMetadataRetriever()
        return try {
            mmr.setDataSource(context, uri)
            val bytes = mmr.embeddedPicture ?: return null
            decodeSampled(bytes, maxEdge)
        } catch (_: Exception) {
            try {
                context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                    mmr.setDataSource(pfd.fileDescriptor)
                    val bytes = mmr.embeddedPicture ?: return null
                    decodeSampled(bytes, maxEdge)
                }
            } catch (_: Exception) {
                null
            }
        } finally {
            try {
                mmr.release()
            } catch (_: Exception) {
            }
        }
    }

    private fun loadFromFilePath(path: String?, maxEdge: Int): Bitmap? {
        if (path.isNullOrBlank()) return null
        val file = File(path)
        if (!file.isFile) return null
        val mmr = MediaMetadataRetriever()
        return try {
            mmr.setDataSource(path)
            val bytes = mmr.embeddedPicture ?: return null
            decodeSampled(bytes, maxEdge)
        } catch (_: Exception) {
            null
        } finally {
            try {
                mmr.release()
            } catch (_: Exception) {
            }
        }
    }

    private fun loadMediaStoreAlbumArt(
        context: Context,
        mediaId: Long,
        isVideo: Boolean,
        maxEdge: Int,
    ): Bitmap? {
        if (isVideo) return null
        // 视频 id 带高位标记，音频为 MediaStore 原始 id
        val storeId = if (mediaId and FolderBrowser.VIDEO_ID_FLAG != 0L) {
            return null
        } else {
            mediaId
        }
        if (storeId <= 0L) return null
        return try {
            val albumId = queryAlbumId(context, storeId) ?: return null
            if (albumId <= 0L) return null
            val artUri = ContentUris.withAppendedId(
                Uri.parse("content://media/external/audio/albumart"),
                albumId,
            )
            context.contentResolver.openInputStream(artUri)?.use { input ->
                decodeSampled(input.readBytes(), maxEdge)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun queryAlbumId(context: Context, mediaStoreId: Long): Long? {
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        }
        val uri = ContentUris.withAppendedId(collection, mediaStoreId)
        return try {
            context.contentResolver.query(
                uri,
                arrayOf(MediaStore.Audio.Media.ALBUM_ID),
                null,
                null,
                null,
            )?.use { c ->
                if (c.moveToFirst()) {
                    val col = c.getColumnIndex(MediaStore.Audio.Media.ALBUM_ID)
                    if (col >= 0) c.getLong(col) else null
                } else {
                    null
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun loadSidecarCover(audioPath: String?, maxEdge: Int): Bitmap? {
        if (audioPath.isNullOrBlank()) return null
        val dir = File(audioPath).parentFile ?: return null
        val names = listOf(
            "cover.jpg", "cover.jpeg", "cover.png", "folder.jpg", "folder.png",
            "AlbumArt.jpg", "AlbumArtSmall.jpg", "front.jpg",
        )
        for (name in names) {
            val f = File(dir, name)
            if (f.isFile) {
                decodeFile(f, maxEdge)?.let { return it }
            }
        }
        val base = File(audioPath).nameWithoutExtension
        for (ext in listOf("jpg", "jpeg", "png")) {
            val f = File(dir, "$base.$ext")
            if (f.isFile) {
                decodeFile(f, maxEdge)?.let { return it }
            }
        }
        return null
    }

    private fun decodeFile(file: File, maxEdge: Int): Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            val opts = BitmapFactory.Options().apply {
                inSampleSize = calcInSampleSize(bounds.outWidth, bounds.outHeight, maxEdge)
            }
            BitmapFactory.decodeFile(file.absolutePath, opts)
        } catch (_: Exception) {
            null
        }
    }

    private fun decodeSampled(bytes: ByteArray, maxEdge: Int): Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            val opts = BitmapFactory.Options().apply {
                inSampleSize = calcInSampleSize(bounds.outWidth, bounds.outHeight, maxEdge)
            }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        } catch (_: Exception) {
            null
        }
    }

    private fun calcInSampleSize(w: Int, h: Int, maxEdge: Int): Int {
        if (w <= 0 || h <= 0 || maxEdge <= 0) return 1
        var sample = 1
        var halfW = w / 2
        var halfH = h / 2
        while (halfW / sample >= maxEdge && halfH / sample >= maxEdge) {
            sample *= 2
        }
        return sample.coerceAtLeast(1)
    }
}
