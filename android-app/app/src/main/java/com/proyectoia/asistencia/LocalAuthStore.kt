package com.proyectoia.asistencia

import android.content.Context
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

enum class LocalUserRole {
    ADMINISTRATOR,
    LOCAL_USER
}

data class LocalAuthAttempt(
    val authenticated: Boolean,
    val lockedForSeconds: Long = 0,
    val remainingAttempts: Int = 0
)

class LocalAuthStore(context: Context) {
    companion object {
        private const val PREFERENCES_NAME = "asistencia_auth"
        private const val USERS_KEY = "users_v2"
        private const val CURRENT_USERNAME_KEY = "current_username"
        private const val SESSION_ACTIVE_KEY = "session_active"

        private const val LEGACY_USERNAME_KEY = "username"
        private const val LEGACY_PASSWORD_SALT_KEY = "password_salt"
        private const val LEGACY_PASSWORD_HASH_KEY = "password_hash"

        private const val HASH_ITERATIONS = 210_000
        private const val HASH_BITS = 256
        private const val MAX_FAILED_ATTEMPTS = 5
        private const val LOCK_DURATION_MS = 30_000L
        private const val MAX_ACCOUNTS = 25
    }

    private data class StoredUser(
        val username: String,
        val role: LocalUserRole,
        val salt: String,
        val passwordHash: String,
        val failedAttempts: Int = 0,
        val lockedUntil: Long = 0L
    )

