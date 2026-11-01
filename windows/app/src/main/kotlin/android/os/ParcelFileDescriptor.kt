package android.os

import java.io.FileDescriptor
import java.net.Socket

/**
 * DESKTOP STAND-IN, not Android: Link.kt's LinkConnection hands the phone's native shim a file descriptor. The Windows
 * player gets its link as a local TCP stream from the shell instead (desktop/PcLink.kt), so this is never called.
 */
class ParcelFileDescriptor private constructor() {
    fun detachFd(): Int = phoneOnly()

    companion object {
        fun fromSocket(socket: Socket): ParcelFileDescriptor = phoneOnly()
        fun dup(fd: FileDescriptor): ParcelFileDescriptor = phoneOnly()
        private fun phoneOnly(): Nothing = throw UnsupportedOperationException("ParcelFileDescriptor: phone only")
    }
}
