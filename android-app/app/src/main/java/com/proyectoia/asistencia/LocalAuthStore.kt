package com.proyectoia.asistencia

import android.content.Context
import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

data class LocalAuthAttempt(
    val authenticated: Boolean,
    val lockedForSeconds: Long = 0,
    val remainingAttempts: Int = 0
)

class LocalAuthStore(context: Context) {
    companion object {
        private const val PREFERENCES_NAME = "asistencia_auth"
        private const val USERNAME_KEY = "username"
        private const val PASSWORD_SALT_KEY = "password_salt"
        private const val PASSWORD_HASH_KEY = "password_hash"
        private const val SESSION_ACTIVE_KEY = "session_active"
        private const val FAILED_ATTEMPTS_KEY = "failed_attempts"
        private const val LOCKED_UNTIL_KEY = "locked_until"
        private const val HASH_ITERATIONS = 210_000
        private const val HASH_BITS = 256
        private const val MAX_FAILED_ATTEMPTS = 5
        private const val LOCK_DURATION_MS = 30_000L
    }

    private val preferences = context.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE
    )

    fun isConfigured(): Boolean {
        return !preferences.getString(USERNAME_KEY, null).isNullOrBlank() &&
            !preferences.getString(PASSWORD_SALT_KEY, null).isNullOrBlank() &&
            !preferences.getString(PASSWORD_HASH_KEY, null).isNullOrBlank()
    }

    fun isSessionActive(): Boolean {
        return isConfigured() && preferences.getBoolean(SESSION_ACTIVE_KEY, false)
    }

    fun username(): String {
        return preferences.getString(USERNAME_KEY, "").orEmpty()
    }

    fun createOrReplace(username: String, password: CharArray) {
        val salt = ByteArray(16).also(SecureRandom()::nextBytes)
        val hash = derivePassword(password, salt)
        password.fill('\u0000')
        preferences.edit()
            .putString(USERNAME_KEY, username.trim())
            .putString(PASSWORD_SALT_KEY, Base64.encodeToString(salt, Base64.NO_WRAP))
            .putString(PASSWORD_HASH_KEY, Base64.encodeToString(hash, Base64.NO_WRAP))
            .putBoolean(SESSION_ACTIVE_KEY, true)
            .putInt(FAILED_ATTEMPTS_KEY, 0)
            .remove(LOCKED_UNTIL_KEY)
            .apply()
    }

    fun authenticate(username: String, password: CharArray): LocalAuthAttempt {
        val now = System.currentTimeMillis()
        val lockedUntil = preferences.getLong(LOCKED_UNTIL_KEY, 0L)
        if (lockedUntil > now) {
            password.fill('\u0000')
            return LocalAuthAttempt(
                authenticated = false,
                lockedForSeconds = ((lockedUntil - now) + 999L) / 1_000L
            )
        }
        if (lockedUntil != 0L) {
            preferences.edit().remove(LOCKED_UNTIL_KEY).putInt(FAILED_ATTEMPTS_KEY, 0).apply()
        }

        val storedUsername = preferences.getString(USERNAME_KEY, "").orEmpty()
        val salt = decodePreference(PASSWORD_SALT_KEY)
        val expectedHash = decodePreference(PASSWORD_HASH_KEY)
        val actualHash = if (salt != null) derivePassword(password, salt) else null
        password.fill('\u0000')
        val valid = storedUsername.equals(username.trim(), ignoreCase = true) &&
            actualHash != null && expectedHash != null &&
            MessageDigest.isEqual(actualHash, expectedHash)
        if (valid) {
            preferences.edit()
                .putBoolean(SESSION_ACTIVE_KEY, true)
                .putInt(FAILED_ATTEMPTS_KEY, 0)
                .remove(LOCKED_UNTIL_KEY)
                .apply()
            return LocalAuthAttempt(authenticated = true)
        }

        val failedAttempts = preferences.getInt(FAILED_ATTEMPTS_KEY, 0) + 1
        if (failedAttempts >= MAX_FAILED_ATTEMPTS) {
            preferences.edit()
                .putInt(FAILED_ATTEMPTS_KEY, 0)
                .putLong(LOCKED_UNTIL_KEY, now + LOCK_DURATION_MS)
                .apply()
            return LocalAuthAttempt(
                authenticated = false,
                lockedForSeconds = LOCK_DURATION_MS / 1_000L
            )
        }
        preferences.edit().putInt(FAILED_ATTEMPTS_KEY, failedAttempts).apply()
        return LocalAuthAttempt(
            authenticated = false,
            remainingAttempts = MAX_FAILED_ATTEMPTS - failedAttempts
        )
    }

    fun logout() {
        preferences.edit().putBoolean(SESSION_ACTIVE_KEY, false).apply()
    }

    private fun decodePreference(key: String): ByteArray? {
        return preferences.getString(key, null)?.let { encoded ->
            runCatching { Base64.decode(encoded, Base64.NO_WRAP) }.getOrNull()
        }
    }

    private fun derivePassword(password: CharArray, salt: ByteArray): ByteArray {
        val specification = PBEKeySpec(password, salt, HASH_ITERATIONS, HASH_BITS)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                .generateSecret(specification)
                .encoded
        } finally {
            specification.clearPassword()
        }
    }
}
