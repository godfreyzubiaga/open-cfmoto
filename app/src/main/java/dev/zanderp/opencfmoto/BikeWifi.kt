// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto

import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/**
 * Joins the bike's hotspot using WifiNetworkSpecifier (no system Wi-Fi config required).
 *
 * The system shows a dialog asking the user to accept the network. After acceptance, the
 * resulting Network object is process-bound so our TCP sockets and mDNS lookups go through
 * the bike's interface (which has no internet — that's fine). Once the SSID has been approved,
 * Android re-satisfies the request without prompting again, so re-joins are silent.
 *
 * ## Auto-rejoin on loss (bike power-cycle)
 * A [WifiNetworkSpecifier] request is *terminal* on loss: when the bike is switched off its hotspot
 * disappears and the framework won't re-satisfy the old request on its own. So if the network drops
 * while a session is still active, we re-issue the request (with backoff) until the bike's AP comes
 * back. The FIRST acquisition drives the normal start-up; every subsequent one is a re-acquire that
 * restarts the bike link on the fresh network via [BikeLink.onWifiReacquired] (fresh IP + rebound
 * server sockets + fresh probe). Without this, stopping and restarting the bike left the app probing
 * a dead interface and needed several manual Stop→Connect cycles to recover.
 */
object BikeWifi {
    private var cm: ConnectivityManager? = null
    private var callback: ConnectivityManager.NetworkCallback? = null
    private var request: NetworkRequest? = null
    private var appContext: Context? = null
    private val handler = Handler(Looper.getMainLooper())

    var currentNetwork: Network? = null
        private set

    @Volatile private var active = false
    @Volatile private var firstDelivered = false
    private var rejoinAttempts = 0
    private var ssid: String = ""
    private var onAvailableCb: ((Network) -> Unit)? = null
    private var onLostCb: (() -> Unit)? = null
    private var logCb: ((String) -> Unit)? = null

    // Watch for the bike's AP until the rider taps Stop (leave()): a stopped bike may be off for a
    // while, and re-issuing the (approved, silent) request is cheap. Backoff grows then caps so we
    // don't hammer the framework while parked.
    //
    // The FIRST re-request after a drop is near-instant: at home the phone races to re-join a saved
    // network the moment the bike's AP blips, and if we don't re-grab the bike AP fast the dash's
    // EasyConn times out ("device is not on the network") and needs a manual restart. Grabbing it back
    // immediately keeps us on the bike's network long enough for the dash to reconnect on its own.
    private const val REJOIN_FAST_MS = 300L
    private const val REJOIN_BASE_MS = 2500L
    private const val REJOIN_MAX_MS = 15000L
    /**
     * Must be passed to [ConnectivityManager.requestNetwork]. Without a timeout the request can
     * wait forever after the bike AP dies — none of onAvailable/onLost/onUnavailable fire — so
     * ignition-cycle reconnect stalls until the rider toggles something. open-cflink hit 7+ min
     * hangs without this.
     */
    private const val JOIN_TIMEOUT_MS = 12_000

    fun join(
        context: Context,
        ssid: String,
        psk: String,
        onAvailable: (Network) -> Unit,
        onLost: () -> Unit,
        log: (String) -> Unit,
    ) {
        val cm = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        // Tear down any previous session's callback first.
        handler.removeCallbacksAndMessages(null)
        callback?.let { try { cm.unregisterNetworkCallback(it) } catch (_: Exception) {} }

        this.cm = cm
        this.appContext = context.applicationContext
        this.ssid = ssid
        this.onAvailableCb = onAvailable
        this.onLostCb = onLost
        this.logCb = log
        this.active = true
        this.firstDelivered = false
        this.rejoinAttempts = 0

        val specifier = WifiNetworkSpecifier.Builder()
            .setSsid(ssid)
            .setWpa2Passphrase(psk)
            .build()
        request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(specifier)
            .build()

        attemptJoin()
    }

