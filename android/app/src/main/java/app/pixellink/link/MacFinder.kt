package app.pixellink.link

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.net.Inet4Address
import kotlin.coroutines.resume

/** Finds the paired Mac on the local network by its Bonjour service and fingerprint prefix. */
object MacFinder {
    private const val SERVICE_TYPE = "_pixellink._tcp"

    suspend fun find(context: Context, fingerprint: String, timeoutMs: Long = 6_000): String? {
        val nsd = context.getSystemService(NsdManager::class.java)
        val found = Channel<NsdServiceInfo>(Channel.UNLIMITED)
        val listener = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(info: NsdServiceInfo) { found.trySend(info) }
            override fun onServiceLost(info: NsdServiceInfo) {}
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) { found.close() }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
        }
        nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        try {
            return withTimeoutOrNull(timeoutMs) {
                for (info in found) {
                    val resolved = resolve(nsd, info) ?: continue
                    val prefix = resolved.attributes["fp"]?.toString(Charsets.UTF_8) ?: continue
                    if (prefix.isNotEmpty() && fingerprint.startsWith(prefix)) {
                        return@withTimeoutOrNull resolved.hostAddresses.firstOrNull { it is Inet4Address }?.hostAddress
                    }
                }
                null
            }
        } finally {
            runCatching { nsd.stopServiceDiscovery(listener) }
        }
    }

    @Suppress("DEPRECATION")
    private suspend fun resolve(nsd: NsdManager, info: NsdServiceInfo): NsdServiceInfo? =
        suspendCancellableCoroutine { cont ->
            nsd.resolveService(info, object : NsdManager.ResolveListener {
                override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                    if (cont.isActive) cont.resume(null)
                }

                override fun onServiceResolved(info: NsdServiceInfo) {
                    if (cont.isActive) cont.resume(info)
                }
            })
        }
}
