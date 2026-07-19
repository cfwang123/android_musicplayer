package com.whj.music.util

import android.net.Uri
import android.provider.DocumentsContract
import com.whj.music.data.FolderBrowser

/**
 * 将系统目录选择器（ACTION_OPEN_DOCUMENT_TREE）返回的 URI
 * 转为与 MediaStore RELATIVE_PATH 一致的相对路径。
 *
 * 例：content://.../tree/primary%3AMusic%2FPop → Music/Pop/
 */
object StoragePathUtils {

    fun treeUriToRelativePath(uri: Uri): String? {
        val docId = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull()
            ?: return null
        // primary:Music/sub  或  1A2B-3C4D:Music
        val colon = docId.indexOf(':')
        if (colon < 0 || colon >= docId.lastIndex) return null
        val relative = docId.substring(colon + 1)
        if (relative.isBlank()) {
            // 选中存储根：primary: → 空路径，不适合作为主目录快捷项
            return null
        }
        return FolderBrowser.normalizeFolder(relative)
    }
}
