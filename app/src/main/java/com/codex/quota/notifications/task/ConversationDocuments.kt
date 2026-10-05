package com.codex.quota.notifications.task

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object ConversationDocumentTypes {
    fun mime(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "pdf" -> "application/pdf"
        "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
        "ppt" -> "application/vnd.ms-powerpoint"
        "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        "doc" -> "application/msword"
        "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        "xls" -> "application/vnd.ms-excel"
        "txt", "log" -> "text/plain"
        "md" -> "text/markdown"
        "csv" -> "text/csv"
        "json" -> "application/json"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "webp" -> "image/webp"
        "zip" -> "application/zip"
        else -> "application/octet-stream"
    }
}

/** Only already verified downloads are handed to a viewer; never expose arbitrary cache paths. */
object ConversationDocuments {
    suspend fun stage(context: Context, source: File, name: String): File = withContext(Dispatchers.IO) {
        require(name.isNotBlank() && name.toByteArray().size <= 240 && !name.any { it in "/\\\r\n\u0000" } && name != "." && name != "..")
        val downloads = File(context.cacheDir, "conversation-downloads").canonicalFile
        require(source.canonicalFile.parentFile == downloads && source.isFile)
        val root = File(context.cacheDir, "conversation-previews").apply { mkdirs() }.canonicalFile
        root.listFiles().orEmpty().filter { it.isDirectory && UUID_REGEX.matches(it.name) && System.currentTimeMillis() - it.lastModified() > 86400_000 }
            .forEach { directory -> if (directory.canonicalFile.parentFile == root) directory.deleteRecursively() }
        val folder = File(root, UUID.randomUUID().toString()).apply { mkdirs() }
        val target = File(folder, name)
        require(target.canonicalFile.parentFile == folder.canonicalFile)
        try {
            if (!source.renameTo(target)) { source.inputStream().use { input -> target.outputStream().use { input.copyTo(it, 65536) } }; source.delete() }
            target
        } catch (e: Exception) { target.delete(); folder.delete(); source.delete(); throw e }
    }
    fun viewIntent(context: Context, file: File): Intent {
        val root = File(context.cacheDir, "conversation-previews").canonicalFile
        require(file.isFile && file.canonicalFile.parentFile?.parentFile == root && UUID_REGEX.matches(file.parentFile!!.name))
        val uri = FileProvider.getUriForFile(context, context.packageName + ".task-notification-files", file)
        return Intent(Intent.ACTION_VIEW).setDataAndType(uri, ConversationDocumentTypes.mime(file.name))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .apply { clipData = android.content.ClipData.newRawUri(file.name, uri) }
    }
    private val UUID_REGEX = Regex("[a-f0-9]{8}(-[a-f0-9]{4}){3}-[a-f0-9]{12}")
}
