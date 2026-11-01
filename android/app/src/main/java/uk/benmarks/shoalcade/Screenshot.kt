package uk.benmarks.shoalcade

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.PixelCopy
import android.view.SurfaceView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Screenshot (game menu → Game → Screenshot, every console). The game picture goes into the phone's gallery
 * (Pictures/Shoalcade) through MediaStore: Android 11+ (minSdk 30) needs no storage permission for that. Named as the
 * browser player names its screenshots (play-ui.js): "<game name> <yyyy-MM-dd HH-mm-ss>.png". Only the picture leaves
 * the game: nothing about saves or snapshots changes. The pure parts ([fileName], [crop]) are unit tested.
 */
internal object Screenshot {
    const val ACTION = "Screenshot"
    const val SAVED = "Screenshot saved to your gallery."
    const val FAILED = "Couldn't take a screenshot: "
    /** Gallery folder (MediaStore RELATIVE_PATH). */
    const val FOLDER = "Pictures/Shoalcade"

    /** Characters a file name can't hold (the browser's list), plus control characters. */
    private val BAD = Regex("[\\\\/:*?\"<>|\\p{Cntrl}]+")

    /** "<name> <yyyy-MM-dd HH-mm-ss>.png", the name cleaned and cut to 80 characters ("game" if nothing is left). */
    fun fileName(game: String, at: Date): String {
        val name = game.replace(BAD, "").trim().take(80).trim().ifEmpty { "game" }
        return "$name ${SimpleDateFormat("yyyy-MM-dd HH-mm-ss", Locale.ROOT).format(at)}.png"
    }

    /** A part of a picture: [left], [top] and its size. */
    data class Crop(val left: Int, val top: Int, val width: Int, val height: Int)

    /**
     * The game picture inside a view-sized grab: the emulator draws the picture centred with black bars where the
     * view is a different shape. Whole black rows / columns are trimmed, the same amount from both sides (the bars are
     * even), so a game's own dark edge on one side is never cut. [pixels] = ARGB, [w] × [h]. Black = each colour
     * channel at most [DARK] (bars are pure black; a little slack for scaling). All black: the whole grab.
     */
    fun crop(pixels: IntArray, w: Int, h: Int): Crop {
        require(pixels.size >= w * h)
        fun dark(p: Int) = (p shr 16 and 0xFF) <= DARK && (p shr 8 and 0xFF) <= DARK && (p and 0xFF) <= DARK
        fun rowDark(y: Int) = (0 until w).all { dark(pixels[y * w + it]) }
        fun colDark(x: Int, y0: Int, y1: Int) = (y0 until y1).all { dark(pixels[it * w + x]) }
        var top = 0
        while (top < h && rowDark(top)) top++
        if (top == h) return Crop(0, 0, w, h)
        var bottom = 0
        while (rowDark(h - 1 - bottom)) bottom++
        val ty = minOf(top, bottom)
        var left = 0
        while (colDark(left, ty, h - ty)) left++
        var right = 0
        while (colDark(w - 1 - right, ty, h - ty)) right++
        val tx = minOf(left, right)
        return Crop(tx, ty, w - 2 * tx, h - 2 * ty)
    }

    const val DARK = 8

    /**
     * Grabs what [view] shows (a GLSurfaceView is a SurfaceView, so PixelCopy reads its surface, OpenGL or Vulkan),
     * trims the black bars and puts it in the gallery. [done] gets null when saved, else the reason it failed. Main thread.
     */
    fun take(ctx: Context, view: SurfaceView, game: String, done: (String?) -> Unit) {
        if (view.width <= 0 || view.height <= 0) { done("the game isn't showing"); return }
        val grab = try { Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888) }
            catch (e: OutOfMemoryError) { done("not enough memory"); return }
        val main = Handler(Looper.getMainLooper())
        try {
            PixelCopy.request(view, grab, { result ->
                if (result != PixelCopy.SUCCESS) { grab.recycle(); done("the picture couldn't be read (error $result)"); return@request }
                // Cropping and writing the PNG off the main thread, so the game doesn't stutter.
                Thread({
                    val err = try { save(ctx, grab, game); null } catch (e: Exception) { e.message ?: e.javaClass.simpleName }
                    finally { grab.recycle() }
                    main.post { done(err) }
                }, "screenshot").start()
            }, main)
        } catch (e: IllegalArgumentException) { // the surface isn't ready (or has gone)
            grab.recycle()
            done(e.message ?: "the game isn't showing")
        }
    }

    /** Crops [grab] and writes it to the gallery as a PNG. Pending until written, so the gallery never shows half a file. */
    private fun save(ctx: Context, grab: Bitmap, game: String) {
        val w = grab.width
        val h = grab.height
        val px = IntArray(w * h).also { grab.getPixels(it, 0, w, 0, 0, w, h) }
        val c = crop(px, w, h)
        val pic = if (c.width == w && c.height == h) grab else Bitmap.createBitmap(grab, c.left, c.top, c.width, c.height)
        val resolver = ctx.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName(game, Date()))
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, FOLDER)
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
            ?: throw IllegalStateException("the gallery didn't take it")
        try {
            val ok = resolver.openOutputStream(uri)?.use { pic.compress(Bitmap.CompressFormat.PNG, 100, it) } ?: false
            if (!ok) throw IllegalStateException("the picture couldn't be written")
            resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            runCatching { resolver.delete(uri, null, null) } // no half-written file left in the gallery
            throw e
        } finally {
            if (pic !== grab) pic.recycle()
        }
    }
}
