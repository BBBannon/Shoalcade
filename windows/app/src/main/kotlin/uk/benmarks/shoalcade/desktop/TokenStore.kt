package uk.benmarks.shoalcade.desktop

import com.sun.jna.platform.win32.Crypt32Util
import com.sun.jna.platform.win32.WinCrypt
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions

/**
 * Keeps the login (it holds the Jellyfin token) in one file, sealed for this computer (plan §7):
 *  - **Windows: DPAPI** (`CryptProtectData`, current Windows user, no prompts). Only this Windows user on this PC can
 *    open it; a copied file is useless elsewhere. Same idea as the phone's Android Keystore.
 *  - **Linux: a clearly marked development store**, not encrypted, readable by this user only (0600). For tests and
 *    development on the build machine; Shoalcade for Windows never uses it.
 *
 * Never logs or prints what it stores, and its errors never include it. A file sealed one way is never opened the
 * other way (a dev file found on Windows counts as "no login").
 */
class TokenStore(private val file: File, private val sealer: Sealer) {

    /** How the bytes are sealed. [kind] is written into the file so the two can never be mixed up. */
    interface Sealer {
        val kind: Byte
        fun seal(plain: ByteArray): ByteArray
        fun open(sealed: ByteArray): ByteArray
    }

    /** Windows DPAPI for the current user. The extra "entropy" ties the blob to Shoalcade's use of it. */
    object Dpapi : Sealer {
        override val kind: Byte = 1
        private val ENTROPY = "Shoalcade login v1".toByteArray(Charsets.UTF_8)
        override fun seal(plain: ByteArray): ByteArray =
            Crypt32Util.cryptProtectData(plain, ENTROPY, WinCrypt.CRYPTPROTECT_UI_FORBIDDEN, "Shoalcade login", null)
        override fun open(sealed: ByteArray): ByteArray =
            Crypt32Util.cryptUnprotectData(sealed, ENTROPY, WinCrypt.CRYPTPROTECT_UI_FORBIDDEN, null)
    }

    /** DEVELOPMENT ONLY (Linux): stored as it is, protected only by the file being this user's alone (0600). */
    object DevPlain : Sealer {
        override val kind: Byte = 2
        override fun seal(plain: ByteArray) = plain.copyOf()
        override fun open(sealed: ByteArray) = sealed.copyOf()
    }

    /** The stored bytes, or null if there are none or they can't be opened here (then the person just logs in again). */
    fun load(): ByteArray? {
        val all = runCatching { file.takeIf { it.isFile }?.readBytes() }.getOrNull() ?: return null
        if (all.size < HEADER.size + 1 || !all.copyOf(HEADER.size).contentEquals(HEADER)) return null
        if (all[HEADER.size] != sealer.kind) return null
        return runCatching { sealer.open(all.copyOfRange(HEADER.size + 1, all.size)) }.getOrNull()
    }

    fun save(plain: ByteArray) {
        val sealed = try { sealer.seal(plain) } catch (e: Exception) {
            throw IOException("Couldn't protect the login on this computer.") // never the bytes or a cause that holds them
        }
        AtomicFile.write(file, HEADER + byteArrayOf(sealer.kind) + sealed, ownerOnly = !Paths.isWindows())
    }

    fun clear() { file.delete() }

    companion object {
        private val HEADER = "SCT1".toByteArray(Charsets.US_ASCII)

        /** DPAPI on Windows; the marked development store anywhere else. */
        fun sealerForThisComputer(): Sealer = if (Paths.isWindows()) Dpapi else DevPlain

        /** True if [f] can only be read and written by its owner (POSIX). Always true on Windows (ACLs, DPAPI). */
        fun ownerOnly(f: File): Boolean = Paths.isWindows() ||
            Files.getPosixFilePermissions(f.toPath()) == PosixFilePermissions.fromString("rw-------")
    }
}
