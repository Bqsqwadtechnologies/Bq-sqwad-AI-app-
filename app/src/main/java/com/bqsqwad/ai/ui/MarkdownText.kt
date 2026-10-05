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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.bqsqwad.ai.core.copyToClipboard

internal sealed interface MdBlock {
    data class Code(val lang: String, val code: String) : MdBlock
    data class Heading(val text: String) : MdBlock
    data class Bullet(val text: String) : MdBlock
    data class Para(val text: String) : MdBlock
}

internal fun parseBlocks(src: String): List<MdBlock> {
    val out = mutableListOf<MdBlock>()
    val lines = src.lines()
    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        when {
            line.trimStart().startsWith("```") -> {
                val lang = line.trim().removePrefix("```").trim()
                val buf = StringBuilder()
                i++
                while (i < lines.size && !lines[i].trimStart().startsWith("```")) { buf.appendLine(lines[i]); i++ }
                out += MdBlock.Code(lang, buf.toString().trimEnd())
            }
            line.trimStart().startsWith("#") -> out += MdBlock.Heading(line.trimStart('#').trim())
            line.trimStart().startsWith("- ") || line.trimStart().startsWith("* ") -> out += MdBlock.Bullet(line.trimStart().drop(2))
            line.isNotBlank() -> out += MdBlock.Para(line)
        }
        i++
    }
    return out
}

private val INLINE = Regex("""(\\*\\*[^*]+\\*\\*|`[^`]+`)""")

private fun inline(s: String): AnnotatedString = buildAnnotatedString {
    var last = 0
    INLINE.findAll(s).forEach { m ->
        append(s.substring(last, m.range.first))
        val token = m.value
        if (token.startsWith("**")) withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(token.removeSurrounding("**")) }
        else withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = Color(0x33888888))) { append(token.removeSurrounding("`")) }
        last = m.range.last + 1
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
                is MdBlock.Heading -> Text(inline(block.text), style = MaterialTheme.typography.titleMedium, color = color)
                is MdBlock.Bullet -> Row(verticalAlignment = Alignment.Top) { Text("•  ", color = color); Text(inline(block.text), color = color) }
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
                TextButton(onClick = { copyToClipboard(context, code) }) { Text("Copy") }
            }
            Text(code, fontFamily = FontFamily.Monospace, modifier = Modifier.horizontalScroll(rememberScrollState()))
        }
    }
}
