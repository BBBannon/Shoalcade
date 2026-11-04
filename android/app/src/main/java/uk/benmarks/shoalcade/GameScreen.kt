package uk.benmarks.shoalcade

import android.app.AlertDialog
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/*
 * Pieces both game screens do the same way: GameActivity (every console but 3DS) and Game3dsActivity (3DS, on its own
 * emulator layer, D153). Only plain Android views and the save sync here, never either emulator, so neither screen's
 * emulator code is shared or changed.
 */

/** Adds [v] over the whole of this root. */
internal fun FrameLayout.addFullSize(v: View) =
    addView(v, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

/** True if the touch [ev] lands on [v] (both in screen coordinates). */
internal fun hits(v: View, ev: MotionEvent): Boolean {
    val at = IntArray(2).also { v.getLocationOnScreen(it) }
    return ev.rawX >= at[0] && ev.rawX < at[0] + v.width && ev.rawY >= at[1] && ev.rawY < at[1] + v.height
}

/** Lets go of any finger on the touch buttons or the game under [root] (the menu is taking over). */
internal fun cancelTouches(root: View) {
    val now = android.os.SystemClock.uptimeMillis()
    val cancel = MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, 0f, 0f, 0)
    root.dispatchTouchEvent(cancel)
    cancel.recycle()
}

/** TalkBack mustn't wander onto the game and touch buttons ([views]) hidden behind the menu or a screen over the game. */
internal fun setGameAccessible(on: Boolean, vararg views: View) {
    val mode = if (on) View.IMPORTANT_FOR_ACCESSIBILITY_AUTO else View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
    for (v in views) v.importantForAccessibility = mode
}

/** Menu → Game → Screenshot: the drawer is closed first, then one frame later the picture of [game] is taken. */
internal fun ComponentActivity.screenshotNextFrame(root: View, game: SurfaceView, name: String, toast: (String) -> Unit) {
    root.postOnAnimation {
        root.postOnAnimation {
            if (isFinishing || isDestroyed) return@postOnAnimation
            Screenshot.take(this, game, name) { err -> toast(if (err == null) Screenshot.SAVED else Screenshot.FAILED + err) }
        }
    }
}

/**
 * The loading screen's signal: looks at a small copy of [view] every 250 ms while [stillLoading]. True once the game
 * shows something that isn't black ([FirstPicture]); false once [safetyMs] have passed (a safety net only: never
 * leave the loading screen over a running game for good); null if [stillLoading] went false first.
 */
internal suspend fun awaitFirstPicture(view: SurfaceView, safetyMs: Long, stillLoading: () -> Boolean): Boolean? {
    val bmp = Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888)
    val px = IntArray(64 * 48)
    val t0 = System.currentTimeMillis()
    while (stillLoading()) {
        val done = CompletableDeferred<Boolean>()
        val asked = runCatching {
            PixelCopy.request(view, bmp, { r -> done.complete(r == PixelCopy.SUCCESS) }, Handler(Looper.getMainLooper()))
        }.isSuccess
        val got = asked && withTimeoutOrNull(2_000) { done.await() } == true
        if (got) bmp.getPixels(px, 0, 64, 0, 0, 64, 48)
        val lit = got && FirstPicture.lit(px)
        if (lit || System.currentTimeMillis() - t0 > safetyMs) return lit
        delay(250)
    }
    return null
}

/**
 * Close Game (and Upload)'s two questions, the same on every game screen. Each choice ends with the screen closing.
 * Saves are precious: the other copy is always kept (Jellyfin's history and the phone's backups).
 */
internal class CloseQuestions(private val activity: ComponentActivity, private val toast: (String) -> Unit) {

    /** Replacing a newer Jellyfin save: both dates shown; Jellyfin's is kept in its history (and the phone's backups) first. */
    fun replaceNewer(sync: SaveSync, game: Game, r: SyncResult.NeedsConfirm) {
        AlertDialog.Builder(activity)
            .setTitle("Replace a newer save?")
            .setMessage(r.message)
            .setCancelable(false)
            .setPositiveButton("Upload anyway") { _, _ -> finishWith { sync.keepPhone(game) } }
            .setNegativeButton("Don't upload") { _, _ -> toast("Not uploaded. Your save is kept on the phone."); activity.finish() }
            .show()
    }

    /**
     * Both changed (D35): same choices as the library's question, never settled silently. [elsewhere] = where the other
     * save may have come from, in the screen's words (e.g. "the browser or another phone").
     */
    fun conflict(sync: SaveSync, game: Game, userId: String, r: SyncResult.Conflict, elsewhere: String) {
        val (phone, jf) = SavePlan.dateLines(Storage.saveTime(userId, game), r.server)
        AlertDialog.Builder(activity)
            .setTitle("Which save should be in use?")
            .setMessage("${game.name} has a different save on this phone and on Jellyfin (maybe from $elsewhere).\n\n" +
                "$phone\n$jf\n\nNothing is lost either way: the other save is kept in Jellyfin's history and in the phone's backups.")
            .setCancelable(false)
            .setPositiveButton("Keep the phone's") { _, _ -> finishWith { sync.keepPhone(game) } }
            .setNeutralButton("Decide later") { _, _ ->
                toast("Not uploaded. Your save is kept on the phone; tap ⚠️ in the library to choose."); activity.finish()
            }
            .apply { if (r.server != null) setNegativeButton("Keep Jellyfin's") { _, _ -> finishWith { sync.keepServer(game) } } }
            .show()
    }

    private fun finishWith(op: suspend () -> SyncResult) {
        activity.lifecycleScope.launch { toast(op().message); activity.finish() }
    }
}
