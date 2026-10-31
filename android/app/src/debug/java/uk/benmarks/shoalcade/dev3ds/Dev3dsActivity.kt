package uk.benmarks.shoalcade.dev3ds

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import uk.benmarks.shoalcade.Game3dsActivity
import java.io.File
import kotlin.concurrent.thread

/**
 * Shoalcade Dev only (D152, D153): "3DS test" launcher icon. Opens the user's own 3DS file from the phone (no server),
 * copies it into the app once, then starts it in [Game3dsActivity]. Never in release builds: there, games come from Jellyfin.
 */
class Dev3dsActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var list: LinearLayout
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad * 3, pad, pad) }
        col.addView(TextView(this).apply { text = "3DS test (Shoalcade Dev)"; textSize = 22f; setTextColor(Color.WHITE) })
        col.addView(TextView(this).apply {
            text = "Our own Azahar build (no keys), Vulkan, 1×. Back leaves the game. Saves stay in this test app."
            setTextColor(Color.LTGRAY); setPadding(0, pad / 2, 0, pad)
        })
        col.addView(Button(this).apply { text = "Load file from phone"; setOnClickListener { pick() } })

        // Graphics switches (Ben 30/10: glitches that standalone Azahar doesn't have). Read by Game3dsActivity at start.
        col.addView(TextView(this).apply { text = "Graphics (applies next time a game starts)"; setTextColor(Color.WHITE); setPadding(0, pad, 0, 0) })
        val prefs = Game3dsActivity.prefs(this)
        for ((key, label) in Game3dsActivity.SWITCHES) {
            col.addView(android.widget.CheckBox(this).apply {
                text = label; setTextColor(Color.LTGRAY)
                isChecked = prefs.getBoolean(key, Game3dsActivity.DEFAULTS.getValue(key))
                setOnCheckedChangeListener { _, on -> prefs.edit().putBoolean(key, on).apply() }
            })
        }
        col.addView(Button(this).apply { text = "Share log (after a glitch)"; setOnClickListener { shareLog() } })

        // 3DS check calibration (D159): the scene's load, read by the check in Shoalcade Dev only.
        col.addView(TextView(this).apply { text = "3DS check load (cubes, processor)"; setTextColor(Color.WHITE); setPadding(0, pad, 0, 0) })
        for ((key, d) in listOf("check_cubes" to uk.benmarks.shoalcade.N3dsCheck.CUBES, "check_cpu" to uk.benmarks.shoalcade.N3dsCheck.CPU)) {
            col.addView(android.widget.EditText(this).apply {
                inputType = android.text.InputType.TYPE_CLASS_NUMBER; setTextColor(Color.WHITE)
                setText(prefs.getInt(key, d).toString())
                addTextChangedListener(object : android.text.TextWatcher {
                    override fun afterTextChanged(e: android.text.Editable?) { e.toString().toIntOrNull()?.let { prefs.edit().putInt(key, it).apply() } }
                    override fun beforeTextChanged(a: CharSequence?, b: Int, c: Int, d2: Int) {}
                    override fun onTextChanged(a: CharSequence?, b: Int, c: Int, d2: Int) {}
                })
            })
        }
        col.addView(Button(this).apply {
            text = "Open the 3DS check"
            setOnClickListener { startActivity(Intent(this@Dev3dsActivity, uk.benmarks.shoalcade.N3dsCheckActivity::class.java)) }
        })
        status = TextView(this).apply { setTextColor(Color.WHITE); setPadding(0, pad, 0, pad) }
        col.addView(status)
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(list)
        setContentView(ScrollView(this).apply { setBackgroundColor(Color.rgb(18, 18, 24)); addView(col) })
        refresh()
    }

    private fun dir() = File(filesDir, "dev3ds/games").apply { mkdirs() }

    /** One "Play" button per file already copied in. */
    private fun refresh() {
        list.removeAllViews()
        dir().listFiles()?.filter { it.isFile && !it.name.endsWith(".part") }?.sortedBy { it.name }?.forEach { f ->
            list.addView(Button(this).apply {
                text = "Play ${f.name} (${f.length() / (1024 * 1024)} MB)"
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                setOnClickListener { play(f) }
            })
        }
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
        var name = "game.3ds"
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
        thread(name = "dev3ds-copy") {
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
                            if (done - last > (32 shl 20)) {
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

    /** This app's own recent log lines (Android only gives an app its own), sent anywhere as text, e.g. email to self. */
    private fun shareLog() {
        val log = runCatching {
            Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-t", "3000")).inputStream.bufferedReader().readText()
        }.getOrElse { "Couldn't read the log: ${it.message}" }
        val text = "Shoalcade Dev 3DS log (${android.os.Build.MODEL}, Android ${android.os.Build.VERSION.RELEASE})\n" +
            log.takeLast(200_000)
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"; putExtra(Intent.EXTRA_SUBJECT, "Shoalcade 3DS log"); putExtra(Intent.EXTRA_TEXT, text)
        }, "Share log"))
    }

    private fun play(f: File) {
        startActivity(Intent(this, Game3dsActivity::class.java).putExtra(Game3dsActivity.EXTRA_ROM, f.absolutePath))
    }

    private companion object { const val PICK = 3 }
}
