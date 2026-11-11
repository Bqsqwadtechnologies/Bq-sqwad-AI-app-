package com.bqsqwad.ai.core

import org.junit.Assert.assertArrayEquals
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
        key.fill(0)
    }

    @Test
    fun unicodePasswordEncryptsAndDecryptsAcrossTheSharedContract() {
        val password = "pässword-🔐".toCharArray()
        val plaintext = """{"title":"Private","transcript":"Unicode secret 🔐"}""".toByteArray(Charsets.UTF_8)

        val encrypted = SecretChatCrypto.encrypt(password, plaintext)
        val decrypted = SecretChatCrypto.decrypt(password, encrypted)

        assertArrayEquals(plaintext, decrypted)
    }

    @Test
    fun malformedCiphertextIsRejectedBeforeDecryption() {
        val malformed = SecretChatCrypto.EncryptedBlob(
            salt = android.util.Base64.encodeToString(ByteArray(16), android.util.Base64.NO_WRAP),
            iv = android.util.Base64.encodeToString(ByteArray(8), android.util.Base64.NO_WRAP),
            ciphertext = android.util.Base64.encodeToString(ByteArray(16), android.util.Base64.NO_WRAP),
        )

        assertThrows(IllegalArgumentException::class.java) {
            SecretChatCrypto.decrypt("password123".toCharArray(), malformed)
        }
    }
}
