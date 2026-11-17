package com.bqsqwad.ai

import com.bqsqwad.ai.core.normalizeHistoryImageUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HistoryImageUrlTest {
    @Test fun websitePrivateImageLinksBecomeAbsoluteAndroidUrls() {
        val relative = "/api/file/supabase/photo/users/user-1/images/gen-1/image.png?t=signed"
        assertEquals(
            "https://bqsqwadai.com$relative",
            normalizeHistoryImageUrl(relative)
        )
    }

    @Test fun existingSecureImageUrlsRemainUnchanged() {
        val secure = "https://storage.example.com/signed-image.png"
        assertEquals(secure, normalizeHistoryImageUrl(secure))
    }

    @Test fun unsafeOrEmptyImageLinksAreNotRestored() {
        assertNull(normalizeHistoryImageUrl("http://insecure.example.com/image.png"))
        assertNull(normalizeHistoryImageUrl("javascript:alert(1)"))
        assertNull(normalizeHistoryImageUrl(""))
        assertNull(normalizeHistoryImageUrl(null))
    }
}
