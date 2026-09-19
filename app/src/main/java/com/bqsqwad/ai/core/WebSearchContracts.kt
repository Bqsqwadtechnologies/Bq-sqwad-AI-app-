package com.bqsqwad.ai.core

data class WebSearchPricing(
    val pricingId: String,
    val enabled: Boolean = true,
    val creditsPerSearch: Long = 1L,
    val creditsPerDeepResearch: Long = 5L,
    val creditsPerResultPage: Long = 0L,
    val maxResults: Int = 10,
    val dailyUserLimit: Long? = null,
    val planOverrides: Map<String, Long> = emptyMap()
)

data class WebSearchRequest(
    val query: String,
    val deepResearch: Boolean = false,
    val maxResults: Int? = null,
    val workspace: String,
    val capabilityId: String = "web_search"
)

data class WebSearchResponse(
    val requestId: String,
    val providerId: String,
    val query: String,
    val sources: List<WebSource>,
    val creditsCharged: Long,
    val citations: List<String> = emptyList()
)

data class WebSource(
    val title: String,
    val url: String,
    val snippet: String? = null,
    val publishedAt: String? = null
)

interface WebSearchRepository {
    suspend fun pricing(): WebSearchPricing
    suspend fun search(request: WebSearchRequest): WebSearchResponse
}

object WebSearchCapabilities {
    const val SEARCH = "web_search"
    const val DEEP_RESEARCH = "deep_research"
    const val SOURCE_VERIFICATION = "source_verification"
    const val RESEARCH_EXPORT = "research_export"
}
