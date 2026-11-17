package com.bqsqwad.ai.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EntitlementExpiryParsingTest {
    @Test
    fun parsesEpochNumber() {
        assertEquals(1_800_000_000_000L, parseEntitlementExpiryMillis(1_800_000_000_000L))
    }

    @Test
    fun parsesIsoInstantString() {
        assertEquals(1_800_000_000_000L, parseEntitlementExpiryMillis("2027-01-15T08:00:00Z"))
    }

    @Test
    fun rejectsMalformedExpiryValuesWithoutThrowing() {
        assertNull(parseEntitlementExpiryMillis("not-a-date"))
        assertNull(parseEntitlementExpiryMillis(null))
        assertNull(parseEntitlementExpiryMillis(Any()))
    }
}
