package com.bqsqwad.ai

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Fails the build when infrastructure / vendor names appear in user-facing
 * string literals in non-admin, non-legal Kotlin sources.
 */
class NoInfrastructureNamesInUserCopyTest {
    private val forbidden = Regex(
        "(?i)google play|play console|firebase|firestore|cloudflare|composio|openai|supabase|tavily|billing client|paystack"
    )
    private val allowedFiles = setOf(
        "AdminProviderSecretsScreen.kt",
        "AdminPlanCatalogScreen.kt",
        "AdminLivePricingScreen.kt",
        "AdminControlSurface.kt",
        "BusinessAITestPanel.kt",
        "BrandCopy.kt",
        "LegalCenter.kt",
        "PlayBillingClient.kt"
    )

    @Test
    fun userFacingLiteralsDoNotNameInfrastructure() {
        val roots = listOf(
            File("src/main/java"),
            File("app/src/main/java")
        ).filter { it.exists() }
        val offenders = roots.flatMap { root ->
            root.walkTopDown()
                .filter { it.extension == "kt" && it.name !in allowedFiles }
                .flatMap { f ->
                    f.readLines().mapIndexedNotNull { i, line ->
                        val code = line.substringBefore("//")
                        val literals = Regex("\"([^\"\\\\]|\\\\.)*\"").findAll(code).map { it.value }.toList()
                        if (literals.any { forbidden.containsMatchIn(it) && !it.contains("Log.") && !it.contains("diagnostic") }) {
                            "${f.name}:${i + 1}"
                        } else null
                    }
                }
        }
        assertTrue("Infrastructure names in user copy: $offenders", offenders.isEmpty())
    }
}
