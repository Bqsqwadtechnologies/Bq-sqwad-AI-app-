package com.bqsqwad.ai.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class BrandCopyTest {
    @Test
    fun checkoutErrorsUseCustomerSafeCopy() {
        val messages = listOf(
            BrandCopy.checkout("Google Play billing disconnected"),
            BrandCopy.checkout("Google Play could not validate this purchase setup"),
            BrandCopy.checkout("Play Console product setup is unavailable"),
            BrandCopy.checkout("Firebase sign in failed"),
            BrandCopy.checkout("network error"),
        )
        messages.forEach { message ->
            assertFalse(message.contains("Google Play", ignoreCase = true))
            assertFalse(message.contains("Firebase", ignoreCase = true))
            assertFalse(message.contains("Play Console", ignoreCase = true))
        }
        assertEquals(
            "We couldn't reach the BQ Sqwad AI catalog. Check your internet connection and try again.",
            BrandCopy.checkout("Google Play billing disconnected")
        )
    }

    @Test
    fun scrubReplacesInfrastructureNamesInCustomerCopy() {
        val safe = BrandCopy.scrub("Google Play Billing / Google Play / Play Console / Firebase")
        assertFalse(safe.contains("Google Play", ignoreCase = true))
        assertFalse(safe.contains("Firebase", ignoreCase = true))
        assertFalse(safe.contains("Play Console", ignoreCase = true))
    }
}
