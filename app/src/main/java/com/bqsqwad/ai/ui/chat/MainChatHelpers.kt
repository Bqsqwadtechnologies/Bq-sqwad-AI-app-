package com.bqsqwad.ai.ui.chat

/**
 * Shared helpers for main chat behaviour.
 * Prefer live web search for time-sensitive or open-world questions so answers
 * are not stuck on older training knowledge.
 */
object MainChatHelpers {
    fun needsCurrentWebSearch(text: String): Boolean {
        val query = text.trim().lowercase(java.util.Locale.ROOT)
        if (query.isBlank()) return false
        val timeSensitive = Regex(
            "\\b(latest|today|tonight|right now|currently|current|this week|this month|this year|202[4-9]|203[0-9]|breaking|news|headline|price|prices|cost|stock|crypto|exchange rate|weather|forecast|score|scores|schedule|fixture|fixtures|standings|results|release date|launched|announced|recent update|as of|who is the current|president|prime minister|ceo of|what happened|who won|live|ongoing)\\b"
        )
        val knowledgeGap = Regex(
            "\\b(who is|what is|when did|where is|how many|how much|search|look up|find out|check online|on the web|according to|source)\\b"
        )
        return timeSensitive.containsMatchIn(query) || knowledgeGap.containsMatchIn(query)
    }

    fun formatCreditsLabel(available: Double): String = when {
        available <= 0.0 -> "0"
        available < 0.01 -> "<0.01"
        available < 1.0 -> String.format(java.util.Locale.US, "%.3f", available).trimEnd('0').trimEnd('.')
        else -> String.format(java.util.Locale.US, "%.2f", available).trimEnd('0').trimEnd('.')
    }

    /** Main chat should not surface numeric credits; only gate when truly empty and idle. */
    fun creditsExhausted(available: Double, requestInFlight: Boolean): Boolean =
        available <= 0.0 && !requestInFlight
}
