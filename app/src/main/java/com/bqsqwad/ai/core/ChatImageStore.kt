package com.bqsqwad.ai.core

import android.content.Context
import android.util.Base64
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/**
 * Device-local durable storage for chat image bytes.
 * Only opaque IDs are stored in conversation history; image data never goes
 * into SavedState bundles or the remote history payload.
 */
object ChatImageStore {
    private const val DIRECTORY = "chat-images"
    private const val MAX_IMAGE_BYTES = 20 * 1024 * 1024

    fun saveBase64(context: Context, encoded: String?): String? {
        if (encoded.isNullOrBlank()) return null
        val bytes = runCatching { Base64.decode(encoded, Base64.DEFAULT) }.getOrNull() ?: return null
        if (bytes.isEmpty() || bytes.size > MAX_IMAGE_BYTES) return null
        val id = UUID.randomUUID().toString()
        val directory = File(context.filesDir, DIRECTORY)
        if (!directory.exists() && !directory.mkdirs()) return null
        val target = File(directory, "$id.img")
        return runCatching {
            FileOutputStream(target).use { output -> output.write(bytes); output.fd.sync() }
            id
        }.getOrElse {
            target.delete()
            null
        }
    }

    fun readBase64(context: Context, id: String?): String? {
        if (id.isNullOrBlank() || !id.matches(Regex("[a-fA-F0-9-]{36}"))) return null
        val target = File(File(context.filesDir, DIRECTORY), "$id.img")
        if (!target.isFile || target.length() !in 1..MAX_IMAGE_BYTES.toLong()) return null
        return runCatching { Base64.encodeToString(target.readBytes(), Base64.NO_WRAP) }.getOrNull()
    }
}
