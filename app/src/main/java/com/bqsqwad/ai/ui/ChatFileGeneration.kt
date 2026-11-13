package com.bqsqwad.ai.ui

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.util.Locale

internal data class ChatGeneratedFileSpec(val title: String, val extension: String, val mimeType: String)

private val CHAT_FILE_TYPES = mapOf(
    "txt" to ("text/plain" to "txt"), "text" to ("text/plain" to "txt"),
    "md" to ("text/markdown" to "md"), "markdown" to ("text/markdown" to "md"),
    "csv" to ("text/csv" to "csv"), "json" to ("application/json" to "json"),
    "html" to ("text/html" to "html"), "htm" to ("text/html" to "htm"),
    "xml" to ("application/xml" to "xml"), "yaml" to ("application/yaml" to "yaml"),
    "yml" to ("application/yaml" to "yml"), "py" to ("text/x-python" to "py"),
    "python" to ("text/x-python" to "py"), "js" to ("text/javascript" to "js"),
    "javascript" to ("text/javascript" to "js"), "ts" to ("text/typescript" to "ts"),
    "typescript" to ("text/typescript" to "ts"), "tsx" to ("text/typescript" to "tsx"),
    "jsx" to ("text/javascript" to "jsx"), "css" to ("text/css" to "css"),
    "scss" to ("text/x-scss" to "scss"), "sql" to ("application/sql" to "sql"),
    "sh" to ("text/x-shellscript" to "sh"), "bash" to ("text/x-shellscript" to "sh"),
    "kt" to ("text/x-kotlin" to "kt"), "kotlin" to ("text/x-kotlin" to "kt"),
    "java" to ("text/x-java-source" to "java"), "c" to ("text/x-c" to "c"),
    "cpp" to ("text/x-c++src" to "cpp"), "h" to ("text/x-chdr" to "h"),
    "hpp" to ("text/x-c++hdr" to "hpp"), "go" to ("text/x-go" to "go"),
    "rs" to ("text/x-rust" to "rs"), "rust" to ("text/x-rust" to "rs"),
    "php" to ("application/x-httpd-php" to "php"), "rb" to ("text/x-ruby" to "rb"),
    "ruby" to ("text/x-ruby" to "rb"), "swift" to ("text/x-swift" to "swift"),
    "dart" to ("text/x-dart" to "dart"), "toml" to ("application/toml" to "toml"),
    "ini" to ("text/plain" to "ini"), "properties" to ("text/plain" to "properties"),
    "log" to ("text/plain" to "log")
)

private val FILE_EXTENSION = Regex("""(?i)\.([a-z0-9]{1,12})\b""")
private val FILE_FORMAT_WORD = Regex(
    """(?i)\b(txt|text|markdown|md|csv|json|html|htm|xml|yaml|yml|python|py|javascript|js|typescript|ts|tsx|jsx|css|scss|sql|bash|sh|kotlin|kt|java|cpp|c|go|rust|rs|php|ruby|rb|swift|dart|toml|ini|properties|log)\b"""
)

internal fun isChatFileGenerationRequest(prompt: String): Boolean {
    val q = prompt.trim().lowercase(Locale.ROOT)
    if (q.isBlank() || Regex("""\b(how do i|how can i|what is|explain|teach me|difference between)\b""").containsMatchIn(q)) return false
    val action = Regex("""\b(create|generate|make|write|build|export|save|produce)\b""").containsMatchIn(q)
    val asset = Regex("""\b(file|document|script|source code|spreadsheet|csv|json|html|xml|yaml|python|javascript|typescript|kotlin|java|sql)\b""").containsMatchIn(q)
    return action && asset
}

internal fun chatGeneratedFileSpec(prompt: String): ChatGeneratedFileSpec {
    val q = prompt.trim()
    val explicitExtension = FILE_EXTENSION.find(q)?.groupValues?.getOrNull(1)?.lowercase(Locale.ROOT)?.takeIf { it in CHAT_FILE_TYPES }
    val formatWord = FILE_FORMAT_WORD.find(q)?.groupValues?.getOrNull(1)?.lowercase(Locale.ROOT)
    val key = explicitExtension ?: formatWord ?: "txt"
    val type = CHAT_FILE_TYPES[key] ?: CHAT_FILE_TYPES.getValue("txt")
    val quoted = Regex("""["“]([^"”]{2,90})["”]""").find(q)?.groupValues?.getOrNull(1)
    val rawTitle = quoted ?: q
        .replace(Regex("""(?i)\b(create|generate|make|write|build|export|save|produce|a|an|the|file|document|script|source code|spreadsheet|as|in|format|please|for me|downloadable)\b"""), " ")
        .replace(FILE_EXTENSION, " ").replace(Regex("""\s+"""), " ").trim().take(70)
    val title = rawTitle.replace(Regex("""[^A-Za-z0-9 _-]"""), "").trim().take(70).ifBlank { "BQ Sqwad AI File" }
    return ChatGeneratedFileSpec(title, type.second, type.first)
}

internal fun normalizeGeneratedFileContent(reply: String): String {
    val text = reply.trim()
    val fence = Regex("(?s)^\\s*```(?:[a-zA-Z0-9_+.-]+)?\\s*\\n(.*?)\\n```\\s*$").find(text)?.groupValues?.getOrNull(1)
    val content = (fence ?: text).trim()
    require(content.isNotBlank()) { "The AI returned an empty file." }
    require(content.toByteArray(Charsets.UTF_8).size <= 1_000_000) { "The generated file is larger than the 1 MB in-chat limit." }
    return content + if (content.endsWith("\n")) "" else "\n"
}

internal fun stageChatGeneratedFile(context: Context, spec: ChatGeneratedFileSpec, content: String): Uri {
    val safeBase = spec.title.replace(Regex("""[^A-Za-z0-9_-]"""), "_").trim('_').take(70).ifBlank { "bq-sqwad-file" }
    val fileName = "${safeBase}.${spec.extension}"
    val values = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
        put(MediaStore.MediaColumns.MIME_TYPE, spec.mimeType)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/BQ Sqwad AI")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
    }
    val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) MediaStore.Downloads.EXTERNAL_CONTENT_URI else MediaStore.Files.getContentUri("external")
    val uri = context.contentResolver.insert(collection, values) ?: error("Could not create the file in Downloads.")
    try {
        val bytes = content.toByteArray(Charsets.UTF_8)
        require(bytes.size <= 1_000_000) { "The generated file is larger than the 1 MB in-chat limit." }
        context.contentResolver.openOutputStream(uri, "w")?.use { it.write(bytes) } ?: error("Could not write the generated file.")
        return uri
    } catch (error: Throwable) {
        context.contentResolver.delete(uri, null, null)
        throw error
    }
}

internal fun publishChatGeneratedFile(context: Context, uri: Uri) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
    }
}
