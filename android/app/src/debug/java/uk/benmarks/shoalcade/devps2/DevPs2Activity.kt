package uk.benmarks.shoalcade.devps2

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import kotlin.concurrent.thread

/**
 * Shoalcade Dev only: "PS2 test" launcher icon (to-do "PS2 on the phone"). Copies the user's own PS2 disc from the
 * phone into the app once, then plays it in [Ps2TestActivity] with the Play! core. Never in release builds.
 */
class DevPs2Activity : Activity() {

    private lateinit var status: TextView
    private lateinit var list: LinearLayout
    private lateinit var resultsView: TextView
    private var busy = false
    private var res = "1x"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val prefs = getSharedPreferences("devps2", MODE_PRIVATE)
        res = prefs.getString("res", "1x") ?: "1x"

        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad * 3, pad, pad) }
        col.addView(TextView(this).apply { text = "PS2 test (Shoalcade Dev)"; textSize = 22f; setTextColor(Color.WHITE) })
        col.addView(TextView(this).apply {
            text = "Play! core (open source, no BIOS needed), OpenGL. Speed shows at the top; Back leaves the game " +
                "and saves a result below. Full speed shows 60 fps, or 30 for games made for 30 fps (most 3D ones). " +
                "Play each game for 3-5 minutes in a busy part. Saves stay in this test app."
            setTextColor(Color.LTGRAY); setPadding(0, pad / 2, 0, pad)
        })
        col.addView(Button(this).apply { text = "Load PS2 disc from phone (.iso, .chd, .cso)"; setOnClickListener { pick() } })

        col.addView(TextView(this).apply { text = "Picture size (try 1x first, then 2x)"; setTextColor(Color.WHITE); setPadding(0, pad, 0, 0) })
        col.addView(RadioGroup(this).apply {
            orientation = RadioGroup.HORIZONTAL
            for (r in listOf("1x", "2x", "4x")) addView(RadioButton(this@DevPs2Activity).apply {
                id = View_ID + r[0].digitToInt(); text = r; setTextColor(Color.LTGRAY); isChecked = r == res
                setOnCheckedChangeListener { _, on -> if (on) { res = r; prefs.edit().putString("res", r).apply() } }
            })
        })

        status = TextView(this).apply { setTextColor(Color.WHITE); setPadding(0, pad, 0, pad) }
        col.addView(status)
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(list)

        col.addView(TextView(this).apply { text = "Results (newest first)"; setTextColor(Color.WHITE); setPadding(0, pad, 0, 0) })
        resultsView = TextView(this).apply { setTextColor(Color.LTGRAY); setTextIsSelectable(true); setPadding(0, pad / 2, 0, pad / 2) }
        col.addView(resultsView)
        col.addView(Button(this).apply { text = "Share results"; setOnClickListener { share() } })
        setContentView(ScrollView(this).apply { setBackgroundColor(Color.rgb(18, 18, 24)); addView(col) })
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun dir() = File(filesDir, "devps2/games").apply { mkdirs() }

    private fun refresh() {
        list.removeAllViews()
        dir().listFiles()?.filter { it.isFile && !it.name.endsWith(".part") }?.sortedBy { it.name }?.forEach { f ->
            list.addView(Button(this).apply {
                text = "Play ${f.name} (${f.length() / (1024 * 1024)} MB)"
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                setOnClickListener { play(f) }
                setOnLongClickListener { f.delete(); refresh(); true } // free the space after testing
            })
        }
        if (list.childCount > 0) list.addView(TextView(this).apply { text = "Long-press a game to delete its copy."; setTextColor(Color.GRAY) })
        val r = results(this)
        resultsView.text = if (r.isFile) r.readText().trim() else "None yet."
    }

    private fun pick() {
        if (busy) return
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE); type = "*/*"
        }, PICK)
    }

    @Deprecated("Simple dev screen")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (requestCode == PICK && resultCode == RESULT_OK && uri != null) copy(uri)
    }

    private fun copy(uri: Uri) {
        var name = "game.iso"
        var size = -1L
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                c.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { name = c.getString(it) ?: name }
                c.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 }?.let { if (!c.isNull(it)) size = c.getLong(it) }
            }
        }
        name = name.replace(Regex("[\\\\/:*?\"<>|]"), "_")
        val dest = File(dir(), name)
        if (dest.exists() && size > 0 && dest.length() == size) { play(dest); return }
        busy = true
        status.text = "Copying $name…"
        thread(name = "devps2-copy") {
            val part = File(dir(), "$name.part")
            val result = runCatching {
                contentResolver.openInputStream(uri)!!.use { input ->
                    part.outputStream().use { out ->
                        val buf = ByteArray(1 shl 20)
                        var done = 0L
                        var last = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            if (done - last > (64 shl 20)) {
                                last = done
                                val pct = if (size > 0) " ${done * 100 / size}%" else ""
                                runOnUiThread { status.text = "Copying $name… ${done shr 20} MB$pct" }
                            }
                        }
                    }
                }
                if (!part.renameTo(dest)) error("couldn't rename the copy")
            }
            runOnUiThread {
                busy = false
                result.onSuccess { status.text = "Copied $name"; refresh(); play(dest) }
                    .onFailure { part.delete(); status.text = "Copy failed: ${it.message}" }
            }
        }
    }

    private fun share() {
        val r = results(this)
        val text = "Shoalcade PS2 test (Play! core) on ${android.os.Build.MODEL}, Android ${android.os.Build.VERSION.RELEASE}\n\n" +
            (if (r.isFile) r.readText() else "No results yet.")
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"; putExtra(Intent.EXTRA_SUBJECT, "Shoalcade PS2 test results"); putExtra(Intent.EXTRA_TEXT, text)
        }, "Share results"))
    }

    private fun play(f: File) {
        startActivity(Intent(this, Ps2TestActivity::class.java)
            .putExtra(Ps2TestActivity.EXTRA_ROM, f.absolutePath)
            .putExtra(Ps2TestActivity.EXTRA_RES, res))
    }

    companion object {
        private const val PICK = 4
        private const val View_ID = 0x5032

        fun results(c: Context) = File(c.filesDir, "devps2/results.txt")
    }
}
