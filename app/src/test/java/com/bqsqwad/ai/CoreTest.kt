package com.bqsqwad.ai

import com.bqsqwad.ai.core.ApiConfig
import com.bqsqwad.ai.core.ChatAttachmentReader
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import com.bqsqwad.ai.core.ApiError
import com.bqsqwad.ai.core.apiErrorFrom
import com.bqsqwad.ai.core.withRetry
import com.bqsqwad.ai.core.parseGeneratedImage
import com.bqsqwad.ai.ui.MdBlock
import com.bqsqwad.ai.ui.parseBlocks
import com.bqsqwad.ai.ui.isImageGenerationRequest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test fun docxAttachmentExtractsDocumentText() {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry("word/document.xml"))
            zip.write("<w:document><w:p><w:r><w:t>Hello BQ Sqwad AI</w:t></w:r></w:p></w:document>".toByteArray())
            zip.closeEntry()
        }
        val text = ChatAttachmentReader.extractOfficeText(output.toByteArray(), "docx")
        assertTrue(text.contains("Hello BQ Sqwad AI"))
    }

    @Test fun xlsxAttachmentExtractsSharedStringsAndValues() {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            val entries = mapOf(
                "xl/sharedStrings.xml" to "<sst><si><t>Customer</t></si></sst>",
                "xl/worksheets/sheet1.xml" to "<worksheet><sheetData><row r=\"1\"><c r=\"A1\" t=\"s\"><v>0</v></c><c r=\"B1\"><v>42</v></c></row></sheetData></worksheet>"
            )
            entries.forEach { (name, xml) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(xml.toByteArray())
                zip.closeEntry()
            }
        }
        val text = ChatAttachmentReader.extractOfficeText(output.toByteArray(), "xlsx")
        assertTrue(text.contains("Customer"))
        assertTrue(text.contains("42"))
    }

    @Test fun pptxAttachmentExtractsSlideText() {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry("ppt/slides/slide1.xml"))
            zip.write("<p:sld><a:p><a:r><a:t>Project update</a:t></a:r></a:p></p:sld>".toByteArray())
            zip.closeEntry()
        }
        val text = ChatAttachmentReader.extractOfficeText(output.toByteArray(), "pptx")
        assertTrue(text.contains("Project update"))
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

    @Test fun imageIntentRecognizesCommonCreationRequests() {
        listOf(
            "Can you generate an image of a lion?",
            "Generate a logo for my business",
            "Draw a lion",
            "Create a poster for the event",
            "Please design a wallpaper",
            "Make me a banner",
            "Show me a photo of a red sports car"
        ).forEach { request ->
            assertTrue("Expected image intent for: " + request, isImageGenerationRequest(request))
        }
    }

    @Test fun imageIntentDoesNotCaptureQuestionsOrNegatedRequests() {
        listOf(
            "What is image generation?",
            "How do I create a logo?",
            "Can you explain how to make a poster?",
            "Don't generate an image",
            "Please do not create a logo"
        ).forEach { request ->
            assertFalse("Expected ordinary chat for: " + request, isImageGenerationRequest(request))
        }
    }
}
