package com.bqsqwad.ai

import com.bqsqwad.ai.core.ApiConfig
import com.bqsqwad.ai.core.WorkspaceAccessSnapshot
import com.bqsqwad.ai.core.WorkspaceRedirectStore
import com.bqsqwad.ai.domain.Workspace
import com.bqsqwad.ai.core.ChatAttachmentReader
import com.bqsqwad.ai.core.detectCsvDimensions
import com.bqsqwad.ai.core.shouldAutoSaveChat
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import com.bqsqwad.ai.core.ApiError
import com.bqsqwad.ai.core.apiErrorFrom
import com.bqsqwad.ai.core.withRetry
import com.bqsqwad.ai.core.parseGeneratedImage
import com.bqsqwad.ai.core.selectCameraIdForFacing
import com.bqsqwad.ai.ui.MdBlock
import com.bqsqwad.ai.ui.parseBlocks
import com.bqsqwad.ai.ui.isImageGenerationRequest
import com.bqsqwad.ai.ui.sanitizeWebAnswer
import com.bqsqwad.ai.ui.orderCanonicalCreditPackIds
import com.bqsqwad.ai.ui.catalogCategoryHeaderIndex
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.Calendar

class CoreTest {

    @Test fun birthdayMonthiversaryClampsMonthEndAndPreservesLocalTime() {
        val start = Calendar.getInstance().apply {
            clear()
            set(2024, Calendar.JANUARY, 31, 12, 30, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val anniversary = Calendar.getInstance().apply {
            timeInMillis = com.bqsqwad.ai.core.bqAnniversaryTimestamp(start, Calendar.MONTH, 1)
        }
        assertEquals(Calendar.FEBRUARY, anniversary.get(Calendar.MONTH))
        assertEquals(29, anniversary.get(Calendar.DAY_OF_MONTH))
        assertEquals(12, anniversary.get(Calendar.HOUR_OF_DAY))
        assertEquals(30, anniversary.get(Calendar.MINUTE))
    }

    @Test fun birthdayYeariversaryHandlesLeapDayWithoutRollingIntoMarch() {
        val start = Calendar.getInstance().apply {
            clear()
            set(2024, Calendar.FEBRUARY, 29, 9, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val anniversary = Calendar.getInstance().apply {
            timeInMillis = com.bqsqwad.ai.core.bqAnniversaryTimestamp(start, Calendar.YEAR, 1)
        }
        assertEquals(2025, anniversary.get(Calendar.YEAR))
        assertEquals(Calendar.FEBRUARY, anniversary.get(Calendar.MONTH))
        assertEquals(28, anniversary.get(Calendar.DAY_OF_MONTH))
    }

    @Test fun remoteImagesEmbeddedInAiTextAreNotRendered() {
        val blocks = parseBlocks("![private repository](https://github.com/private/repo/worker.png)")
        assertFalse(blocks.any { it is MdBlock.Image })
        assertFalse(blocks.toString().contains("github.com"))
        assertTrue(blocks.toString().contains("External image preview hidden for privacy."))
    }


    @Test fun userFacingErrorsNeverExposePrivateWorkerOrRepositoryDetails() {
        val privateDetails = IllegalStateException("Request failed for owner@example.com at https://private-worker.workers.dev; repo https://github.com/private/repo")
        val shown = com.bqsqwad.ai.ui.safeUserFacingIssue(privateDetails, "Try again.")
        assertTrue(shown.startsWith("Something went wrong. Reference "))
        assertTrue(shown.substringAfter("Reference ").trim().trimEnd('.').matches(Regex("[A-F0-9]{8}")))
        assertFalse(shown.contains("@"))
        assertFalse(shown.contains("workers.dev"))
        assertFalse(shown.contains("github.com"))
    }


    @Test fun realtimeProviderRejectionDoesNotExposeStatusOrDiagnosticCodesToUsers() {
        val shown = com.bqsqwad.ai.ui.safeUserFacingIssue(
            IllegalStateException("realtime_session_failed (provider status 400)"),
            "Live Voice is getting ready. Please try again in a moment."
        )
        assertFalse(shown.contains("400"))
        assertFalse(shown.contains("realtime_session_failed"))
        assertFalse(shown.contains("provider_not_configured"))
    }

    @Test fun sharedIssueMapperDistinguishesGuestOfflineAndServerFailure() {
        val guest = com.bqsqwad.ai.ui.issueFrom(signedIn = false)
        assertEquals(com.bqsqwad.ai.ui.BqIssueKind.NOT_SIGNED_IN, guest.kind)
        assertEquals(com.bqsqwad.ai.ui.BqIssueAction.SIGN_IN, guest.primaryAction)

        val offline = com.bqsqwad.ai.ui.issueFrom(java.net.UnknownHostException("hidden host"))
        assertEquals(com.bqsqwad.ai.ui.BqIssueKind.OFFLINE, offline.kind)
        assertTrue(offline.message.contains("Wi-Fi or mobile data"))
        assertFalse(offline.message.contains("hidden host"))

        val expired = com.bqsqwad.ai.ui.issueFrom(httpStatus = 401, signedIn = true)
        assertEquals(com.bqsqwad.ai.ui.BqIssueKind.SESSION_EXPIRED, expired.kind)

        val forbidden = com.bqsqwad.ai.ui.issueFrom(httpStatus = 403)
        assertEquals(com.bqsqwad.ai.ui.BqIssueKind.PERMISSION_DENIED, forbidden.kind)

        val timeout = com.bqsqwad.ai.ui.issueFrom(java.net.SocketTimeoutException("request timed out"))
        assertEquals(com.bqsqwad.ai.ui.BqIssueKind.OFFLINE, timeout.kind)

        val cancelled = com.bqsqwad.ai.ui.issueFrom(kotlinx.coroutines.CancellationException("cancelled"))
        assertEquals(com.bqsqwad.ai.ui.BqIssueKind.CANCELLED, cancelled.kind)
        assertEquals("", cancelled.message)

        val credits = com.bqsqwad.ai.ui.issueFrom(httpStatus = 402)
        assertEquals(com.bqsqwad.ai.ui.BqIssueKind.OUT_OF_CREDITS, credits.kind)

        val rateLimited = com.bqsqwad.ai.ui.issueFrom(httpStatus = 429)
        assertEquals(com.bqsqwad.ai.ui.BqIssueKind.RATE_LIMITED, rateLimited.kind)

        val server = com.bqsqwad.ai.ui.issueFrom(httpStatus = 503)
        assertEquals(com.bqsqwad.ai.ui.BqIssueKind.UNKNOWN, server.kind)
        assertTrue(server.refCode?.matches(Regex("[A-F0-9]{8}")) == true)

        val providerCapacity = com.bqsqwad.ai.ui.issueFrom(httpStatus = 429, errorCode = "provider_capacity")
        assertEquals(com.bqsqwad.ai.ui.BqIssueKind.SERVER_BUSY, providerCapacity.kind)

        val missingPrice = com.bqsqwad.ai.ui.issueFrom(httpStatus = 503, errorCode = "model_price_missing")
        assertEquals(com.bqsqwad.ai.ui.BqIssueKind.UNKNOWN, missingPrice.kind)
        assertTrue(missingPrice.message.contains("on our side"))

        val unknown = com.bqsqwad.ai.ui.issueFrom(IllegalStateException("private provider details"))
        assertEquals(com.bqsqwad.ai.ui.BqIssueKind.UNKNOWN, unknown.kind)
        assertEquals(com.bqsqwad.ai.ui.BqIssueAction.REPORT_PROBLEM, unknown.secondaryAction)
        assertTrue(unknown.refCode?.matches(Regex("[A-F0-9]{8}")) == true)
        assertFalse(unknown.message.contains("private provider details"))
    }

    @Test fun workspaceRedirectStorePublishesAndClearsTarget() {
        WorkspaceRedirectStore.clear()
        WorkspaceRedirectStore.publish(org.json.JSONObject().put("workspace", "CHAT").put("message", "Open Main Chat"))
        assertEquals("CHAT", WorkspaceRedirectStore.redirect.value?.optString("workspace"))
        assertEquals("Open Main Chat", WorkspaceRedirectStore.redirect.value?.optString("message"))
        WorkspaceRedirectStore.clear()
        assertEquals(null, WorkspaceRedirectStore.redirect.value)
    }

    @Test fun webAnswerHidesBareAndMarkdownUrlsButKeepsReadableWordsAndCitations() {
        val answer = sanitizeWebAnswer("The report says [1]. Read [the source](https://example.com/report) or visit https://example.org/page for details.")
        assertTrue(answer.contains("[1]"))
        assertTrue(answer.contains("the source"))
        assertTrue(answer.contains("for details"))
        assertFalse(answer.contains("https://"))
    }

    @Test fun catalogCreditPacksUseCanonicalOrderAndExcludeUnknownIds() {
        assertEquals(
            listOf("credit100", "credit250", "credit500", "credit1000"),
            orderCanonicalCreditPackIds(listOf("credit1000", "other", "credit500", "credit100", "credit250"))
        )
    }

    @Test fun catalogCategoryChipsScrollToTheFilteredSectionHeader() {
        assertEquals(28, catalogCategoryHeaderIndex(totalItemCount = 42, trailingItemCount = 5, planCount = 8))
        assertEquals(0, catalogCategoryHeaderIndex(totalItemCount = 2, trailingItemCount = 8, planCount = 5))
    }

    @Test fun cameraSelectionTriesRequestedLensThenOtherThenFirstAvailable() {
        val facings = mapOf("logical" to 2, "front" to 0, "back" to 1)
        assertEquals("back" to 1, selectCameraIdForFacing(listOf("logical", "front", "back"), 1) { facings[it] })
        assertEquals("front" to 0, selectCameraIdForFacing(listOf("logical", "front"), 1) { facings[it] })
        assertEquals("logical" to 2, selectCameraIdForFacing(listOf("logical"), 1) { facings[it] })
        assertEquals(null, selectCameraIdForFacing(emptyList(), 1) { facings[it] })
        assertEquals("front" to 0, selectCameraIdForFacing(listOf("broken", "front"), 1) { id -> if (id == "broken") error("unreadable characteristics") else facings[id] })
    }

    @Test fun imageGenerationRequestsAreDetectedWithoutModeChips() {
        assertTrue(isImageGenerationRequest("Generate a picture of a blue whale"))
        assertTrue(isImageGenerationRequest("Please create an image of a cozy reading room"))
        assertTrue(isImageGenerationRequest("Make a logo for my tailoring brand"))
        assertTrue(isImageGenerationRequest("Draw a superhero with lightning powers"))
        assertFalse(isImageGenerationRequest("How do I create an image in Blender?"))
        assertFalse(isImageGenerationRequest("What is image generation?"))
    }

    @Test fun creditOnlyWorkspacesNeverWaitForOrRequireAPlan() {
        val snapshot = WorkspaceAccessSnapshot.empty()
        listOf(
            Workspace.CHAT,
            Workspace.SEARCH,
            Workspace.CAMERA,
            Workspace.VOICE,
            Workspace.VIDEO,
            Workspace.PERSONAL,
            Workspace.DATA_ANALYSIS
        ).forEach { workspace ->
            assertTrue("$workspace must be immediately accessible", snapshot.hasVerifiedAccess(workspace))
            assertFalse("$workspace must not be plan gated", snapshot.requiresPaidAccess(workspace))
        }
    }

    @Test fun adminTestModeBypassesWorkspacePlanGatesWithoutGrantingEntitlements() {
        val catalog = org.json.JSONObject().put("plans", org.json.JSONArray().put(
            org.json.JSONObject()
                .put("id", "business-pro")
                .put("module", "business")
                .put("price", org.json.JSONObject().put("monthly", 99))
        ))
        val noEntitlements = org.json.JSONObject().put("items", org.json.JSONArray())

        val adminTesting = WorkspaceAccessSnapshot.fromCatalog(catalog, noEntitlements, admin = true, testMode = true)
        val normalAdmin = WorkspaceAccessSnapshot.fromCatalog(catalog, noEntitlements, admin = true, testMode = false)
        val normalUser = WorkspaceAccessSnapshot.fromCatalog(catalog, noEntitlements, admin = false, testMode = false)

        assertTrue(adminTesting.hasVerifiedAccess(Workspace.BUSINESS))
        assertFalse(normalAdmin.hasVerifiedAccess(Workspace.BUSINESS))
        assertFalse(normalUser.hasVerifiedAccess(Workspace.BUSINESS))
        assertTrue(adminTesting.entitlements.isEmpty())
        assertTrue(adminTesting.entitlementModules.isEmpty())
    }

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

    @Test fun markdownHidesRemoteImageLinksForPrivacy() {
        val blocks = parseBlocks("![preview](https://example.com/image.png)")
        assertEquals(1, blocks.size)
        assertTrue(blocks[0] is MdBlock.Para)
        assertEquals("External image preview hidden for privacy.", (blocks[0] as MdBlock.Para).text)
    }

    @Test fun csvDimensionsRespectQuotedCommasAndMultilineFields() {
        val dimensions = detectCsvDimensions("name,notes\nAlice,\"works in sales, finance\"\nBob,\"first line\nsecond line\"\n")
        assertEquals(2, dimensions.dataRows)
        assertEquals(2, dimensions.columns)
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
            "Show me a photo of a red sports car",
            "Can you generate an image of a superhero?",
            "Please create a flyer for my tailoring business",
            "Could you render an illustration of a city?",
            "Design an image of a fantasy castle"
        ).forEach { request ->
            assertTrue("Expected image intent for: " + request, isImageGenerationRequest(request))
        }
    }

    @Test fun imageIntentDoesNotCaptureQuestionsOrNegatedRequests() {
        listOf(
            "What is image generation?",
            "How do I create a logo?",
            "Can you explain how to make a poster?",
            "Do you know how to generate a logo?",
            "Can you tell me about image generation?",
            "What is the best way to create a poster?",
            "Don't generate an image",
            "Please do not create a logo"
        ).forEach { request ->
            assertFalse("Expected ordinary chat for: " + request, isImageGenerationRequest(request))
        }
    }

    @Test fun privateChatsAndDisabledHistoryNeverAutoSave() {
        assertFalse(shouldAutoSaveChat(privateChat = true, historyEnabled = true, autoSaveEnabled = true))
        assertFalse(shouldAutoSaveChat(privateChat = false, historyEnabled = false, autoSaveEnabled = true))
        assertFalse(shouldAutoSaveChat(privateChat = false, historyEnabled = true, autoSaveEnabled = false))
        assertTrue(shouldAutoSaveChat(privateChat = false, historyEnabled = true, autoSaveEnabled = true))
    }

    @Test fun testModeBypassesWorkspacePlanGateOnlyForAuthorizedAdmin() {
        // Keep this policy test independent of Android's mocked org.json methods.
        // Catalog parsing has separate integration coverage; this test focuses on
        // the access decision once the verified catalog snapshot has been loaded.
        fun snapshot(admin: Boolean, testMode: Boolean) = WorkspaceAccessSnapshot(
            admin = admin,
            adminTestMode = testMode,
            entitlements = emptySet(),
            entitlementModules = emptySet(),
            planModules = mapOf("business" to listOf("businesspro:paid")),
            loaded = true
        )

        val normalUser = snapshot(admin = false, testMode = true)
        val adminWithoutTestMode = snapshot(admin = true, testMode = false)
        val authorizedAdminTestMode = snapshot(admin = true, testMode = true)

        assertFalse(normalUser.hasVerifiedAccess(Workspace.BUSINESS))
        assertFalse(adminWithoutTestMode.hasVerifiedAccess(Workspace.BUSINESS))
        assertTrue(authorizedAdminTestMode.hasVerifiedAccess(Workspace.BUSINESS))
    }
}

