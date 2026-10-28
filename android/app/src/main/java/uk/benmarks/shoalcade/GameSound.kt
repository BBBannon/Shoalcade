package uk.benmarks.shoalcade

import android.content.Context

/**
 * The one place the game's sound is decided.
 *
 * F4 / D139: LibretroDroid's low-latency audio uses Android's fast MMAP path. A phone call, alarm or headset
 * change while the game is in the background disconnects that stream while it is stopped, and LibretroDroid
 * 0.14 never reopens a stream lost that way: its restart call fails quietly and the game stays silent for the
 * rest of the session. The normal path reports the same loss through Oboe's error callback, where LibretroDroid
 * reopens the stream, so sound comes back on return. Every console plays through LibretroDroid, so this covers
 * them all.
 *
 * F5 App Mute: silences the game so music from another app can play over it (Android shares one media volume).
 * Neither the app nor LibretroDroid / Oboe asks for audio focus, so other apps' music is never paused, muted or not.
 * Kept on the phone for every game, in the plain SharedPreferences file `display` (key `mute`, no secrets).
 */
internal object GameSound {
    /** Low-latency audio stays off: it can't recover after an interruption (see above). */
    const val LOW_LATENCY = false

    /** Whether the game should make sound right now. The menu stands the game still, so it is quiet too; App Mute always is. */
    fun enabled(menuOpen: Boolean, muted: Boolean = false): Boolean = !menuOpen && !muted

    private const val MUTE_KEY = "mute"

    /** App Mute (F5); anything but "1" in the file reads as off, so sound is never lost to a damaged value. */
    fun muted(store: SyncStore): Boolean = store.get(MUTE_KEY) == "1"
    fun setMuted(store: SyncStore, on: Boolean) = store.set(MUTE_KEY, if (on) "1" else null)

    /** The phone's store for App Mute. */
    fun store(ctx: Context): SyncStore = object : SyncStore {
        private val prefs = ctx.applicationContext.getSharedPreferences("display", Context.MODE_PRIVATE)
        override fun get(key: String): String? = try { prefs.getString(key, null) } catch (_: ClassCastException) { null }
        override fun set(key: String, value: String?) {
            prefs.edit().apply { if (value == null) remove(key) else putString(key, value) }.apply()
        }
    }
}
