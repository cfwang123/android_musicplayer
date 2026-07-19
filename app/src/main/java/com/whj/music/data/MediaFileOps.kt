package com.whj.music.data

import android.app.PendingIntent
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.whj.music.model.BrowseItem
import com.whj.music.model.BrowseItemType
import java.io.File

/** 媒体文件重命名 / 删除（MediaStore + 可选同目录 .lrc） */
object MediaFileOps {

    data class Result(
        val ok: Boolean,
        val message: String? = null,
        /** Android 11+ 需要用户确认删除时返回 */
        val userConfirmPendingIntent: PendingIntent? = null,
    )

    fun buildDisplayName(originalName: String, newBaseName: String): String {
        val trimmed = newBaseName.trim()
        if (trimmed.isEmpty()) return originalName
        val dot = originalName.lastIndexOf('.')
        val ext = if (dot > 0 && dot < originalName.lastIndex) {
            originalName.substring(dot)
        } else {
            ""
        }
        return if (ext.isNotEmpty() && !trimmed.endsWith(ext, ignoreCase = true)) {
            trimmed + ext
        } else {
            trimmed
        }
    }

    fun rename(context: Context, item: BrowseItem, newBaseName: String): Result {
        if (item.type != BrowseItemType.FILE || item.uri == null) {
            return Result(false, "invalid")
        }
        val newName = buildDisplayName(item.name, newBaseName)
        if (newName == item.name) return Result(true)

        return try {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, newName)
            }
            val rows = context.contentResolver.update(item.uri, values, null, null)
            if (rows > 0) {
                renameSidecarLrc(item.filePath, newName)
                Result(true)
            } else {
                // 部分机型 MediaStore 更新失败时尝试文件层
                val path = item.filePath
                if (!path.isNullOrBlank()) {
                    val file = File(path)
                    if (file.isFile) {
                        val dest = File(file.parentFile, newName)
                        if (file.renameTo(dest)) {
                            // 通知扫描
                            val scanUri = if (item.isVideo) {
                                MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                            } else {
                                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                            }
                            // 刷新 MediaStore 显示名（尽力）
                            context.contentResolver.update(item.uri, values, null, null)
                            renameSidecarLrc(path, newName)
                            return Result(true)
                        }
                    }
                }
                Result(false, "update_failed")
            }
        } catch (e: SecurityException) {
            Result(false, e.message ?: "security")
        } catch (e: Exception) {
            Result(false, e.message ?: "error")
        }
    }

    fun delete(context: Context, item: BrowseItem): Result {
        if (item.type != BrowseItemType.FILE || item.uri == null) {
            return Result(false, "invalid")
        }
        return deleteMany(context, listOf(item))
    }

    /** 批量删除媒体文件；Android 11+ 可能返回系统确认 Intent */
    fun deleteMany(context: Context, items: List<BrowseItem>): Result {
        val files = items.filter { it.type == BrowseItemType.FILE && it.uri != null }
        if (files.isEmpty()) return Result(false, "invalid")
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val uris = files.mapNotNull { it.uri }
                val pi = MediaStore.createDeleteRequest(context.contentResolver, uris)
                Result(ok = false, userConfirmPendingIntent = pi)
            } else {
                var okCount = 0
                for (item in files) {
                    val uri = item.uri ?: continue
                    val rows = context.contentResolver.delete(uri, null, null)
                    if (rows > 0) {
                        deleteSidecarLrc(item.filePath)
                        okCount++
                    } else {
                        val path = item.filePath
                        if (!path.isNullOrBlank()) {
                            val file = File(path)
                            if (file.isFile && file.delete()) {
                                deleteSidecarLrc(path)
                                okCount++
                            }
                        }
                    }
                }
                if (okCount > 0) Result(true)
                else Result(false, "delete_failed")
            }
        } catch (e: SecurityException) {
            Result(false, e.message ?: "security")
        } catch (e: Exception) {
            Result(false, e.message ?: "error")
        }
    }

    /** 系统确认删除成功后，清理同目录 lrc */
    fun afterDeleteConfirmed(item: BrowseItem) {
        deleteSidecarLrc(item.filePath)
    }

    fun afterDeleteConfirmedMany(items: List<BrowseItem>) {
        items.forEach { afterDeleteConfirmed(it) }
    }

    private fun renameSidecarLrc(audioPath: String?, newDisplayName: String) {
        if (audioPath.isNullOrBlank()) return
        val audio = File(audioPath)
        val dir = audio.parentFile ?: return
        val oldBase = audio.nameWithoutExtension
        val newBase = newDisplayName.substringBeforeLast('.', newDisplayName)
        listOf("$oldBase.lrc", "$oldBase.LRC", "$oldBase.lrcx").forEach { name ->
            val lrc = File(dir, name)
            if (lrc.isFile) {
                val ext = lrc.extension.ifBlank { "lrc" }
                lrc.renameTo(File(dir, "$newBase.$ext"))
            }
        }
    }

    private fun deleteSidecarLrc(audioPath: String?) {
        if (audioPath.isNullOrBlank()) return
        val audio = File(audioPath)
        val dir = audio.parentFile ?: return
        val base = audio.nameWithoutExtension
        listOf("$base.lrc", "$base.LRC", "$base.lrcx").forEach { name ->
            val lrc = File(dir, name)
            if (lrc.isFile) lrc.delete()
        }
    }
}
