package com.streambuddy.app.net

import com.streambuddy.app.data.SettingsStore
import okhttp3.Interceptor
import java.io.IOException

/** Thrown when a network operation is refused because no VPN route is active. */
class VpnBlockedException(message: String = VpnRequiredMessage.network) : IOException(message)

/**
 * Single source of truth for "may this process use the network right now?".
 *
 * When "Require VPN" is on, every outbound request must pass through here.
 * Playback and torrents already check the VPN directly; this closes the other
 * holes (metadata, posters, browser pages, page scanning, browser downloads,
 * offline downloads) so the app never silently exposes the device IP.
 *
 * The policy fails closed: if it is installed and the VPN is not the app's
 * active route, requests are refused.
 */
class NetworkPolicy(
    private val settingsStore: SettingsStore,
    private val vpnMonitor: VpnMonitor,
) {
    fun isBlocked(): Boolean =
        settingsStore.current().requireVpnForNetwork && !vpnMonitor.isActive()

    fun check() {
        if (isBlocked()) throw VpnBlockedException()
    }

    val interceptor: Interceptor = Interceptor { chain ->
        if (isBlocked()) throw VpnBlockedException()
        chain.proceed(chain.request())
    }
}

/**
 * Process-wide gate used by code that cannot receive an injected policy
 * (OkHttp clients created in singletons). [install] is called once by
 * [com.streambuddy.app.AppContainer].
 */
object NetworkGuard {

    @Volatile
    private var policy: NetworkPolicy? = null

    fun install(policy: NetworkPolicy) {
        this.policy = policy
    }

    fun clear() {
        policy = null
    }

    fun isBlocked(): Boolean = policy?.isBlocked() ?: false

    fun check() {
        policy?.check()
    }

    /** Interceptor for every OkHttp client in the app. */
    fun interceptor(): Interceptor = Interceptor { chain ->
        val current = policy
        if (current != null && current.isBlocked()) {
            throw VpnBlockedException()
        }
        chain.proceed(chain.request())
    }
}
