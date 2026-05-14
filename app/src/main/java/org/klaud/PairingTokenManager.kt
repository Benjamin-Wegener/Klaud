package org.klaud

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

data class PairingToken(
    val token: String,
    val expiresAt: Long
)

object PairingTokenManager {
    private const val PREFS_NAME = "klaud_pairing_tokens"
    private const val KEY_TOKEN = "active_token"
    private const val KEY_EXPIRES_AT = "active_token_expires_at"
    private val TOKEN_TTL_MS = TimeUnit.MINUTES.toMillis(10)
    private val REFRESH_GRACE_MS = TimeUnit.MINUTES.toMillis(1)
    private val random = SecureRandom()

    @Synchronized
    fun getOrCreateActiveToken(context: Context): PairingToken {
        val prefs = prefs(context)
        val now = System.currentTimeMillis()
        val token = prefs.getString(KEY_TOKEN, null)
        val expiresAt = prefs.getLong(KEY_EXPIRES_AT, 0L)

        if (!token.isNullOrBlank() && expiresAt > now + REFRESH_GRACE_MS) {
            return PairingToken(token, expiresAt)
        }

        val nextToken = generateToken()
        val nextExpiresAt = now + TOKEN_TTL_MS
        prefs.edit()
            .putString(KEY_TOKEN, nextToken)
            .putLong(KEY_EXPIRES_AT, nextExpiresAt)
            .apply()

        return PairingToken(nextToken, nextExpiresAt)
    }

    @Synchronized
    fun consume(context: Context, candidate: String): Boolean {
        val prefs = prefs(context)
        val token = prefs.getString(KEY_TOKEN, null) ?: return false
        val expiresAt = prefs.getLong(KEY_EXPIRES_AT, 0L)

        if (System.currentTimeMillis() > expiresAt) {
            clear(prefs)
            return false
        }

        val matched = MessageDigest.isEqual(token.toByteArray(), candidate.toByteArray())
        if (matched) clear(prefs)
        return matched
    }

    private fun generateToken(): String {
        val bytes = ByteArray(18)
        random.nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.NO_WRAP or Base64.URL_SAFE or Base64.NO_PADDING)
    }

    private fun prefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private fun clear(prefs: SharedPreferences) {
        prefs.edit().remove(KEY_TOKEN).remove(KEY_EXPIRES_AT).apply()
    }
}
