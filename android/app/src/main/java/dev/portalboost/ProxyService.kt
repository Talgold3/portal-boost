package dev.portalboost

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.annotation.SuppressLint
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import java.net.InetAddress

/**
 * Foreground service: keeps the phone awake on Wi-Fi, creates the Portal's Wi-Fi Direct network
 * (LocalOnlyHotspot as a fallback; no route to the home LAN, so the Portal's
 * remembered PS5 address is unreachable and it falls back to discovery), and
 * runs the relay towards the PS5 over the home Wi-Fi.
 */
class ProxyService : Service() {
    companion object {
        const val ACTION_START = "dev.portalboost.START"
        const val ACTION_STOP = "dev.portalboost.STOP"
        const val EXTRA_PS5 = "ps5"
        const val EXTRA_PS5_NAME = "ps5Name"
        const val EXTRA_PROFILE = "profile"   // "off", "35", "45", "55", "65", "100", "200"
        const val HTTP_PROXY_PORT = 8080
        const val P2P_SSID = "DIRECT-PB-PortalBoost"
        private const val CHANNEL = "proxy"
        private const val NOTIF_ID = 1
    }

    private val log = LogBus::log
    private val main = Handler(Looper.getMainLooper())
    private var proxy: Proxy? = null
    private var httpProxy: HttpProxy? = null
    private var reservation: WifiManager.LocalOnlyHotspotReservation? = null
    private var p2p: WifiP2pManager? = null
    private var p2pChannel: WifiP2pManager.Channel? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var lowLatencyLock: WifiManager.WifiLock? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> start(
                intent.getStringExtra(EXTRA_PS5) ?: "",
                intent.getStringExtra(EXTRA_PS5_NAME),
                intent.getStringExtra(EXTRA_PROFILE) ?: "65",
            )
            ACTION_STOP -> { shutdown(); stopSelf() }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        shutdown()
        super.onDestroy()
    }

    private fun start(ps5Text: String, ps5Name: String?, profile: String) {
        if (proxy != null) { log("already running"); return }
        goForeground("Starting…")

        val ps5 = try {
            InetAddress.getByName(ps5Text.trim())
        } catch (e: Exception) {
            log("invalid PS5 IP '$ps5Text'"); stopSelf(); return
        }
        val upstream = findUpstream(ps5)
        if (upstream == null) {
            log("WARNING: no Wi-Fi network on the PS5's subnet found; is the phone on the home Wi-Fi?")
        }
        acquireLocks()

        val patch = Patch.PROFILES[profile]
        log("starting: PS5 ${ps5.hostAddress}, profile ${if (patch == null) "relay only (no boost)" else "$profile Mbps"}")
        val router = upstream?.let { n ->
            getSystemService(ConnectivityManager::class.java).getLinkProperties(n)?.routes
                ?.firstOrNull { it.isDefaultRoute && it.gateway is java.net.Inet4Address }?.gateway
        }
        val p = Proxy(ps5, patch, upstream, router, log)
        var ticks = 0
        p.onStats = { mbps, portalMs, ps5Ms, lossPct, ping ->
            LogBus.update { it.copy(mbps = mbps, portalMs = portalMs, ps5Ms = ps5Ms, lossPct = lossPct, ping = ping) }
            // Refresh the notification every 5 s; more often gets rate-limited.
            if (++ticks % 5 == 0 && LogBus.status.portalSeen) notifyStatus()
        }
        p.onPs5Found = { LogBus.update { it.copy(ps5Found = true) } }
        p.onPortal = {
            if (!LogBus.status.portalSeen) { LogBus.update { it.copy(portalSeen = true) }; main.post { notifyStatus() } }
        }
        p.onBoost = { applied ->
            LogBus.update {
                it.copy(boost = when (applied) {
                    true -> LogBus.Boost.APPLIED
                    false -> LogBus.Boost.NOT_APPLIED
                    // A resumed session keeps whatever bitrate it started with.
                    null -> if (it.boost == LogBus.Boost.APPLIED) it.boost else LogBus.Boost.NOT_APPLIED
                })
            }
        }
        // Set before the relay starts, so its first events (PS5 found) aren't overwritten.
        LogBus.update {
            LogBus.Status(running = true, profile = profile, ps5Ip = ps5.hostAddress, ps5Name = ps5Name, homeFreq = homeWifiFrequency())
        }
        try {
            p.start()
        } catch (e: Exception) {
            log("bind failed: ${e.message} (is Remote Play or another proxy running?)")
            p.stop(); releaseLocks(); LogBus.update { LogBus.Status() }; stopSelf(); return
        }
        proxy = p

        val homeLinks = upstream?.let {
            getSystemService(ConnectivityManager::class.java).getLinkProperties(it)?.linkAddresses
        }.orEmpty()
        val hp = HttpProxy(
            HTTP_PROXY_PORT, upstream,
            isHomeClient = { a -> homeLinks.any { sameSubnet(it.address, a, it.prefixLength) } },
            ps5 = ps5, log = log,
        )
        try {
            hp.start(); httpProxy = hp
        } catch (e: Exception) {
            log("internet proxy on port $HTTP_PROXY_PORT failed: ${e.message}")
        }
        startDirect(upstream)
    }

    private fun startHotspot(upstream: Network?) {
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        if (Build.VERSION.SDK_INT >= 30 && !wm.isStaApConcurrencySupported) {
            log("WARNING: this phone reports no Wi-Fi + hotspot concurrency; home Wi-Fi may drop when the hotspot starts")
        }
        try {
            wm.startLocalOnlyHotspot(object : WifiManager.LocalOnlyHotspotCallback() {
                override fun onStarted(r: WifiManager.LocalOnlyHotspotReservation) {
                    reservation = r
                    val (ssid, pass) = credentials(r)
                    LogBus.update { it.copy(hotspotSsid = ssid, hotspotPass = pass) }
                    log("hotspot up: SSID '$ssid' (password shown in the app)")
                    goForeground("Hotspot $ssid — waiting for Portal")
                    main.postDelayed({ announceHotspotIp(upstream) }, 1500)
                    main.postDelayed({ checkUpstream(upstream) }, 3000)
                }

                override fun onStopped() {
                    log("hotspot stopped by the system")
                    LogBus.update { it.copy(hotspotSsid = null, hotspotPass = null) }
                }

                override fun onFailed(reason: Int) {
                    val why = when (reason) {
                        ERROR_NO_CHANNEL -> "no channel available"
                        ERROR_INCOMPATIBLE_MODE -> "incompatible mode (turn off the normal hotspot/tethering)"
                        ERROR_TETHERING_DISALLOWED -> "hotspot disallowed on this device"
                        else -> "generic error"
                    }
                    log("hotspot FAILED: $why ($reason)")
                }
            }, main)
        } catch (e: SecurityException) {
            log("hotspot needs permission: ${e.message}")
        } catch (e: IllegalStateException) {
            log("hotspot not started: ${e.message}")
        }
    }

    /** Wi-Fi Direct group with a fixed name/password, so the Portal joins once and remembers it. */
    @SuppressLint("MissingPermission")
    private fun startDirect(upstream: Network?) {
        val m = getSystemService(WifiP2pManager::class.java)
        if (Build.VERSION.SDK_INT < 29 || m == null) {
            log("fixed Wi-Fi needs Android 10+ with Wi-Fi Direct; using the random-password hotspot")
            startHotspot(upstream); return
        }
        val ch = m.initialize(this, mainLooper) { log("Wi-Fi Direct channel lost") }
        p2p = m; p2pChannel = ch
        val pass = Settings.passphrase(this)
        // Latency: run the Portal's network on the same channel as the home Wi-Fi, so the
        // phone's single radio doesn't have to hop between two channels.
        val staFreq = homeWifiFrequency()
        log("home Wi-Fi on ${describeFreq(staFreq)}")
        if (Build.VERSION.SDK_INT >= 31) {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            log("radio: dual-band simultaneous ${if (wm.isDualBandSimultaneousSupported) "supported" else "NOT supported"}")
        }
        if (staFreq in 5260..5720) {
            log("WARNING: your router's 5 GHz is on DFS ${describeFreq(staFreq)}. Wi-Fi Direct can't use DFS channels, so the Portal's network " +
                "will land on another channel and latency will be bad. Set the router's 5 GHz channel to 36, 40, 44 or 48 (not Auto).")
        }
        // Separate channels for the two links were all worse on the S25; the group always follows the home Wi-Fi.
        val target = staFreq

        fun config(useTarget: Boolean) = WifiP2pConfig.Builder().setNetworkName(P2P_SSID).setPassphrase(pass)
            .apply { if (useTarget) setGroupOperatingFrequency(target) }
            .build()

        fun create(sameChannel: Boolean) {
            m.createGroup(ch, config(sameChannel), object : WifiP2pManager.ActionListener {
                override fun onSuccess() { main.postDelayed({ directInfo(upstream, 5, staFreq) }, 1000) }
                override fun onFailure(reason: Int) {
                    val why = when (reason) {
                        WifiP2pManager.P2P_UNSUPPORTED -> "unsupported"
                        WifiP2pManager.BUSY -> "busy"
                        else -> "error $reason"
                    }
                    if (sameChannel) {
                        log("fixed Wi-Fi on ${describeFreq(target)} failed ($why); retrying with automatic channel")
                        create(false)
                        return
                    }
                    log("fixed Wi-Fi (Wi-Fi Direct) failed: $why; falling back to the random-password hotspot")
                    stopDirect()
                    startHotspot(upstream)
                }
            })
        }
        val sameChannel = target in 2412..2484 || target in 5160..5885
        // Clear any leftover group from a previous run first.
        m.removeGroup(ch, object : WifiP2pManager.ActionListener {
            override fun onSuccess() = create(sameChannel)
            override fun onFailure(reason: Int) = create(sameChannel)
        })
    }

    @Suppress("DEPRECATION")
    private fun homeWifiFrequency(): Int =
        (applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager).connectionInfo?.frequency ?: -1

    private fun describeFreq(f: Int): String = when {
        f in 2412..2484 -> "$f MHz (2.4 GHz, channel ${(f - 2407) / 5})"
        f in 4900..5900 -> "$f MHz (5 GHz, channel ${(f - 5000) / 5})"
        f > 5900 -> "$f MHz (6 GHz)"
        else -> "unknown frequency"
    }

    @SuppressLint("MissingPermission")
    private fun directInfo(upstream: Network?, tries: Int, staFreq: Int) {
        val m = p2p ?: return
        val ch = p2pChannel ?: return
        m.requestGroupInfo(ch) { g ->
            if (g == null) {
                if (tries > 0) main.postDelayed({ directInfo(upstream, tries - 1, staFreq) }, 1000)
                else log("Wi-Fi Direct group did not come up")
                return@requestGroupInfo
            }
            LogBus.update { it.copy(hotspotSsid = g.networkName, hotspotPass = g.passphrase, groupFreq = g.frequency) }
            log("fixed Wi-Fi up: SSID '${g.networkName}' on ${describeFreq(g.frequency)}")
            val band = { f: Int -> if (f < 3000) 2 else if (f < 5900) 5 else 6 }
            if (g.frequency == staFreq) {
                log("latency: Portal network and home Wi-Fi share one channel (no hopping; both hops take turns on one radio)")
            } else if (band(g.frequency) != band(staFreq)) {
                log("latency: Portal network and home Wi-Fi on different bands (can run in parallel on a dual-band-simultaneous radio)")
            } else {
                log("latency WARNING: Portal network (${describeFreq(g.frequency)}) and home Wi-Fi (${describeFreq(staFreq)}) are on different channels; on the tested Galaxy S25 this made the phone hop between them (much higher latency). Set the router's 5 GHz to channel 36-48, 40 MHz, not Auto.")
            }
            goForeground("Wi-Fi ${g.networkName} — waiting for Portal")
            announceHotspotIp(upstream, iface = g.`interface`)
            main.postDelayed({ checkUpstream(upstream) }, 2000)
        }
    }

    private fun stopDirect() {
        val m = p2p; val ch = p2pChannel
        if (m != null && ch != null) {
            runCatching { m.removeGroup(ch, null) }
            if (Build.VERSION.SDK_INT >= 27) runCatching { ch.close() }
        }
        p2p = null; p2pChannel = null
    }

    @Suppress("DEPRECATION")
    private fun credentials(r: WifiManager.LocalOnlyHotspotReservation): Pair<String?, String?> =
        if (Build.VERSION.SDK_INT >= 30) {
            val c = r.softApConfiguration
            val ssid = if (Build.VERSION.SDK_INT >= 33) c.wifiSsid?.toString()?.trim('"') else c.ssid
            ssid to c.passphrase
        } else {
            r.wifiConfiguration?.SSID?.trim('"') to r.wifiConfiguration?.preSharedKey
        }

    /** The phone's own address on the hotspot = proxy server address for the Portal. */
    private fun announceHotspotIp(upstream: Network?, tries: Int = 5, iface: String? = null) {
        if (proxy == null) return
        val cm = getSystemService(ConnectivityManager::class.java)
        val homeAddrs = upstream?.let { cm.getLinkProperties(it)?.linkAddresses?.map { a -> a.address } }.orEmpty()
        val ip = runCatching {
            val candidates = iface?.let { listOfNotNull(java.net.NetworkInterface.getByName(it)) }
                ?: java.net.NetworkInterface.getNetworkInterfaces().toList()
                    .filter { it.isUp && !it.isLoopback && !it.name.startsWith("rmnet") && !it.name.startsWith("tun") }
            candidates.flatMap { it.inetAddresses.toList() }
                .firstOrNull { it is java.net.Inet4Address && it.isSiteLocalAddress && it !in homeAddrs }
                ?.hostAddress
        }.getOrNull()
        if (ip == null) {
            if (tries > 0) main.postDelayed({ announceHotspotIp(upstream, tries - 1, iface) }, 1000)
            else log("could not find the hotspot's IP address; check the Portal's gateway address instead")
            return
        }
        LogBus.update { it.copy(proxyAddr = "$ip:$HTTP_PROXY_PORT") }
        log("internet proxy for the Portal: $ip port $HTTP_PROXY_PORT")
        log("READY. On the Portal: join the hotspot, set Proxy Server = $ip / $HTTP_PROXY_PORT, then connect to the PS5.")
    }

    private fun checkUpstream(upstream: Network?) {
        if (proxy == null) return
        val cm = getSystemService(ConnectivityManager::class.java)
        val alive = upstream != null && cm.getNetworkCapabilities(upstream) != null
        if (!alive) log("WARNING: home Wi-Fi is gone after starting the hotspot; the PS5 is unreachable")
        else if (proxy?.discoveryOk == true) log("home Wi-Fi still up and PS5 answering - good")
        else log("home Wi-Fi up but PS5 not answering discovery yet (check the IP / is it on?)")
    }

    private fun findUpstream(ps5: InetAddress): Network? {
        val cm = getSystemService(ConnectivityManager::class.java)
        val wifi = cm.allNetworks.filter {
            cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        }
        return wifi.firstOrNull { n ->
            cm.getLinkProperties(n)?.linkAddresses?.any { sameSubnet(it.address, ps5, it.prefixLength) } == true
        } ?: wifi.firstOrNull()
    }

    private fun sameSubnet(a: InetAddress, b: InetAddress, prefix: Int): Boolean {
        val x = a.address; val y = b.address
        if (x.size != y.size) return false
        var bits = prefix
        for (i in x.indices) {
            if (bits <= 0) return true
            val mask = if (bits >= 8) 0xff else (0xff shl (8 - bits)) and 0xff
            if ((x[i].toInt() and mask) != (y[i].toInt() and mask)) return false
            bits -= 8
        }
        return true
    }

    @Suppress("DEPRECATION")
    private fun acquireLocks() {
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        multicastLock = wm.createMulticastLock("portalboost").apply { setReferenceCounted(false); acquire() }
        wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "portalboost").apply { setReferenceCounted(false); acquire() }
        // Disables Wi-Fi power save (radio sleeping between packets). Android only honours it while
        // the app is in the foreground with the screen on.
        if (Build.VERSION.SDK_INT >= 29) {
            lowLatencyLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "portalboost-ll")
                .apply { setReferenceCounted(false); acquire() }
        }
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "portalboost:proxy").apply { setReferenceCounted(false); acquire() }
    }

    private fun releaseLocks() {
        runCatching { multicastLock?.release() }; multicastLock = null
        runCatching { wifiLock?.release() }; wifiLock = null
        runCatching { lowLatencyLock?.release() }; lowLatencyLock = null
        runCatching { wakeLock?.release() }; wakeLock = null
    }

    private fun shutdown() {
        val wasRunning = proxy != null || reservation != null
        proxy?.stop(); proxy = null
        httpProxy?.stop(); httpProxy = null
        runCatching { reservation?.close() }; reservation = null
        stopDirect()
        releaseLocks()
        LogBus.update { LogBus.Status() }
        if (wasRunning) log("stopped")
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    /** Live notification text once the Portal is on: profile and stream rate. */
    private fun notifyStatus() {
        if (proxy == null) return
        val s = LogBus.status
        val profile = if (s.profile == "off") "Boost off" else "Profile ${s.profile}"
        goForeground("$profile · %.0f Mbps".format(s.mbps))
    }

    private fun goForeground(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Boost status", NotificationManager.IMPORTANCE_LOW))
        // Opening the app restores the low-latency Wi-Fi lock, which Android only honours in the foreground.
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, ProxyService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_boost)
            .setColor(0xFF0070D1.toInt())
            .setContentTitle("Portal Boost")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(null, "Open", open).build())
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }
}
