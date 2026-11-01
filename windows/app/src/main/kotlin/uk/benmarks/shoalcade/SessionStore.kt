package uk.benmarks.shoalcade

import android.content.Context
import org.json.JSONObject
import uk.benmarks.shoalcade.desktop.Log
import uk.benmarks.shoalcade.desktop.TokenStore
import java.io.File
import java.util.UUID

/**
 * The desktop's SessionStore: the same API as the phone's (android/.../Session.kt lines 17-63: deviceId, load, save,
 * clear), so code written against the phone's can use it. The login goes into `session.bin`, sealed by [TokenStore]
 * (Windows DPAPI); the device id is not secret and sits in the plain `device` preferences.
 */
class SessionStore(
    context: Context,
    private val tokens: TokenStore = TokenStore(File(context.filesDir, "session.bin"), TokenStore.sealerForThisComputer()),
) {
    private val prefs = context.getSharedPreferences("device", Context.MODE_PRIVATE)

    /** Stable per-install id; Jellyfin uses it to tell devices apart. */
    val deviceId: String
        @Synchronized get() = prefs.getString("deviceId", null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString("deviceId", it).commit()
        }

    fun load(): Session? {
        val bytes = tokens.load() ?: return null
        return runCatching {
            val o = JSONObject(String(bytes, Charsets.UTF_8))
            Session(o.getString("server"), o.getString("userId"), o.optString("userName", ""), o.getString("token"))
        }.getOrNull()?.also { Log.secret(it.token) }
    }

    fun save(s: Session) {
        Log.secret(s.token)
        val o = JSONObject().put("server", s.serverUrl).put("userId", s.userId).put("userName", s.userName).put("token", s.token)
        tokens.save(o.toString().toByteArray(Charsets.UTF_8))
    }

    fun clear() = tokens.clear()
}
