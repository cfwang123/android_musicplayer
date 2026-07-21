package com.whj.music.data

import android.content.ContentResolver
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log

/**
 * MediaStore 查询兼容层。
 *
 * 不同 Android 版本 / OEM 对列支持不一致：
 * - [MediaStore.MediaColumns.RELATIVE_PATH] 仅 API 29+
 * - [MediaStore.MediaColumns.DATA]（_data）在部分机型上投影会直接抛
 *   `IllegalArgumentException: no such column: ...`
 *
 * 换机后首次全量扫描若无回退，启动即失败。
 */
object MediaStoreCompat {
    private const val TAG = "MediaStoreCompat"

    fun audioProjection(includeData: Boolean, includeRelativePath: Boolean): Array<String> {
        val cols = mutableListOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.IS_MUSIC,
        )
        if (includeRelativePath && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            cols += MediaStore.Audio.Media.RELATIVE_PATH
        }
        if (includeData) {
            cols += MediaStore.Audio.Media.DATA
        }
        return cols.toTypedArray()
    }

    fun videoProjection(includeData: Boolean, includeRelativePath: Boolean): Array<String> {
        val cols = mutableListOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.TITLE,
            MediaStore.Video.Media.DURATION,
        )
        if (includeRelativePath && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            cols += MediaStore.Video.Media.RELATIVE_PATH
        }
        if (includeData) {
            cols += MediaStore.Video.Media.DATA
        }
        // 不投影 Video.ARTIST：部分机型无此列
        return cols.toTypedArray()
    }

    /**
     * 按 id 批量解析时的投影（音频/视频共用基础列）。
     */
    fun mediaByIdProjection(
        isVideo: Boolean,
        includeData: Boolean,
        includeRelativePath: Boolean,
    ): Array<String> {
        val cols = mutableListOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.TITLE,
            MediaStore.MediaColumns.DURATION,
        )
        if (includeRelativePath && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            cols += MediaStore.MediaColumns.RELATIVE_PATH
        }
        if (includeData) {
            cols += MediaStore.MediaColumns.DATA
        }
        if (!isVideo) {
            cols += MediaStore.Audio.Media.ARTIST
            cols += MediaStore.Audio.Media.IS_MUSIC
        }
        return cols.toTypedArray()
    }

    /**
     * 带列回退的 query：优先完整列，失败则逐步去掉 DATA / RELATIVE_PATH。
     */
    fun query(
        resolver: ContentResolver,
        uri: Uri,
        projectionBuilder: (includeData: Boolean, includeRelativePath: Boolean) -> Array<String>,
        selection: String? = null,
        selectionArgs: Array<String>? = null,
        sortOrder: String? = null,
    ): Cursor? {
        val wantRelative = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        val attempts = linkedSetOf(
            true to wantRelative,
            false to wantRelative,
            true to false,
            false to false,
        )
        var lastError: Throwable? = null
        for ((includeData, includeRelative) in attempts) {
            try {
                val cursor = resolver.query(
                    uri,
                    projectionBuilder(includeData, includeRelative),
                    selection,
                    selectionArgs,
                    sortOrder,
                )
                if (cursor != null) return cursor
            } catch (e: IllegalArgumentException) {
                lastError = e
                Log.w(TAG, "query no-column data=$includeData rel=$includeRelative: ${e.message}")
            } catch (e: SecurityException) {
                throw e
            } catch (e: Exception) {
                lastError = e
                Log.w(TAG, "query fail data=$includeData rel=$includeRelative: ${e.message}")
            }
        }
        if (lastError != null) {
            Log.e(TAG, "MediaStore query exhausted for $uri", lastError)
        }
        return null
    }

    /**
     * 从 cursor 解析相对目录（统一末尾 `/`）。
     * 优先 RELATIVE_PATH；否则从 DATA 绝对路径推导。
     */
    fun readRelativeFolder(cursor: Cursor, relativeCol: Int, dataCol: Int): String {
        if (relativeCol >= 0) {
            val rel = cursor.getString(relativeCol)
            if (!rel.isNullOrBlank()) {
                return FolderBrowser.normalizeFolder(rel)
            }
        }
        if (dataCol >= 0) {
            val data = cursor.getString(dataCol)
            if (!data.isNullOrBlank()) {
                return absolutePathToRelativeFolder(data)
            }
        }
        return ""
    }

    /** 绝对路径 → MediaStore 风格相对目录（含末尾 `/`）。 */
    fun absolutePathToRelativeFolder(absolutePath: String): String {
        var path = absolutePath.replace('\\', '/')
        val prefixes = listOf(
            "/storage/emulated/0/",
            "/sdcard/",
            "/mnt/sdcard/",
            "/storage/sdcard0/",
        )
        var stripped = false
        for (p in prefixes) {
            if (path.startsWith(p, ignoreCase = true)) {
                path = path.substring(p.length)
                stripped = true
                break
            }
        }
        if (!stripped && path.startsWith("/storage/", ignoreCase = true)) {
            // /storage/XXXX-XXXX/Music/a.mp3 → Music/a.mp3
            val rest = path.substring("/storage/".length)
            val slash = rest.indexOf('/')
            if (slash in 0 until rest.lastIndex) {
                path = rest.substring(slash + 1)
                stripped = true
            }
        }
        if (!stripped) {
            // 无法识别卷前缀时不当作有效相对路径，避免污染目录树
            return ""
        }
        val lastSlash = path.lastIndexOf('/')
        val folder = if (lastSlash >= 0) path.substring(0, lastSlash + 1) else ""
        return FolderBrowser.normalizeFolder(folder)
    }
}
