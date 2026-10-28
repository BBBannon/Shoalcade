package uk.benmarks.shoalcade

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * F3: is the app connected to the Jellyfin server? Connected = the last call to the server got an answer and the login
 * was accepted. Any answer proves the server is there, even a refusal (404, 409, 422); no answer, a timeout, a Wi-Fi
 * sign-in page or an expired login (401) means not connected. Logged out always shows as not connected (the screen
 * checks the session too).
 *
 * Every [JellyfinClient] server call reports here, so there's no polling. The library adds a light check (the
 * plugin's `/shoalcade/info`) on start, on return to the app and when the phone's network changes.
 * Plain Kotlin, so the JVM tests run exactly this code.
 */
object ServerStatus {
    private val state = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = state.asStateFlow()

    /** When the last answer (or failure) came, in ms; 0 = none yet. Lets the light check skip a just-made call. */
    @Volatile var lastReport = 0L
        private set

    fun report(ok: Boolean) {
        lastReport = System.currentTimeMillis()
        state.value = ok
    }

    /**
     * True when a call ANSWERED in the last [gapMs], so a light check can be skipped. A recent failure never counts,
     * or a quick network drop and return would leave the light red (G6 delta M1).
     */
    fun recentlyConnected(gapMs: Long, now: Long = System.currentTimeMillis()) = state.value && now - lastReport < gapMs

    /** Logged out or the phone lost its network. */
    fun disconnected() = report(false)

    /** The settings button's spoken name: says the state in words, not just colour. */
    fun label(connected: Boolean) =
        "${SettingsModel.TITLE} — " + if (connected) "connected to server" else "not connected to server"
}
