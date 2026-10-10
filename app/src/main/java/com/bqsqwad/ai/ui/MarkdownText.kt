package com.bqsqwad.ai.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.bqsqwad.ai.core.copyToClipboard

internal sealed interface MdBlock {
    data class Code(val lang: String, val code: String) : MdBlock
    data class Heading(val text: String) : MdBlock
    data class Bullet(val text: String) : MdBlock
    data class BulletList(val items: List<String>) : MdBlock
    data class Numbered(val text: String) : MdBlock
    data class NumberedList(val items: List<String>) : MdBlock
    data class Quote(val text: String) : MdBlock
    data object Divider : MdBlock
    data class Para(val text: String) : MdBlock
}

private val ORDERED_PREFIX = Regex("""^\s*\d+[.)]\s+""")

internal fun parseBlocks(src: String): List<MdBlock> {
    val out = mutableListOf<MdBlock>()
    val lines = src.lines()
    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        val trimmed = line.trimStart()
        when {
            trimmed.startsWith("```") -> {
                val lang = trimmed.removePrefix("```").trim()
                val buf = StringBuilder()
                i++
                while (i < lines.size && !lines[i].trimStart().startsWith("```")) {
                    buf.appendLine(lines[i])
                    i++
                }
                out += MdBlock.Code(lang, buf.toString().trimEnd())
            }
            trimmed.startsWith("#") -> out += MdBlock.Heading(trimmed.trimStart('#').trim())
            trimmed.startsWith("---") || trimmed.startsWith("***") -> out += MdBlock.Divider
            trimmed.startsWith(">") -> out += MdBlock.Quote(trimmed.drop(1).trim())
            ORDERED_PREFIX.containsMatchIn(line) -> out += MdBlock.Numbered(ORDERED_PREFIX.replaceFirst(line, ""))
            trimmed.startsWith("- ") || trimmed.startsWith("* ") -> out += MdBlock.Bullet(trimmed.drop(2))
            line.isNotBlank() -> out += MdBlock.Para(line)
        }
        i++
    }

    val grouped = mutableListOf<MdBlock>()
    var bulletItems = mutableListOf<String>()
    var numberedItems = mutableListOf<String>()
    fun flushBullets() {
        if (bulletItems.isNotEmpty()) {
            grouped += MdBlock.BulletList(bulletItems.toList())
            bulletItems = mutableListOf()
        }
    }
    fun flushNumbered() {
        if (numberedItems.isNotEmpty()) {
            grouped += MdBlock.NumberedList(numberedItems.toList())
            numberedItems = mutableListOf()
        }
    }
    out.forEach { block ->
        when (block) {
            is MdBlock.Bullet -> {
                flushNumbered()
                bulletItems += block.text
            }
            is MdBlock.Numbered -> {
                flushBullets()
                numberedItems += block.text
            }
            else -> {
                flushBullets()
                flushNumbered()
                grouped += block
            }
        }
    }
    flushBullets()
    flushNumbered()
    return grouped
}

private val INLINE = Regex("""(\*\*[^*]+\*\*|(?<!\*)\*[^*]+\*(?!\*)|`[^`]+`)""")

private fun inline(s: String): AnnotatedString = buildAnnotatedString {
    var last = 0
    INLINE.findAll(s).forEach { match ->
        append(s.substring(last, match.range.first))
        val token = match.value
        when {
            token.startsWith("**") -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                append(token.removeSurrounding("**"))
            }
            token.startsWith("`") -> withStyle(
                SpanStyle(fontFamily = FontFamily.Monospace, background = Color(0x33888888))
            ) {
                append(token.removeSurrounding("`"))
            }
            else -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                append(token.removeSurrounding("*"))
            }
        }
        last = match.range.last + 1
    }
    append(s.substring(last))
}

@Composable
fun MarkdownText(text: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.onSurface) {
    val blocks = remember(text) { parseBlocks(text) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        blocks.forEach { block ->
            when (block) {
                is MdBlock.Code -> CodeBlock(block.code, block.lang)
                is MdBlock.Heading -> Text(
                    inline(block.text),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = color
                )
                is MdBlock.Bullet -> Row(verticalAlignment = Alignment.Top) {
                    Text("•  ", color = color)
                    Text(inline(block.text), color = color)
                }
                is MdBlock.BulletList -> Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    block.items.forEach { item ->
                        Row(verticalAlignment = Alignment.Top) {
                            Text("•  ", color = color)
                            Text(inline(item), color = color)
                        }
                    }
                }
                is MdBlock.Numbered -> Text("1. " + inline(block.text), color = color)
                is MdBlock.NumberedList -> Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    block.items.forEachIndexed { index, item ->
                        Row(verticalAlignment = Alignment.Top) {
                            Text((index + 1).toString() + ".  ", color = color)
                            Text(inline(item), color = color)
                        }
                    }
                }
                is MdBlock.Quote -> Surface(
                    shape = RoundedCornerShape(topEnd = 10.dp, bottomEnd = 10.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Text(inline(block.text), color = color, modifier = Modifier.padding(10.dp))
                }
                MdBlock.Divider -> HorizontalDivider()
                is MdBlock.Para -> Text(inline(block.text), color = color)
            }
        }
    }
}

@Composable
private fun CodeBlock(code: String, lang: String) {
    val context = LocalContext.current
    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(Modifier.padding(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(lang.ifBlank { "code" }, style = MaterialTheme.typography.labelSmall)
                TextButton(onClick = { copyToClipboard(context, code) }) { Text("Copy code") }
            }
            Text(code, fontFamily = FontFamily.Monospace, modifier = Modifier.horizontalScroll(rememberScrollState()))
        }
    }
}
