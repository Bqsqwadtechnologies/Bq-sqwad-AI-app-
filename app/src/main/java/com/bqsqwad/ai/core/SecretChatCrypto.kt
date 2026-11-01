package com.bqsqwad.ai.core

import android.util.Base64
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Client-side secret chat encryption (Prompt H Part 6).
 * PBKDF2-HMAC-SHA256 with 600,000 iterations → AES-256-GCM.
 * Server stores only ciphertext.
 */
object SecretChatCrypto {
    private const val ITERATIONS = 600_000
    private const val SALT_BYTES = 16
    private const val IV_BYTES = 12
    private const val KEY_BITS = 256
    private const val GCM_TAG_BITS = 128

    data class EncryptedBlob(
        val salt: String,
        val iv: String,
        val ciphertext: String,
    )

    fun encrypt(password: CharArray, plaintext: ByteArray): EncryptedBlob {
        val salt = ByteArray(SALT_BYTES).also { SecureRandom().nextBytes(it) }
        val key = deriveKey(password, salt)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
        val iv = cipher.iv
        val cipherBytes = cipher.doFinal(plaintext)
        return EncryptedBlob(
            salt = Base64.encodeToString(salt, Base64.NO_WRAP),
            iv = Base64.encodeToString(iv, Base64.NO_WRAP),
            ciphertext = Base64.encodeToString(cipherBytes, Base64.NO_WRAP),
        )
    }

    fun decrypt(password: CharArray, blob: EncryptedBlob): ByteArray {
        val salt = Base64.decode(blob.salt, Base64.NO_WRAP)
        val iv = Base64.decode(blob.iv, Base64.NO_WRAP)
        val ciphertext = Base64.decode(blob.ciphertext, Base64.NO_WRAP)
        val key = deriveKey(password, salt)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
        return cipher.doFinal(ciphertext)
    }

    private fun deriveKey(password: CharArray, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(password, salt, ITERATIONS, KEY_BITS)
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(spec)
            .encoded
    }

    fun generateRecoveryCode(): String {
        val bytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
        return bytes.joinToString("") { "%02X".format(it) }
    }
}
