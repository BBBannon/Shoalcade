package uk.benmarks.shoalcade

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.util.UUID

/** Logged-in Jellyfin session. The token is kept in encrypted preferences (Android Keystore). */
data class Session(
    val serverUrl: String,
    val userId: String,
    val userName: String,
    val token: String,
)

class SessionStore(context: Context) {
    private val prefs: SharedPreferences = try {
        open(context)
    } catch (e: Exception) {
        // Keystore entry broken (e.g. after a restore): wipe and start fresh. The user just logs in again.
        context.deleteSharedPreferences("session")
        runCatching {
            java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
                .deleteEntry(MasterKey.DEFAULT_MASTER_KEY_ALIAS)
        }
        open(context)
    }

    private fun open(context: Context) = EncryptedSharedPreferences.create(
        context,
        "session",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    /** Stable per-install ID; Jellyfin uses it to tell devices apart. */
    val deviceId: String
        get() = prefs.getString("deviceId", null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString("deviceId", it).apply()
        }

    fun load(): Session? {
        val server = prefs.getString("server", null) ?: return null
        val userId = prefs.getString("userId", null) ?: return null
        val token = prefs.getString("token", null) ?: return null
        return Session(server, userId, prefs.getString("userName", "") ?: "", token)
    }

    fun save(s: Session) {
        prefs.edit()
            .putString("server", s.serverUrl)
            .putString("userId", s.userId)
            .putString("userName", s.userName)
            .putString("token", s.token)
            .apply()
    }

    fun clear() {
        prefs.edit().remove("server").remove("userId").remove("userName").remove("token").apply()
    }
}
