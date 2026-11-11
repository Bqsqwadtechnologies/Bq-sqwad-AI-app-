package com.bqsqwad.ai.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SecretChatCryptoTest {
    @Test
    fun utf8Pbkdf2MatchesWebCryptoReferenceVector() {
        val salt = ByteArray(16) { it.toByte() }
        val key = SecretChatCrypto.deriveKey("pässword-🔐".toCharArray(), salt)

        assertEquals(
            "077d8bc168c1d1cd09213f8405a1ae43788452aaff6d5030deeafb74f7f69568",
            key.joinToString("") { "%02x".format(it) },
        )
        assertEquals(32, key.size)
        key.fill(0)
    }

    @Test
    fun invalidSaltIsRejectedBeforeKeyDerivation() {
        assertThrows(IllegalArgumentException::class.java) {
            SecretChatCrypto.deriveKey("password123".toCharArray(), ByteArray(8))
        }
    }
}
