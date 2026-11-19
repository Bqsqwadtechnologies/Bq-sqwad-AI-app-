package com.bqsqwad.ai.ui

import java.util.Locale

internal fun isImageGenerationRequest(prompt: String): Boolean {
    val value = prompt.trim()
    if (value.isBlank()) return false
    val q = value.lowercase(Locale.ROOT)
    if (Regex("""\b(how do i|how can i|what is|explain|teach me|difference between)\b""").containsMatchIn(q)) return false
    val excludePattern = Regex("""\b(caption|describe|analy[sz]e|analysis|summari[sz]e|alt[- ]?text|critique|review)\b.{0,80}\b(this|that|the|my)\s+(image|picture|photo|artwork|logo)\b""", RegexOption.IGNORE_CASE)
    if (excludePattern.containsMatchIn(value)) return false
    val action = """(generate|create|draw|make|design|render|paint|produce)"""
    val visual = """(image|picture|photo|illustration|artwork|poster|logo|wallpaper|graphic)s?"""
    val p1 = Regex("""\b$action\b.{0,100}\b$visual\b""", RegexOption.IGNORE_CASE)
    val p2 = Regex("""\b$visual\b.{0,100}\b$action\b""", RegexOption.IGNORE_CASE)
    if (p1.containsMatchIn(value) || p2.containsMatchIn(value)) return true
    if (Regex("""^\s*(?:please\s+)?(?:draw|sketch|paint)\s+(?:me\s+)?(?:a|an|the|some)\b""", RegexOption.IGNORE_CASE).containsMatchIn(value)) return true
    return false
}
