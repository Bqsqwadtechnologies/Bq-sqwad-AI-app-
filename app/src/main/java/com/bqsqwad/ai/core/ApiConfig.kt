package com.bqsqwad.ai.core

import com.bqsqwad.ai.BuildConfig

object ApiConfig {
    /** Single private service origin. Configure it in app/build.gradle.kts only. */
    val baseUrl: String
        get() = BuildConfig.API_BASE_URL.trimEnd('/')

    /**
     * Website origin for public shared endpoints (catalog, models, legal).
     * Falls back to the configured service origin when not separately configured.
     */
    val websiteBaseUrl: String
        get() = runCatching {
            val field = BuildConfig::class.java.getField("WEBSITE_BASE_URL")
            (field.get(null) as? String)?.trimEnd('/')
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: baseUrl

    fun url(path: String): String =
        baseUrl + (if (path.startsWith("/")) path else "/$path")
}
