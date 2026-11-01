package android.system

import java.io.FileDescriptor

/**
 * DESKTOP STAND-IN, not Android: the socketpair calls in Link.kt's LinkConnection (the phone's internet and Celio link
 * ends). The desktop joins the player to the relay or Celio over a local TCP stream instead (desktop/PcLink.kt).
 */
object Os {
    fun socketpair(domain: Int, type: Int, protocol: Int, a: FileDescriptor, b: FileDescriptor): Unit = phoneOnly()
    fun shutdown(fd: FileDescriptor, how: Int): Unit = phoneOnly()
    fun close(fd: FileDescriptor): Unit = phoneOnly()
    private fun phoneOnly(): Nothing = throw UnsupportedOperationException("android.system.Os: phone only")
}

object OsConstants {
    const val AF_UNIX = 1
    const val SOCK_STREAM = 1
    const val SHUT_RDWR = 2
}
