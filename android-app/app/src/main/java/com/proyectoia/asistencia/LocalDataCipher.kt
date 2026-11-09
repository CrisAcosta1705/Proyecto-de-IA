package com.proyectoia.asistencia

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class LocalDataCipher(
    private val keyAlias: String
) {
    companion object {
        private val ENVELOPE_PREFIX = byteArrayOf(0x41, 0x49, 0x41, 0x31)
        private const val NONCE_SIZE = 12
        private const val TAG_BITS = 128

        fun biometricData(): LocalDataCipher =
            LocalDataCipher("asistencia_biometric_data_v1")

        fun authenticationData(): LocalDataCipher =
            LocalDataCipher("asistencia_auth_data_v1")
    }

    private val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    fun isEncrypted(value: ByteArray): Boolean {
        if (value.size <= ENVELOPE_PREFIX.size + NONCE_SIZE) return false
        return ENVELOPE_PREFIX.indices.all { value[it] == ENVELOPE_PREFIX[it] }
    }

    fun encrypt(plaintext: ByteArray, purpose: String): ByteArray {
        val nonce = ByteArray(NONCE_SIZE).also(SecureRandom()::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey(), GCMParameterSpec(TAG_BITS, nonce))
        cipher.updateAAD(purpose.toByteArray(StandardCharsets.UTF_8))
        val ciphertext = cipher.doFinal(plaintext)
        return ENVELOPE_PREFIX + nonce + ciphertext
    }

    fun decrypt(envelope: ByteArray, purpose: String): ByteArray {
        if (!isEncrypted(envelope)) return envelope
        val nonceStart = ENVELOPE_PREFIX.size
        val ciphertextStart = nonceStart + NONCE_SIZE
        val nonce = envelope.copyOfRange(nonceStart, ciphertextStart)
        val ciphertext = envelope.copyOfRange(ciphertextStart, envelope.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        return try {
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(TAG_BITS, nonce))
            cipher.updateAAD(purpose.toByteArray(StandardCharsets.UTF_8))
            cipher.doFinal(ciphertext)
        } catch (error: AEADBadTagException) {
            throw SecurityException(
                "No se pudo verificar la integridad de los datos protegidos.",
                error
            )
        }
    }

    private fun getOrCreateKey(): SecretKey {
        (keyStore.getKey(keyAlias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            "AndroidKeyStore"
        )
        generator.init(
            KeyGenParameterSpec.Builder(
                keyAlias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }
}
