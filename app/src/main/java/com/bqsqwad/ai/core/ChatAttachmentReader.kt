package com.bqsqwad.ai.core

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.zip.ZipInputStream

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
    private const val MAX_OFFICE_EXPANDED_BYTES = 10 * 1024 * 1024
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
            name.substringAfterLast('.', "").lowercase() in setOf("docx", "xlsx", "pptx") -> {
                val extension = name.substringAfterLast('.', "").lowercase()
                val text = extractOfficeText(bytes, extension)
                require(text.isNotBlank()) { "No readable text was found in '" + name + "'." }
                require(text.length <= MAX_TEXT_CHARS) {
                    "Extracted text from '" + name + "' exceeds the " + MAX_TEXT_CHARS + "-character limit."
                }
                PreparedChatAttachment(name, mime, text = text)
            }
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


    private fun extractOfficeText(bytes: ByteArray, extension: String): String {
        val entries = linkedMapOf<String, String>()
        var expandedBytes = 0
        fun relevant(path: String): Boolean = when (extension) {
            "docx" -> path == "word/document.xml" ||
                path.matches(Regex("""word/(header|footer)\d+\.xml""")) ||
                path == "word/footnotes.xml" || path == "word/endnotes.xml"
            "pptx" -> path.matches(Regex("""ppt/slides/slide\d+\.xml"""))
            "xlsx" -> path == "xl/sharedStrings.xml" ||
                path.matches(Regex("""xl/worksheets/sheet\d+\.xml"""))
            else -> false
        }
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (!entry.isDirectory && relevant(entry.name)) {
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        val count = zip.read(buffer)
                        if (count < 0) break
                        expandedBytes += count
                        require(expandedBytes <= MAX_OFFICE_EXPANDED_BYTES) {
                            "Office document expands beyond the safe processing limit."
                        }
                        output.write(buffer, 0, count)
                    }
                    entries[entry.name] = output.toString(StandardCharsets.UTF_8.name())
                }
                zip.closeEntry()
            }
        }
        return when (extension) {
            "docx" -> entries.entries
                .filter { it.key == "word/document.xml" || it.key.startsWith("word/header") || it.key.startsWith("word/footer") || it.key.endsWith("footnotes.xml") || it.key.endsWith("endnotes.xml") }
                .sortedBy { it.key }
                .joinToString("\n") { xmlToText(it.value) }
            "pptx" -> entries.entries.sortedBy { it.key }.joinToString("\n\n") { xmlToText(it.value) }
            "xlsx" -> extractSpreadsheetText(entries)
            else -> ""
        }.trim()
    }

    private fun extractSpreadsheetText(entries: Map<String, String>): String {
        val sharedStrings = entries["xl/sharedStrings.xml"]?.let { xml ->
            Regex("""<si\b[^>]*>(.*?)</si>""", setOf(RegexOption.DOT_MATCHES_ALL))
                .findAll(xml)
                .map { xmlToText(it.groupValues[1]) }
                .toList()
        }.orEmpty()
        val sheetPattern = Regex("""<c\b([^>]*)>(.*?)</c>""", setOf(RegexOption.DOT_MATCHES_ALL))
        return entries.entries.filter { it.key.startsWith("xl/worksheets/sheet") }
            .sortedBy { it.key }
            .joinToString("\n") { (path, xml) ->
                val rows = Regex("""<row\b[^>]*>(.*?)</row>""", setOf(RegexOption.DOT_MATCHES_ALL))
                    .findAll(xml)
                    .map { row ->
                        sheetPattern.findAll(row.groupValues[1]).joinToString(" | ") { cell ->
                            val type = Regex("""\bt="([^"]+)"""").find(cell.groupValues[1])?.groupValues?.get(1)
                            val raw = Regex("""<v\b[^>]*>(.*?)</v>""", setOf(RegexOption.DOT_MATCHES_ALL))
                                .find(cell.groupValues[2])?.groupValues?.get(1)
                                ?: cell.groupValues[2]
                            if (type == "s") sharedStrings.getOrNull(raw.trim().toIntOrNull() ?: -1).orEmpty()
                            else xmlToText(raw)
                        }
                    }
                    .filter { it.isNotBlank() }
                    .joinToString("\n")
                path.substringAfterLast('/') + "\\n" + rows
            }
    }

    private fun xmlToText(xml: String): String {
        val plain = xml
            .replace(Regex("""<(?:w:tab|a:br)\b[^>]*/>"""), "\\t")
            .replace(Regex("""</(?:w:p|a:p|w:tr|a:tr|si|row)\s*>"""), "\\n")
            .replace(Regex("""<[^>]+>"""), " ")
        val entities = plain
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
        return entities.replace(Regex("""[ \t]+"""), " ")
            .replace(Regex(""" *\n *"""), "\\n")
            .replace(Regex("""\n{3,}"""), "\\n\\n")
            .trim()
    }
}
