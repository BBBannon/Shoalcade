package uk.benmarks.shoalcade

import android.content.Context

/**
 * The menu's Display choices kept on the phone (D131), in the plain SharedPreferences file `display` (no secrets).
 *
 * - **Screen** is kept per console (`screen.<console>`): e.g. GBA on its side, GB upright. PSP ignores it (always landscape).
 * - **DS screens** and **Swap** are kept per way up (`ds.land` / `ds.port`, `swap.land` / `swap.port`); only the DS uses them.
 *
 * Anything unknown or damaged in the file reads as the default, so a bad value can never stop a game starting.
 */
class DisplayPrefs(private val store: SyncStore, private val console: Console) : DisplayChoices {
    override var screen: ScreenMode
        get() = ScreenMode.fromKey(store.get("screen.${console.key}"))
        set(v) = store.set("screen.${console.key}", v.key)

    override fun ds(landscape: Boolean) = DsLayout.fromKey(store.get("ds.${way(landscape)}")) ?: DsLayout.default(landscape)
    override fun setDs(landscape: Boolean, layout: DsLayout) = store.set("ds.${way(landscape)}", layout.key)

    override fun swap(landscape: Boolean) = store.get("swap.${way(landscape)}") == "1"
    override fun setSwap(landscape: Boolean, on: Boolean) = store.set("swap.${way(landscape)}", if (on) "1" else null)

    private fun way(landscape: Boolean) = if (landscape) "land" else "port"

    companion object {
        fun of(ctx: Context, console: Console) = DisplayPrefs(object : SyncStore {
            private val prefs = ctx.applicationContext.getSharedPreferences("display", Context.MODE_PRIVATE)
            // getString throws if the key holds another type (damaged file): treat that as "not set".
            override fun get(key: String): String? = try { prefs.getString(key, null) } catch (_: ClassCastException) { null }
            override fun set(key: String, value: String?) {
                prefs.edit().apply { if (value == null) remove(key) else putString(key, value) }.apply()
            }
        }, console)
    }
}
