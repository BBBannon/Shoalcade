package uk.benmarks.shoalcade

import android.content.Context

/**
 * Snapshots on/off per console (D38): one setting shared by the app and browser, kept in the user's server settings.
 * The library copies it here on every refresh, so the game menu knows it offline. Unknown = on (the server default).
 * Only the on/off switch is shared: phone snapshots stay on the phone (D37), and app controls stay on the phone (D39).
 */
object SnapshotSetting {
    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("snapshots", Context.MODE_PRIVATE)

    fun enabled(ctx: Context, console: Console): Boolean = prefs(ctx).getBoolean(console.key, true)

    /** Replaces the stored settings with the server's (consoles it doesn't mention go back to on). */
    fun store(ctx: Context, byConsoleKey: Map<String, Boolean>) {
        prefs(ctx).edit().clear().apply { byConsoleKey.forEach { (k, v) -> putBoolean(k, v) } }.apply()
    }
}
