package com.bqsqwad.ai.core

import android.util.Base64
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Client-side secret chat encryption.
 *
 * New blobs use UTF-8 password bytes with PBKDF2-HMAC-SHA256 (600,000 rounds)
 * and AES-256-GCM. UTF-8 matches the website WebCrypto implementation.
 * The legacy PBKDF2 character conversion is retained only as a decrypt fallback
 * for older Android-created blobs; new encryption never uses that format.
 */
object SecretChatCrypto {
    private const val ITERATIONS = 600_000
    private const val SALT_BYTES = 16
    private const val IV_BYTES = 12
    private const val KEY_BYTES = 32
    private const val GCM_TAG_BITS = 128
    private const val MAX_CIPHERTEXT_BYTES = 675_000
    private val secureRandom = SecureRandom()

    data class EncryptedBlob(
        val salt: String,
        val iv: String,
        val ciphertext: String,
    )

    fun encrypt(password: CharArray, plaintext: ByteArray): EncryptedBlob {
        require(password.size in 8..1024) { "Secret password must contain 8 to 1024 characters." }
        require(plaintext.size <= MAX_CIPHERTEXT_BYTES - 16) { "Secret chat is too large to encrypt." }
        val salt = ByteArray(SALT_BYTES).also(secureRandom::nextBytes)
        val key = deriveKey(password, salt)
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
            val iv = cipher.iv
            check(iv.size == IV_BYTES) { "The device returned an unsupported encryption IV." }
            val cipherBytes = cipher.doFinal(plaintext)
            return EncryptedBlob(
                salt = Base64.encodeToString(salt, Base64.NO_WRAP),
                iv = Base64.encodeToString(iv, Base64.NO_WRAP),
                ciphertext = Base64.encodeToString(cipherBytes, Base64.NO_WRAP),
            )
        } finally {
            key.fill(0)
        }
    }

    fun decrypt(password: CharArray, blob: EncryptedBlob): ByteArray {
        require(password.size in 8..1024) { "Secret password must contain 8 to 1024 characters." }
        require(blob.salt.length <= 64 && blob.iv.length <= 32 &&
            blob.ciphertext.length <= ((MAX_CIPHERTEXT_BYTES + 2) / 3) * 4 + 8
        ) { "Encrypted secret chat is too large or malformed." }

        val salt = Base64.decode(blob.salt, Base64.NO_WRAP)
        val iv = Base64.decode(blob.iv, Base64.NO_WRAP)
        val ciphertext = Base64.decode(blob.ciphertext, Base64.NO_WRAP)
        require(salt.size == SALT_BYTES && iv.size == IV_BYTES &&
            ciphertext.size in 16..MAX_CIPHERTEXT_BYTES
        ) { "Encrypted secret chat is malformed." }

        val utf8Key = deriveKey(password, salt)
        try {
            return try {
                decryptWithKey(utf8Key, iv, ciphertext)
            } catch (error: AEADBadTagException) {
                // Existing Android blobs made with non-ASCII passwords used the
                // platform PBEKeySpec character conversion. Keep read compatibility.
                val legacyKey = deriveLegacyKey(password, salt)
                try {
                    decryptWithKey(legacyKey, iv, ciphertext)
                } catch (_: AEADBadTagException) {
                    throw error
                } finally {
                    legacyKey.fill(0)
                }
            }
        } finally {
            utf8Key.fill(0)
        }
    }

    private fun decryptWithKey(key: ByteArray, iv: ByteArray, ciphertext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
        return cipher.doFinal(ciphertext)
    }

    /** PBKDF2-HMAC-SHA256 over UTF-8 bytes, byte-for-byte compatible with WebCrypto. */
    internal fun deriveKey(password: CharArray, salt: ByteArray): ByteArray {
        require(salt.size == SALT_BYTES) { "Invalid secret-chat salt." }
        val passwordBytes = String(password).toByteArray(Charsets.UTF_8)
        try {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(passwordBytes, "HmacSHA256"))
            val blockInput = ByteBuffer.allocate(salt.size + 4)
                .order(ByteOrder.BIG_ENDIAN)
                .put(salt)
                .putInt(1)
                .array()

            var u = mac.doFinal(blockInput)
            val result = u.copyOf(KEY_BYTES)
            try {
                repeat(ITERATIONS - 1) {
                    val next = mac.doFinal(u)
                    u.fill(0)
                    u = next
                    for (index in result.indices) result[index] =
                        (result[index].toInt() xor u[index].toInt()).toByte()
                }
                return result
            } catch (error: Throwable) {
                result.fill(0)
                throw error
            } finally {
                u.fill(0)
                blockInput.fill(0)
            }
        } finally {
            passwordBytes.fill(0)
        }
    }

    /** Compatibility reader for Android blobs encrypted before UTF-8 alignment. */
    private fun deriveLegacyKey(password: CharArray, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(password, salt, ITERATIONS, KEY_BYTES * 8)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                .generateSecret(spec)
                .encoded
        } finally {
            spec.clearPassword()
        }
    }

    fun generateRecoveryCode(): String {
        val bytes = ByteArray(16).also(secureRandom::nextBytes)
        return bytes.joinToString("") { "%02X".format(it) }
    }
}
