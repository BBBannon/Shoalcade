package android.net.nsd

import java.net.InetAddress

/** DESKTOP STAND-IN, not Android: only the names Link.kt's phone lobby uses (see NsdManager.kt). Never used here. */
class NsdServiceInfo {
    var serviceName: String = ""
    var serviceType: String = ""
    private var p = 0
    val port: Int get() = p
    fun setPort(port: Int) { p = port }
    val host: InetAddress? get() = null
    val attributes: Map<String, ByteArray> get() = emptyMap()
    fun setAttribute(key: String, value: String): Unit = throw UnsupportedOperationException("NsdServiceInfo: phone only")
}
