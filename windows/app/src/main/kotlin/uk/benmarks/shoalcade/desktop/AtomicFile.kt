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
}
