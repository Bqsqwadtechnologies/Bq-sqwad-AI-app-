package com.bqsqwad.ai

import com.bqsqwad.ai.core.ApiConfig
import com.bqsqwad.ai.core.ApiError
import com.bqsqwad.ai.core.apiErrorFrom
import com.bqsqwad.ai.core.withRetry
import com.bqsqwad.ai.core.parseGeneratedImage
import com.bqsqwad.ai.ui.MdBlock
import com.bqsqwad.ai.ui.parseBlocks
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class CoreTest {
    @Test fun apiConfigBuildsCanonicalUrls() {
        assertTrue(ApiConfig.url("/health").endsWith("/health"))
        assertTrue(ApiConfig.url("health").endsWith("/health"))
    }

    @Test fun markdownHandlesIncompleteFence() {
        val blocks = parseBlocks("# Title\n- one\n```kotlin\nprintln(1)")
        assertEquals(3, blocks.size)
        assertTrue(blocks[0] is MdBlock.Heading)
        assertTrue(blocks[1] is MdBlock.BulletList)
        assertTrue(blocks[2] is MdBlock.Code)
    }

    @Test fun markdownRecognizesImageLinks() {
        val blocks = parseBlocks("![preview](https://example.com/image.png)")
        assertEquals(1, blocks.size)
        assertTrue(blocks[0] is MdBlock.Image)
        val image = blocks[0] as MdBlock.Image
        assertEquals("preview", image.alt)
        assertEquals("https://example.com/image.png", image.url)
    }

    @Test fun generatedImageParserAcceptsWorkerBase64Contract() {
        val generated = parseGeneratedImage(
            imageUrl = null,
            imageBase64 = "cG5nLWJ5dGVz",
            creditsCharged = 1.0,
            modelId = null
        )
        assertEquals("", generated.url)
        assertEquals("cG5nLWJ5dGVz", generated.imageBase64)
        assertEquals(1.0, generated.creditsCharged, 0.0)
    }

    @Test fun generatedImageParserRejectsEmptyImageResponse() {
        val failure = runCatching { parseGeneratedImage(imageUrl = null, imageBase64 = null, creditsCharged = 1.0, modelId = null) }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
    }

    @Test fun retryRetriesIoFailure() = runBlocking {
        var calls = 0
        val result = withRetry(attempts = 3, initialDelayMs = 1) {
            calls++
            if (calls < 3) throw IOException("temporary")
            "ok"
        }
        assertEquals("ok", result)
        assertEquals(3, calls)
    }

    @Test fun apiErrorMappingUsesStructuredCodes() {
        assertTrue(apiErrorFrom(401, "auth_required") is ApiError.AuthRequired)
        assertTrue(apiErrorFrom(429, "rate_limited") is ApiError.RateLimited)
        assertTrue(apiErrorFrom(503, "provider_unavailable") is ApiError.ProviderUnavailable)
    }
}
