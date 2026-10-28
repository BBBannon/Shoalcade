package uk.benmarks.shoalcade

import android.content.Context
import java.io.File
import java.io.IOException
import java.util.zip.ZipInputStream

/**
 * PPSSPP needs its support files (fonts, compat list, UI atlas; no Sony BIOS) in <system>/PPSSPP.
 * They ship inside the APK as assets/PPSSPP.zip and are unpacked once, the first time they are needed.
 */
object PspAssets {
    /** Same folder GameActivity gives the cores as the libretro system directory. */
    fun systemDir(ctx: Context) = File(ctx.filesDir, "system")

    private fun marker(ctx: Context) = File(systemDir(ctx), "PPSSPP/.shoalcade-version")

    /** Unpacks the support files if missing or from an older app version. Safe to call often. */
    @Synchronized
    fun ensure(ctx: Context) {
        val version = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).longVersionCode }.getOrDefault(0L).toString()
        val m = marker(ctx)
        if (m.isFile && m.readText() == version) return
        val dest = File(systemDir(ctx), "PPSSPP")
        dest.deleteRecursively()
        val base = systemDir(ctx).apply { mkdirs() }.canonicalPath + File.separator
        ZipInputStream(ctx.assets.open("PPSSPP.zip").buffered()).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                val target = File(systemDir(ctx), e.name)
                if (!target.canonicalPath.startsWith(base)) throw IOException("Bad file in PSP support files.")
                if (e.isDirectory) { target.mkdirs(); continue }
                target.parentFile?.mkdirs()
                target.outputStream().use { zip.copyTo(it) }
            }
        }
        m.writeText(version) // written last: a half-finished unpack is redone next time
    }
}