    /**
     * Acquire the bike network, preferring an existing association over a fresh request.
     *
     * A [WifiNetworkSpecifier] request is NOT satisfied by the framework when the phone is already
     * associated with that SSID (e.g. it stayed connected after a manual Stop, or auto-reconnected as
     * a saved network): none of onAvailable/onUnavailable fire and the app hangs on "Connecting to
     * bike Wi-Fi". So if we're already on the target SSID, bind that live [Network] directly and skip
     * the request entirely. Only when we're NOT already on it do we issue the specifier request (which
     * shows the system accept dialog on first use). Called for both the initial join and every rejoin.
     */
    private fun attemptJoin() {
        val cm = cm ?: return
        val ctx = appContext
        val existing = if (ctx != null) try { findAssociatedNetwork(cm, ctx, ssid) } catch (_: Exception) { null } else null
        if (existing != null) {
            currentNetwork = existing
            try { cm.bindProcessToNetwork(existing) } catch (_: Exception) {}
            rejoinAttempts = 0
            logLinkOnce(existing)
            registerLossWatcher()
            if (!firstDelivered) {
                firstDelivered = true
                logCb?.invoke("Wi-Fi already connected: $ssid (network=$existing, bound) — skipping re-request")
                onAvailableCb?.invoke(existing)
            } else {
                logCb?.invoke("Wi-Fi re-acquired (already connected): $ssid — restarting bike link")
                BikeLink.onWifiReacquired(existing)
            }
            return
        }
        logCb?.invoke("requesting Wi-Fi join: $ssid …")
        registerCallback()
    }

    /**
     * The live wifi [Network] the phone is currently associated with, if its SSID matches [ssid].
     * Returns null when we're on a different network (or none) so the caller falls back to a proper
     * join request. Confirms the association via [WifiManager] first (works on API 29+, where the
     * per-network [NetworkCapabilities.getTransportInfo] SSID can be redacted).
     */
    private fun findAssociatedNetwork(cm: ConnectivityManager, ctx: Context, ssid: String): Network? {
        val target = ssid.trim('"')
        if (target.isEmpty()) return null
        val wm = ctx.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return null
        @Suppress("DEPRECATION")
        val assoc = wm.connectionInfo?.ssid?.trim('"')
        if (assoc == null || assoc == "<unknown ssid>" || !assoc.equals(target, ignoreCase = true)) return null
        // We're associated with the target SSID — find its Network object.
        val wifiNets = cm.allNetworks.filter {
            cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        }
        // Prefer a capabilities-confirmed SSID match; else the sole wifi network (SSID redacted on
        // older APIs) since WifiManager already confirmed we're on the target.
        wifiNets.firstOrNull { n ->
            val info = cm.getNetworkCapabilities(n)?.transportInfo as? android.net.wifi.WifiInfo
            info?.ssid?.trim('"')?.equals(target, ignoreCase = true) == true
        }?.let { return it }
        return wifiNets.singleOrNull()
    }

