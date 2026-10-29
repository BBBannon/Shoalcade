package uk.benmarks.shoalcade

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Typing in RPG Maker games (names and the like, D147). The engine has no text input of its own, so typed text goes to
 * the game through `.shoalcade-typing` in the game's save folder, which shoalcade_compat.rb reads (a backspace is
 * a backspace character, Enter / Done a line break). The game says when it wants text through `.shoalcade-keyboard`
 * ("1"/"0"); the phone keyboard then opens and closes by itself (and while it wants text, OK and Back also count as
 * Enter and Escape). Both are dot files, so they
 * are never part of the synced save. Pure parts are unit-tested ([folderFor], [encode]).
 */
object RpgTyping {
    /** Escape (cancel) in the typing file. */
    const val ESCAPE = "\u001b"
    const val TYPED = ".shoalcade-typing"
    const val WANTED = ".shoalcade-keyboard"

    /** The game's own save folder: the one folder under mkxp-z/Saves (the newest if there is ever more than one). */
    fun folderFor(saves: File): File? = saves.listFiles { f -> f.isDirectory }?.maxByOrNull { it.lastModified() }

    /** What a piece of typing adds to the file: the text itself, with line breaks dropped. */
    fun encode(text: CharSequence): String = text.toString().replace("\r", "").replace("\n", "")

    /**
     * The keyboard's word being typed changes from [old] to [new] (already sent to the game): how many characters to
     * delete and what to add after. Counts whole characters (code points), as the game does.
     */
    fun compose(old: String, new: String): Pair<Int, String> {
        val a = old.codePoints().toArray()
        val b = new.codePoints().toArray()
        var same = 0
        while (same < a.size && same < b.size && a[same] == b[same]) same++
        return (a.size - same) to String(b, same, b.size - same)
    }

    /** The view that takes the phone keyboard's typing while a game is running. */
    class Receiver(ctx: Context, private val saves: File) : View(ctx) {
        private val handler = Handler(Looper.getMainLooper())
        /** The two files are read and written here, in order, off the main thread. */
        private val io = Executors.newSingleThreadScheduledExecutor()
        private var shown = false
        private var running = false

        init { isFocusable = true; isFocusableInTouchMode = true }

        private fun folder(): File? = folderFor(saves)

        /** Runs [job] on [io]; a failure (or a stopped game) just drops it. */
        private fun onIo(job: () -> Unit) { runCatching { io.execute { runCatching(job) } } }

        private fun append(s: String) {
            if (s.isNotEmpty()) onIo { folder()?.let { File(it, TYPED).appendText(s, Charsets.UTF_8) } }
        }

        fun type(text: CharSequence) = append(encode(text))
        fun backspace(times: Int = 1) = append("\b".repeat(times.coerceIn(0, 64)))
        /** Enter / Done on the keyboard: the game's Enter key ("\n" in the file). */
        private fun pressOk() = append("\n")

        /** A new game session: nothing typed yet, keyboard closed. */
        fun start() {
            onIo { folder()?.let { d -> File(d, TYPED).writeText(""); File(d, WANTED).writeText("0") } }
            running = true
            io.scheduleWithFixedDelay({
                val want = runCatching { folder()?.let { File(it, WANTED).readText().trim() == "1" } == true }.getOrDefault(false)
                handler.post { if (running && want != shown) show(want) }
            }, 0, 250, TimeUnit.MILLISECONDS)
        }

        fun stop() { running = false; io.shutdown(); show(false) }

        private fun show(on: Boolean) {
            shown = on
            val imm = context.getSystemService(InputMethodManager::class.java) ?: return
            if (on) { requestFocus(); imm.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT) }
            else imm.hideSoftInputFromWindow(windowToken, 0)
        }

        override fun onCheckIsTextEditor() = shown

        override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
            outAttrs.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            outAttrs.imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN
            return object : BaseInputConnection(this, false) {
                // Keyboards such as Gboard type a word as "composing" text and only commit it at a space or
                // punctuation, so a name typed then Done never reached the game. The game gets the word as it is
                // typed: each change to it is sent as backspaces plus the new letters ([compose]).
                private var composing = ""
                private fun replaceComposing(text: CharSequence) {
                    val (back, add) = compose(composing, encode(text))
                    backspace(back); type(add)
                }
                override fun setComposingText(text: CharSequence, newCursorPosition: Int): Boolean {
                    replaceComposing(text); composing = encode(text); return true
                }
                override fun finishComposingText(): Boolean { composing = ""; return true }
                override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean { replaceComposing(text); composing = ""; return true }
                override fun deleteSurroundingText(before: Int, after: Int): Boolean { composing = ""; backspace(before); return true }
                override fun performEditorAction(actionCode: Int): Boolean { composing = ""; pressOk(); return true }
                override fun sendKeyEvent(event: KeyEvent): Boolean {
                    if (event.action == KeyEvent.ACTION_DOWN) { composing = ""; press(event) }
                    return true
                }
            }
        }

        /**
         * The phone's Back key while the game wants text: the game's Escape key (cancel), as the touch Back button is
         * under the keyboard. The keyboard stays open until the game says it's done.
         */
        override fun onKeyPreIme(keyCode: Int, event: KeyEvent): Boolean {
            if (shown && keyCode == KeyEvent.KEYCODE_BACK) {
                if (event.action == KeyEvent.ACTION_UP) append(ESCAPE)
                return true
            }
            return super.onKeyPreIme(keyCode, event)
        }

        /** A physical keyboard while the game wants text: letters type, Backspace deletes, Enter is OK. */
        fun key(event: KeyEvent): Boolean {
            if (!shown) return false
            if (event.action != KeyEvent.ACTION_DOWN) return event.keyCode == KeyEvent.KEYCODE_DEL || event.unicodeChar > 0
            if (event.keyCode == KeyEvent.KEYCODE_ESCAPE) { append(ESCAPE); return true }
            return press(event)
        }

        /** Backspace, Enter or a typed character; false for any other key. */
        private fun press(event: KeyEvent): Boolean = when {
            event.keyCode == KeyEvent.KEYCODE_DEL -> { backspace(); true }
            event.keyCode == KeyEvent.KEYCODE_ENTER -> { pressOk(); true }
            event.unicodeChar > 0 -> { type(String(Character.toChars(event.unicodeChar))); true }
            else -> false
        }
    }
}
