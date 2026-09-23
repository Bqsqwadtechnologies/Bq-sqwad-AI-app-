package com.bqsqwad.ai.core

import android.content.Context
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

data class PastedTextFile(val file: File, val characterCount: Int, val lineCount: Int)

object LongPasteAttachment {
    private val sequence = AtomicInteger(0)
    fun create(context: Context, text: String, useMarkdown: Boolean = false): PastedTextFile {
        val number = sequence.incrementAndGet()
        val extension = if (useMarkdown) "md" else "txt"
        val file = File(context.cacheDir, "Pasted text $number.$extension")
        file.writeText(text, Charsets.UTF_8)
        return PastedTextFile(file, text.length, if (text.isEmpty()) 0 else text.count { it == '\n' } + 1)
    }
}
