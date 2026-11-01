package android.net.nsd

/**
 * DESKTOP STAND-IN, not Android. Link.kt is compiled on the desktop for its rules and PIN handshake (LinkRules,
 * PinGuard, LinkHandshake). Its phone lobby (LinkLobby) also names Android's network service discovery; these are only
 * the names, so it compiles. The desktop finds and shows hosts with its own mDNS code (desktop/PcLink.kt), never this:
 * every call throws.
 */
class NsdManager private constructor() {
    interface RegistrationListener {
        fun onServiceRegistered(info: NsdServiceInfo)
        fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int)
        fun onServiceUnregistered(info: NsdServiceInfo)
        fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int)
    }

    interface DiscoveryListener {
        fun onDiscoveryStarted(serviceType: String)
        fun onDiscoveryStopped(serviceType: String)
        fun onStartDiscoveryFailed(serviceType: String, errorCode: Int)
        fun onStopDiscoveryFailed(serviceType: String, errorCode: Int)
        fun onServiceFound(info: NsdServiceInfo)
        fun onServiceLost(info: NsdServiceInfo)
    }

    interface ResolveListener {
        fun onResolveFailed(info: NsdServiceInfo, errorCode: Int)
        fun onServiceResolved(info: NsdServiceInfo)
    }

    fun registerService(info: NsdServiceInfo, protocol: Int, l: RegistrationListener): Unit = phoneOnly()
    fun unregisterService(l: RegistrationListener): Unit = phoneOnly()
    fun discoverServices(type: String, protocol: Int, l: DiscoveryListener): Unit = phoneOnly()
    fun stopServiceDiscovery(l: DiscoveryListener): Unit = phoneOnly()
    fun resolveService(info: NsdServiceInfo, l: ResolveListener): Unit = phoneOnly()

    companion object {
        const val PROTOCOL_DNS_SD = 1
        private fun phoneOnly(): Nothing = throw UnsupportedOperationException("NsdManager: phone only")
    }
}