    /**
     * Passive watcher for the short-circuit path: when we bind an already-connected network (no
     * specifier request was issued), there's no request callback to notice a later drop. Observe wifi
     * loss so an ignition cycle still triggers [scheduleRejoin]. [clearCapabilities] matches both
     * normal and local-only (no-internet) wifi networks.
     */
    private fun registerLossWatcher() {
        val cm = cm ?: return
        callback?.let { try { cm.unregisterNetworkCallback(it) } catch (_: Exception) {} }
        val req = NetworkRequest.Builder()
            .clearCapabilities()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onLost(network: Network) {
                if (network != currentNetwork) return
                logCb?.invoke("Wi-Fi lost: $network")
                currentNetwork = null
                linkLogged = false
                onLostCb?.invoke()
                if (active) scheduleRejoin()
            }
        }
        callback = cb
        try { cm.registerNetworkCallback(req, cb) } catch (_: Exception) {}
    }

    private fun registerCallback() {
        val cm = cm ?: return
        val req = request ?: return
        callback?.let { try { cm.unregisterNetworkCallback(it) } catch (_: Exception) {} }

        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                currentNetwork = network
                cm.bindProcessToNetwork(network)
                rejoinAttempts = 0
                logLinkOnce(network)
                if (!firstDelivered) {
                    firstDelivered = true
                    logCb?.invoke("Wi-Fi joined: $ssid (network=$network, bound)")
                    onAvailableCb?.invoke(network)
                } else {
                    logCb?.invoke("Wi-Fi re-acquired: $ssid — restarting bike link on fresh network")
                    BikeLink.onWifiReacquired(network)
                }
            }

            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                // Band/RSSI logged once per join from onAvailable path via logLinkOnce.
            }

            override fun onLost(network: Network) {
                logCb?.invoke("Wi-Fi lost: $network")
                currentNetwork = null
                linkLogged = false
                onLostCb?.invoke()
                if (active) scheduleRejoin()
            }

            override fun onUnavailable() {
                logCb?.invoke(
                    "Wi-Fi join unavailable after ${JOIN_TIMEOUT_MS / 1000}s " +
                        "(bike off, out of range, or declined) — will retry",
                )
                if (active) scheduleRejoin()
            }
        }
        callback = cb
        // Timeout is mandatory — see JOIN_TIMEOUT_MS.
        cm.requestNetwork(req, cb, JOIN_TIMEOUT_MS)
    }

    @Volatile private var linkLogged = false

    private fun logLinkOnce(network: Network) {
        if (linkLogged) return
        val cm = this.cm ?: return
        try {
            val caps = cm.getNetworkCapabilities(network) ?: return
            val info = caps.transportInfo as? android.net.wifi.WifiInfo ?: return
            linkLogged = true
            val mhz = info.frequency
            val band = if (mhz in 2400..2500) "2.4GHz — SHARED WITH BLUETOOTH" else "${mhz / 1000}GHz"
            logCb?.invoke(
                "[wifi] link: ${mhz}MHz ($band), rssi=${info.rssi}dBm, " +
                    "tx=${info.txLinkSpeedMbps}Mbps, rx=${info.rxLinkSpeedMbps}Mbps",
            )
        } catch (_: Exception) {
        }
    }

    private fun scheduleRejoin() {
        if (!active) return
        rejoinAttempts++
        // First attempt is near-instant to beat the phone settling on a saved (home) network and the
        // dash timing out; later attempts back off and cap so we don't hammer the framework.
        val delay = if (rejoinAttempts <= 1) REJOIN_FAST_MS
        else minOf(REJOIN_BASE_MS * (rejoinAttempts - 1), REJOIN_MAX_MS)
        handler.postDelayed({
            if (!active) return@postDelayed
            logCb?.invoke("re-requesting bike Wi-Fi (attempt $rejoinAttempts) …")
            // Don't stomp the WAITING_FOR_BIKE state the service sets once AA is parked.
            if (ConnectionState.phase != Phase.WAITING_FOR_BIKE) {
                ConnectionState.set(Phase.RECONNECTING, "waiting for bike Wi-Fi")
            }
            attemptJoin()
        }, delay)
    }

    /**
     * Best-effort "is the bike's hotspot nearby?" check for auto-connect. Deliberately biased toward
     * `null` ("unknown → try anyway"): a false "not in range" is worse than a wasted connect attempt.
     *  - `true`  : the SSID is in a fresh Wi-Fi scan (or we're already on it) → connect.
     *  - `false` : we have FRESH scan results and the SSID is not among them → the bike really is away.
     *  - `null`  : we can't be sure (Wi-Fi off, no location permission, empty/stale scan cache) →
     *              the caller should attempt anyway.
     *
     * Reads cached scan results and only trusts "absent" when at least one result is recent (Android
     * throttles active scans, so a stale cache must not be read as "bike gone"). Also fires a
     * best-effort [WifiManager.startScan] to refresh the cache for the next check.
     */
    fun isSsidInRange(context: Context, ssid: String): Boolean? {
        if (ssid.isBlank()) return null
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            ?: return null
        if (!wm.isWifiEnabled) return null
        val fine = context.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (!fine) return null

        // Already associated with the bike's AP counts as in range.
        val connected = wm.connectionInfo?.ssid?.trim('"')
        if (connected != null && connected.equals(ssid, ignoreCase = true)) return true

        val results = try { wm.scanResults } catch (_: SecurityException) { null } ?: return null
        // Refresh for next time (throttled/deprecated — failures are fine).
        try { wm.startScan() } catch (_: Exception) {}
        if (results.isEmpty()) return null

        // ScanResult.timestamp is microseconds since boot when the AP was last seen.
        val nowUs = SystemClock.elapsedRealtime() * 1000L
        val fresh = results.filter { it.timestamp > 0 && nowUs - it.timestamp < FRESH_SCAN_US }
        if (fresh.any { it.SSID?.equals(ssid, ignoreCase = true) == true }) return true
        if (results.any { it.SSID?.equals(ssid, ignoreCase = true) == true }) return true
        // Not found: only a confident "no" if we actually have fresh evidence; else unknown.
        return if (fresh.isNotEmpty()) false else null
    }

    private const val FRESH_SCAN_US = 30_000_000L  // 30s

    fun leave(context: Context, log: (String) -> Unit) {
        active = false
        handler.removeCallbacksAndMessages(null)
        val cm = this.cm ?: (context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager)
        callback?.let {
            try { cm.unregisterNetworkCallback(it) } catch (_: Exception) {}
        }
        callback = null
        firstDelivered = false
        linkLogged = false
        cm.bindProcessToNetwork(null)
        currentNetwork = null
        log("Wi-Fi released")
    }

    /**
     * Re-assert process→bike-network binding. VPNs (PCAPdroid, commercial clients, Always-on VPN)
     * often steal the default route after we join; without this, outbound probes can leave via the
     * tunnel even though [currentNetwork] is still the bike AP.
     */
    fun rebindProcessToBike(context: Context): Boolean {
        val n = currentNetwork ?: return false
        val cm = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        return try {
            cm.bindProcessToNetwork(n)
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * True if any network has [NetworkCapabilities.TRANSPORT_VPN]. Bike projection needs a direct
     * L3 path on the bike Wi-Fi; VPN kill-switches ("Block connections without VPN") drop that path.
     */
    fun isVpnActive(context: Context): Boolean {
        val cm = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        return try {
            cm.allNetworks.any { n ->
                cm.getNetworkCapabilities(n)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
            }
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Always-on VPN with "Block connections without VPN" returns EPERM from
     * [Network.bindSocket] / [Network.getSocketFactory] — the app cannot pin traffic to bike Wi-Fi.
     */
    fun isVpnBindBlocked(error: Throwable?): Boolean {
        var t: Throwable? = error
        while (t != null) {
            val m = t.message ?: ""
            if (m.contains("EPERM", ignoreCase = true) ||
                m.contains("Operation not permitted", ignoreCase = true)
            ) {
                return true
            }
            t = t.cause
        }
        return false
    }

    /**
     * Quick check: can we create a socket on the bike [Network]? Returns null if OK, else the error.
     * Call after join when a VPN may be in lockdown mode.
     */
    fun testBikeSocketBind(context: Context): Exception? {
        val n = currentNetwork ?: return null
        rebindProcessToBike(context)
        return try {
            n.socketFactory.createSocket().close()
            null
        } catch (e1: Exception) {
            if (isVpnBindBlocked(e1)) return e1
            try {
                val s = java.net.Socket()
                n.bindSocket(s)
                s.close()
                null
            } catch (e2: Exception) {
                e2
            }
        }
    }
}
