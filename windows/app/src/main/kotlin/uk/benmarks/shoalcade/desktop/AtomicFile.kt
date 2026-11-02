package uk.benmarks.shoalcade.desktop

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions

/**
 * Whole-file writes that can never leave half a file: unique temp file beside the target, flush to disk, then an
 * atomic rename over it. The same rule as the phone's `Storage.writeAtomically` (android/.../Storage.kt, "Unique temp
 * file + fsync + rename"), which is private there; Storage itself uses its own copy for saves. Used here for the
 * desktop's own small files (preferences, the game list cache, the saved login, box art).
 */
object AtomicFile {
    /** [ownerOnly]: POSIX 0600 before any byte is written (the login file on Linux dev builds). */
    fun write(f: File, bytes: ByteArray, ownerOnly: Boolean = false) {
        f.parentFile?.mkdirs()
        val tmp = File.createTempFile(f.name, ".tmp", f.parentFile)
        try {
            if (ownerOnly) Files.setPosixFilePermissions(tmp.toPath(), PosixFilePermissions.fromString("rw-------"))
            FileOutputStream(tmp).use { it.write(bytes); it.fd.sync() }
            // Windows can refuse to replace a file a scanner holds for a moment: try again briefly (as Storage does).
            var tries = 0
            while (true) {
                try {
                    Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                    break
                } catch (e: AccessDeniedException) {
                    if (++tries >= 20) throw IOException("Couldn't write ${f.name}.", e)
                    Thread.sleep(25)
                }
            }
        } finally {
            tmp.delete() // no-op after a successful move
        }
    }

    /**
     * Pictures only (box art, snapshot pictures fetched from Jellyfin), never saves: the player's `write_file_direct`
     * rule (D225). Antivirus that watches for "write X.tmp, rename onto X.png/.jpg" (Trend Micro killed processes on
     * the 3rd such rename) sees a plain write: any old file is deleted, the new one created, written and flushed. A
     * failed write deletes what it started, so it never leaves a part-picture; a crash mid-write can, and costs only a
     * picture that won't decode (shown as none; the next fetch writes it again).
     */
    fun writeDirect(f: File, bytes: ByteArray) {
        f.parentFile?.mkdirs()
        var tries = 0
        while (true) {
            try { Files.deleteIfExists(f.toPath()); break } catch (e: AccessDeniedException) { // a scanner holding it a moment
                if (++tries >= 20) throw IOException("Couldn't replace ${f.name}.", e)
                Thread.sleep(25)
            }
        }
        try {
            FileOutputStream(f).use { it.write(bytes); it.fd.sync() }
        } catch (e: IOException) {
            f.delete()
            throw e
        }
    }
}
