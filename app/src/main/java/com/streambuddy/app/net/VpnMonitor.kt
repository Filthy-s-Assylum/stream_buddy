package com.streambuddy.app.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether Stream Buddy's own traffic is currently routed through a VPN.
 *
 * This deliberately checks the *active network of this app*, not just "does a
 * VPN interface exist". If the user runs a per-app VPN that excludes Stream
 * Buddy (split tunnelling), a VPN network may exist while this app's sockets
 * still leave through Wi-Fi or mobile data. Reporting that as protected would
 * be a lie, so the check uses [ConnectivityManager.getActiveNetwork] plus its
 * capabilities.
 *
 * Stream Buddy does not ship a VPN client and cannot create a tunnel. The user
 * supplies the VPN (system VPN, always-on VPN, or a trusted VPN app); this
 * class only verifies it is in the path and lets the rest of the app fail
 * closed when it is not.
 */
data class VpnStatus(
    val active: Boolean,
    val metered: Boolean = false,
    val checkedAt: Long = 0L,
    val reason: String = "",
) {
    val summary: String
        get() = if (active) {
            "VPN route detected" + if (metered) " (metered)" else ""
        } else {
            reason.ifBlank { "No VPN route detected" }
        }
}

/** Shared, user-facing wording for a missing VPN route. */
object VpnRequiredMessage {
    const val torrent =
        "A VPN connection is required. Connect your trusted VPN (and make sure Stream Buddy is not excluded " +
            "from it), then try again."

    const val playback =
        "A VPN connection is required for network playback. Connect your trusted VPN, or switch off “Require VPN” " +
            "in Settings if you accept that your IP will be visible to the source."

    const val network =
        "A VPN connection is required. Connect your trusted VPN (and make sure Stream Buddy is not excluded " +
            "from it), then try again. No network request was sent."
}

class VpnMonitor(context: Context) {

    private val connectivity: ConnectivityManager? =
        context.applicationContext.getSystemService(ConnectivityManager::class.java)

    private val _status = MutableStateFlow(readStatus())
    val status: StateFlow<VpnStatus> = _status.asStateFlow()

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = refresh()

        override fun onLost(network: Network) = refresh()

        override fun onCapabilitiesChanged(
            network: Network,
            networkCapabilities: NetworkCapabilities,
        ) = refresh()
    }

    private var registered = false

    init {
        register()
    }

    fun refresh() {
        _status.value = readStatus()
    }

    fun isActive(): Boolean {
        val current = readStatus()
        _status.value = current
        return current.active
    }

    /** Re-registers callbacks, e.g. after the user returns from VPN settings. */
    @Synchronized
    fun register() {
        val manager = connectivity ?: return
        if (registered) return
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_VPN)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        runCatching {
            manager.registerNetworkCallback(request, callback)
            registered = true
        }
    }

    @Synchronized
    fun release() {
        val manager = connectivity ?: return
        if (!registered) return
        runCatching { manager.unregisterNetworkCallback(callback) }
        registered = false
    }

    private fun readStatus(): VpnStatus {
        val manager = connectivity ?: return VpnStatus(
            active = false,
            checkedAt = System.currentTimeMillis(),
            reason = "This device does not expose a connectivity service.",
        )

        val activeNetwork = runCatching { manager.activeNetwork }.getOrNull()
        val caps = activeNetwork?.let { network ->
            runCatching { manager.getNetworkCapabilities(network) }.getOrNull()
        }

        val activeViaVpn = caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true &&
            !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
        if (activeViaVpn) {
            return VpnStatus(
                active = true,
                metered = !caps!!.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
                checkedAt = System.currentTimeMillis(),
                reason = "VPN route detected",
            )
        }

        // A VPN interface can exist while this app is excluded from it. Treat
        // that as unprotected and say why, so the user can fix split tunnelling
        // or an "exclude apps" rule.
        val vpnInterfaceExists = runCatching {
            manager.allNetworks.any { network ->
                manager.getNetworkCapabilities(network)
                    ?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
            }
        }.getOrDefault(false)

        val reason = when {
            vpnInterfaceExists && activeNetwork == null ->
                "A VPN is present but Stream Buddy has no active network. Check the VPN."
            vpnInterfaceExists ->
                "A VPN is running, but Stream Buddy is not using it. Allow Stream Buddy in the VPN's per-app settings."
            activeNetwork == null ->
                "No network connection is active."
            else ->
                "No VPN route detected. Connect your trusted VPN before streaming or downloading torrents."
        }
        return VpnStatus(
            active = false,
            checkedAt = System.currentTimeMillis(),
            reason = reason,
        )
    }
}