    private val preferences = context.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE
    )

    init {
        migrateLegacyAdministrator()
    }

    fun isConfigured(): Boolean = loadUsers().isNotEmpty()

    fun isSessionActive(): Boolean {
        return preferences.getBoolean(SESSION_ACTIVE_KEY, false) && currentUser() != null
    }

    fun username(): String = currentUser()?.username.orEmpty()

    fun currentRole(): LocalUserRole? {
        return currentUser()?.role?.takeIf { isSessionActive() }
    }

    fun isAdministrator(): Boolean {
        return currentRole() == LocalUserRole.ADMINISTRATOR
    }

    fun createInitialAdministrator(username: String, password: CharArray) {
        require(!isConfigured()) { "La cuenta administradora ya fue configurada." }
        val administrator = createStoredUser(
            username,
            password,
            LocalUserRole.ADMINISTRATOR
        )
        saveUsers(listOf(administrator))
        preferences.edit()
            .putString(CURRENT_USERNAME_KEY, administrator.username)
            .putBoolean(SESSION_ACTIVE_KEY, true)
            .apply()
    }

    fun createLocalUser(username: String, password: CharArray): Boolean {
        if (!isAdministrator()) {
            password.fill('\u0000')
            throw IllegalStateException(
                "Solo el administrador puede crear usuarios locales."
            )
        }
        val users = loadUsers().toMutableList()
        if (users.size >= MAX_ACCOUNTS) {
            password.fill('\u0000')
            throw IllegalStateException("Se alcanzo el limite de cuentas locales.")
        }
        if (users.any { sameUsername(it.username, username) }) {
            password.fill('\u0000')
            return false
        }
        users += createStoredUser(username, password, LocalUserRole.LOCAL_USER)
        saveUsers(users)
        return true
    }

    fun updateCurrentCredentials(username: String, password: CharArray): Boolean {
        val users = loadUsers().toMutableList()
        val current = currentUser() ?: run {
            password.fill('\u0000')
            return false
        }
        val currentIndex = users.indexOfFirst { sameUsername(it.username, current.username) }
        if (currentIndex < 0) {
            password.fill('\u0000')
            return false
        }
        if (users.anyIndexed { index, user ->
                index != currentIndex && sameUsername(user.username, username)
            }
        ) {
            password.fill('\u0000')
            return false
        }
        val updated = createStoredUser(username, password, current.role)
        users[currentIndex] = updated
        saveUsers(users)
        preferences.edit()
            .putString(CURRENT_USERNAME_KEY, updated.username)
            .putBoolean(SESSION_ACTIVE_KEY, true)
            .apply()
        return true
    }

    fun authenticate(username: String, password: CharArray): LocalAuthAttempt {
        val users = loadUsers().toMutableList()
        val userIndex = users.indexOfFirst { sameUsername(it.username, username) }
        val user = users.getOrNull(userIndex)
        val now = System.currentTimeMillis()
        if (user != null && user.lockedUntil > now) {
            password.fill('\u0000')
            return LocalAuthAttempt(
                authenticated = false,
                lockedForSeconds = ((user.lockedUntil - now) + 999L) / 1_000L
            )
        }

        val salt = user?.salt?.let(::decodeBase64) ?: ByteArray(16)
        val expectedHash = user?.passwordHash?.let(::decodeBase64) ?: ByteArray(HASH_BITS / 8)
        val actualHash = try {
            derivePassword(password, salt)
        } finally {
            password.fill('\u0000')
        }
        val valid = user != null && MessageDigest.isEqual(actualHash, expectedHash)
        if (valid) {
            users[userIndex] = user.copy(failedAttempts = 0, lockedUntil = 0L)
            saveUsers(users)
            preferences.edit()
                .putString(CURRENT_USERNAME_KEY, user.username)
                .putBoolean(SESSION_ACTIVE_KEY, true)
                .apply()
            return LocalAuthAttempt(authenticated = true)
        }

        if (user == null) {
            return LocalAuthAttempt(
                authenticated = false,
                remainingAttempts = MAX_FAILED_ATTEMPTS - 1
            )
        }
        val failedAttempts = user.failedAttempts + 1
        if (failedAttempts >= MAX_FAILED_ATTEMPTS) {
            users[userIndex] = user.copy(
                failedAttempts = 0,
                lockedUntil = now + LOCK_DURATION_MS
            )
            saveUsers(users)
            return LocalAuthAttempt(
                authenticated = false,
                lockedForSeconds = LOCK_DURATION_MS / 1_000L
            )
        }
        users[userIndex] = user.copy(failedAttempts = failedAttempts, lockedUntil = 0L)
        saveUsers(users)
        return LocalAuthAttempt(
            authenticated = false,
            remainingAttempts = MAX_FAILED_ATTEMPTS - failedAttempts
        )
    }

    fun logout() {
        preferences.edit().putBoolean(SESSION_ACTIVE_KEY, false).apply()
    }

    private fun migrateLegacyAdministrator() {
        if (loadUsers().isNotEmpty()) return
        val username = preferences.getString(LEGACY_USERNAME_KEY, null)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: return
        val salt = preferences.getString(LEGACY_PASSWORD_SALT_KEY, null)
            ?.takeIf { it.isNotBlank() }
            ?: return
        val passwordHash = preferences.getString(LEGACY_PASSWORD_HASH_KEY, null)
            ?.takeIf { it.isNotBlank() }
            ?: return
        saveUsers(
            listOf(
                StoredUser(
                    username = username,
                    role = LocalUserRole.ADMINISTRATOR,
                    salt = salt,
                    passwordHash = passwordHash
                )
            )
        )
        preferences.edit()
            .putString(CURRENT_USERNAME_KEY, username)
            .apply()
    }

    private fun currentUser(): StoredUser? {
        val currentUsername = preferences.getString(CURRENT_USERNAME_KEY, "").orEmpty()
        return loadUsers().firstOrNull { sameUsername(it.username, currentUsername) }
    }

    private fun createStoredUser(
        username: String,
        password: CharArray,
        role: LocalUserRole
    ): StoredUser {
        val salt = ByteArray(16).also(SecureRandom()::nextBytes)
        val hash = try {
            derivePassword(password, salt)
        } finally {
            password.fill('\u0000')
        }
        return StoredUser(
            username = username.trim(),
            role = role,
            salt = Base64.encodeToString(salt, Base64.NO_WRAP),
            passwordHash = Base64.encodeToString(hash, Base64.NO_WRAP)
        )
    }

    private fun loadUsers(): List<StoredUser> {
        val serialized = preferences.getString(USERS_KEY, null) ?: return emptyList()
        return runCatching {
            val entries = JSONObject(serialized).optJSONArray("users") ?: JSONArray()
            buildList {
                for (index in 0 until entries.length()) {
                    val entry = entries.optJSONObject(index) ?: continue
                    val username = entry.optString("username").trim()
                    val salt = entry.optString("salt")
                    val passwordHash = entry.optString("password_hash")
                    val role = runCatching {
                        LocalUserRole.valueOf(entry.optString("role"))
                    }.getOrDefault(LocalUserRole.LOCAL_USER)
                    if (username.isBlank() || salt.isBlank() || passwordHash.isBlank()) continue
                    add(
                        StoredUser(
                            username = username,
                            role = role,
                            salt = salt,
                            passwordHash = passwordHash,
                            failedAttempts = entry.optInt("failed_attempts", 0),
                            lockedUntil = entry.optLong("locked_until", 0L)
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun saveUsers(users: List<StoredUser>) {
        val entries = JSONArray()
        users.forEach { user ->
            entries.put(
                JSONObject()
                    .put("username", user.username)
                    .put("role", user.role.name)
                    .put("salt", user.salt)
                    .put("password_hash", user.passwordHash)
                    .put("failed_attempts", user.failedAttempts)
                    .put("locked_until", user.lockedUntil)
            )
        }
        preferences.edit()
            .putString(USERS_KEY, JSONObject().put("users", entries).toString())
            .apply()
    }

    private fun sameUsername(first: String, second: String): Boolean {
        return first.trim().lowercase(Locale.ROOT) == second.trim().lowercase(Locale.ROOT)
    }

    private inline fun <T> Iterable<T>.anyIndexed(predicate: (Int, T) -> Boolean): Boolean {
        forEachIndexed { index, value -> if (predicate(index, value)) return true }
        return false
    }

    private fun decodeBase64(encoded: String): ByteArray? {
        return runCatching { Base64.decode(encoded, Base64.NO_WRAP) }.getOrNull()
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
