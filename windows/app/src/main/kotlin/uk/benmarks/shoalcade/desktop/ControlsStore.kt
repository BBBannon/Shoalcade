package uk.benmarks.shoalcade.desktop

import android.content.Context
import org.json.JSONObject

/**
 * The buttons changed in the game menu's Controls (PROTOCOL.md `controlsChanged`), kept on this PC (the `controls`
 * store): the keyboard and the shortcut keys per console (`k.<console>`, `h.<console>`, as the browser keeps
 * `prefs[console].controls`), and a controller's buttons per console and controller model (`p.<console>.<model>`, the
 * phone's rule, D126). The next game of that console starts with them (`start.controls`).
 */
class ControlsStore(ctx: Context) {
    private val prefs = ctx.getSharedPreferences("controls", Context.MODE_PRIVATE)

    /** The player's `controlsChanged`: each part it carries is kept, or removed when it is null (back to the defaults). */
    fun changed(e: JSONObject) {
        val console = e.optString("console").takeIf { it.isNotBlank() && it.all { c -> c.isLetterOrDigit() } } ?: return
        val edit = prefs.edit()
        fun keep(key: String, value: Any?) {
            if (value is JSONObject) edit.putString(key, value.toString()) else edit.remove(key)
        }
        if (e.has("keyboard")) keep("k.$console", e.opt("keyboard"))
        if (e.has("hotkeys")) keep("h.$console", e.opt("hotkeys"))
        e.optJSONObject("gamepads")?.let { pads ->
            for (model in pads.keySet()) if (model.isNotBlank()) keep("p.$console.$model", pads.opt(model))
        }
        edit.apply()
    }

    /** What the player's `start.controls` gets for [console] (null = nothing changed: the player's defaults). */
    fun start(console: String): JSONObject? {
        val out = JSONObject()
        read("k.$console")?.let { out.put("keyboard", it) }
        read("h.$console")?.let { out.put("hotkeys", it) }
        val prefix = "p.$console."
        val pads = JSONObject()
        for (key in prefs.getAll().keys.sorted()) if (key.startsWith(prefix)) read(key)?.let { pads.put(key.removePrefix(prefix), it) }
        if (pads.length() > 0) out.put("gamepads", pads)
        return out.takeIf { it.length() > 0 }
    }

    private fun read(key: String): JSONObject? = prefs.getString(key, null)?.let { runCatching { JSONObject(it) }.getOrNull() }
}
