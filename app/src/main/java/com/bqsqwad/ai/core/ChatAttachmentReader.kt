package com.bqsqwad.ai.core

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

data class PreparedChatAttachment(
    val name: String,
    val mimeType: String,
    val text: String? = null,
    val imageBase64: String? = null,
    val fileBase64: String? = null
)

object ChatAttachmentReader {
    private const val MAX_BYTES = 5L * 1024L * 1024L
    private const val MAX_TEXT_CHARS = 120_000
    private val TEXT_MIMES = setOf("text/plain", "text/markdown", "text/csv", "text/html", "application/json", "application/xml", "text/xml", "application/javascript", "text/javascript")

    fun read(context: Context, uri: Uri): PreparedChatAttachment {
        val resolver = context.contentResolver
        val mime = (resolver.getType(uri) ?: "application/octet-stream").lowercase()
        val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: "attachment"
        val bytes = resolver.openInputStream(uri)?.use { input ->
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            var total = 0L
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                if (total > MAX_BYTES) error("Attachment '" + name + "' is larger than 5 MB.")
                out.write(buffer, 0, count)
            }
            out.toByteArray()
        } ?: error("Could not open '" + name + "'.")
        return when {
            mime.startsWith("image/") -> {
                require(mime in setOf("image/jpeg", "image/jpg", "image/png", "image/webp")) {
                    "Image '" + name + "' must be JPEG, PNG, or WebP. Convert it to a supported format and try again."
                }
                PreparedChatAttachment(name, if (mime == "image/jpg") "image/jpeg" else mime, imageBase64 = Base64.encodeToString(bytes, Base64.NO_WRAP))
            }
            mime == "application/pdf" || name.substringAfterLast('.', "").equals("pdf", ignoreCase = true) ->
                PreparedChatAttachment(name, "application/pdf", fileBase64 = Base64.encodeToString(bytes, Base64.NO_WRAP))
            mime in TEXT_MIMES || name.substringAfterLast('.', "").lowercase() in setOf("txt", "md", "csv", "json", "xml", "html", "js", "kt", "kts", "java", "py", "css") -> {
                val text = bytes.toString(StandardCharsets.UTF_8)
                require(text.length <= MAX_TEXT_CHARS) {
                    "Attachment '" + name + "' exceeds the " + MAX_TEXT_CHARS +
                        "-character text limit. Split it into smaller files so no content is silently omitted."
                }
                PreparedChatAttachment(name, mime, text = text)
            }
            else -> error("Unsupported attachment type for '" + name + "'. Use an image or text-based document.")
        }
    }
}
