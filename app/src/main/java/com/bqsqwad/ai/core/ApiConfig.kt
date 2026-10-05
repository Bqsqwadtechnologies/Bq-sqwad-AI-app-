package com.bqsqwad.ai.core

import com.bqsqwad.ai.BuildConfig

object ApiConfig {
    /** Single backend origin. Configure it in app/build.gradle.kts only. */
    val baseUrl: String
        get() = BuildConfig.API_BASE_URL.trimEnd('/')

    fun url(path: String): String =
        baseUrl + (if (path.startsWith("/")) path else "/$path")
}
