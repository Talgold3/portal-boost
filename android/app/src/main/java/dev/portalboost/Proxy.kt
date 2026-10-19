package dev.portalboost

import android.net.Network
import android.os.Process
import java.io.Closeable
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.PortUnreachableException
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * User-space PS5 Remote Play relay, a port of portal_proxy.py.
 *
 *   9302/udp discovery -> answered from the cached PS5 reply, WAKEUP forwarded
 *   9295/tcp session   -> raw TCP relay
 *   9296/udp stream    -> UDP relay, startup packet patched
 *   9297/udp senkusha  -> UDP relay
 *
 * Listening sockets are unbound (so they accept the Portal on the hotspot);
 * every socket that talks to the PS5 is bound to [upstream], the home Wi-Fi.
 */
class Proxy(
    private val ps5: InetAddress,
    /** Bitrate patch for new sessions; null = relay only. Can be changed while running. */
    @Volatile var patch: ByteArray?,
    private val upstream: Network?,
    /** Home router (gateway); pinged separately to split the home hop into Wi-Fi vs. PS5. */
    private val router: InetAddress?,
    private val log: (String) -> Unit,
) {
    @Volatile private var running = true
    private val closeables = ConcurrentHashMap.newKeySet<Closeable>()
    private val streamBytes = AtomicLong()
    @Volatile var discoveryOk = false
        private set

    /**
     * Every second: PS5->Portal Mbps, Portal and PS5 ping (ms, null without replies), video loss on the
     * home hop in percent over the last second (null without video) and a ping summary.
     */
    var onStats: (Double, Double?, Double?, Double?, String) -> Unit = { _, _, _, _, _ -> }
    /** The PS5 answered discovery for the first time. */
    var onPs5Found: () -> Unit = {}
    /** The Portal is on the network (it searched for the PS5 or opened a session). */
    var onPortal: () -> Unit = {}
    /** A new stream: true = boost applied, false = startup packet refused, null = no startup packet (resumed session). */
    var onBoost: (Boolean?) -> Unit = {}

    // Latency per hop: phone->PS5 over home Wi-Fi, phone->Portal over the Portal's network.
    private val ps5Ping = Pinger(ps5)
    private val routerPing = router?.let { Pinger(it) }
    @Volatile private var portalPing: Pinger? = null

    fun start() {
        ps5Ping.start()
        routerPing?.start()
        val disc = DatagramSocket(null).apply {
            reuseAddress = true; broadcast = true; soTimeout = 1000
            bind(InetSocketAddress(9302))
        }.track()
        val discUp = upstreamUdp().apply { soTimeout = 1000 }
        val tcp = ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(9295)) }.track()
        val u96 = listenUdp(9296)
        val u97 = listenUdp(9297)

        val cache = arrayOfNulls<ByteArray>(1)
        thread(name = "disc-up", isDaemon = true) { discoveryUpstream(discUp, cache) }
        thread(name = "disc", isDaemon = true) { discoveryLoop(disc, discUp, cache) }
        thread(name = "tcp9295", isDaemon = true) { tcpLoop(tcp, 9295) }
        thread(name = "udp9296", isDaemon = true) { udpLoop(u96, 9296, true, streamBytes) }
        thread(name = "udp9297", isDaemon = true) { udpLoop(u97, 9297, false, AtomicLong()) }
        thread(name = "stats", isDaemon = true) { statsLoop() }
    }

    fun stop() {
        running = false
        ps5Ping.stop(); routerPing?.stop(); portalPing?.stop(); portalPing = null
        closeables.forEach { runCatching { it.close() } }
        closeables.clear()
    }

    private fun <T : Closeable> T.track(): T = also { closeables.add(it) }

    private fun upstreamUdp(): DatagramSocket =
        DatagramSocket().also { upstream?.bindSocket(it) }.track()

    private fun listenUdp(port: Int) = DatagramSocket(null).apply {
        reuseAddress = true
        receiveBufferSize = UDP_BUF
        sendBufferSize = UDP_BUF
        trafficClass = TOS_VIDEO
        bind(InetSocketAddress(port))
    }.track()

    // ---------- 9302 discovery ----------
    private val srch = "SRCH * HTTP/1.1\ndevice-discovery-protocol-version:00030010\n".toByteArray()

    private fun discoveryUpstream(up: DatagramSocket, cache: Array<ByteArray?>) {
        val buf = ByteArray(4096)
        var last = 0L
        while (running) {
            if (System.currentTimeMillis() - last > 2000) {
                runCatching { up.send(DatagramPacket(srch, srch.size, ps5, 9302)) }
                last = System.currentTimeMillis()
            }
            val p = DatagramPacket(buf, buf.size)
            try {
                up.receive(p)
            } catch (e: SocketTimeoutException) {
                continue
            } catch (e: SocketException) {
                if (!running) return else continue
            }
            if (p.address != ps5) continue
            val data = p.data.copyOf(p.length)
            if (!String(data, 0, minOf(8, data.size)).startsWith("HTTP/1.1")) continue
            if (!data.contentEquals(cache[0])) {
                log("PS5 discovery status: ${String(data).substringBefore('\n').trim()}")
            }
            cache[0] = data
            if (!discoveryOk) { discoveryOk = true; onPs5Found() }
        }
    }

    private fun discoveryLoop(sock: DatagramSocket, up: DatagramSocket, cache: Array<ByteArray?>) {
        val buf = ByteArray(4096)
        while (running) {
            val p = DatagramPacket(buf, buf.size)
            try {
                sock.receive(p)
            } catch (e: SocketTimeoutException) {
                continue
            } catch (e: SocketException) {
                if (!running) return else continue
            }
            if (p.address == ps5) continue
            val head = String(buf, 0, minOf(p.length, 40))
            when {
                head.startsWith("SRCH") -> {
                    onPortal()
                    val c = cache[0]
                    if (c != null) {
                        runCatching { sock.send(DatagramPacket(c, c.size, p.socketAddress)) }
                        log("SRCH from ${p.address.hostAddress}:${p.port} -> answered as PS5")
                    } else {
                        log("SRCH from ${p.address.hostAddress} but no PS5 reply cached yet (is the PS5 IP right?)")
                    }
                    runCatching { up.send(DatagramPacket(buf, p.length, ps5, 9302)) }
                }
                head.startsWith("WAKEUP") -> {
                    log("WAKEUP from ${p.address.hostAddress} -> forwarded to PS5")
                    runCatching { up.send(DatagramPacket(buf, p.length, ps5, 9302)) }
                }
                else -> log("9302 from ${p.address.hostAddress}: ${head.replace('\n', ' ')}")
            }
        }
    }

    // ---------- 9295 TCP relay ----------
    private fun tcpLoop(srv: ServerSocket, port: Int) {
        while (running) {
            val c = try { srv.accept() } catch (e: SocketException) { return }
            c.track()
            onPortal()
            log("TCP $port connection from ${c.inetAddress.hostAddress}:${c.port} -> relaying to PS5")
            thread(isDaemon = true) {
                val up = Socket().track()
                try {
                    upstream?.bindSocket(up)
                    up.connect(InetSocketAddress(ps5, port), 10_000)
                } catch (e: Exception) {
                    log("cannot reach PS5:$port: ${e.message}")
                    runCatching { c.close() }; runCatching { up.close() }
                    return@thread
                }
                c.tcpNoDelay = true; up.tcpNoDelay = true
                thread(isDaemon = true) { pipe(c, up, "Portal->PS5") }
                pipe(up, c, "PS5->Portal")
            }
        }
    }

    private fun pipe(src: Socket, dst: Socket, name: String) {
        val buf = ByteArray(65536)
        try {
            val i = src.getInputStream(); val o = dst.getOutputStream()
            while (true) {
                val n = i.read(buf)
                if (n < 0) break
                o.write(buf, 0, n)
            }
        } catch (_: Exception) {
        } finally {
            runCatching { src.shutdownInput() }; runCatching { dst.shutdownOutput() }
            runCatching { src.close() }; runCatching { dst.close() }
            closeables.remove(src); closeables.remove(dst)
            if (running) log("TCP $name closed")
        }
    }

    // ---------- 9296 / 9297 UDP relay ----------
    // WMM priority (DSCP -> 802.11 access category): video to the Portal as CS5 (AC_VI),
    // Portal input/acks to the PS5 as CS6 (AC_VO) so they jump ahead of the video queue.
    private val TOS_VIDEO = 0xA0
    private val TOS_INPUT = 0xC0

    // Socket buffers are a queue on the phone. The Portal (like chiaki-ng's receiver) waits only
    // ~16 ms (one frame) for a missing packet and then skips ahead, so video delivered later than
    // that is thrown away anyway while it delays everything queued behind it. 256 KB is ~35 ms at
    // 60 Mbps: enough to absorb a scheduling hiccup, small enough that a radio stall drops stale
    // packets here instead of forwarding a 100+ ms backlog (v0.14 and earlier used 1 MB).
    private val UDP_BUF = 256 shl 10

    /** Lost PS5->Portal video packets on the home hop, from the plaintext Takion sequence number. */
    private val videoLoss = VideoLoss()

    /** Stream/input packets must not queue behind other work on the phone. */
    private fun urgent() = runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO) }

    private fun udpLoop(down: DatagramSocket, port: Int, patchable: Boolean, bytes: AtomicLong) {
        urgent()
        val flows = ConcurrentHashMap<InetSocketAddress, DatagramSocket>()
        val buf = ByteArray(65536)
        var patched = 0
        var seen = 0
        val p = DatagramPacket(buf, buf.size)
        val out = DatagramPacket(buf, 0)
        while (running) {
            p.setData(buf, 0, buf.size)
            try {
                down.receive(p)
            } catch (e: PortUnreachableException) {
                continue
            } catch (e: SocketException) {
                if (!running) return else continue
            }
            val client = p.socketAddress as InetSocketAddress
            var up = flows[client]
            if (up == null) {
                up = DatagramSocket().also { upstream?.bindSocket(it) }.apply {
                    receiveBufferSize = UDP_BUF
                    trafficClass = TOS_INPUT
                    connect(ps5, port)
                }.track()
                flows[client] = up
                log("UDP $port new flow from ${client.address.hostAddress}:${client.port} -> PS5 via ${up.localPort}")
                val u = up
                val loss = if (patchable) videoLoss.also { it.reset() } else null
                thread(isDaemon = true) { udpUpstream(u, down, client, bytes, loss, flows) }
                patched = 0; seen = 0
                if (patchable) {
                    portalPing?.stop()
                    portalPing = Pinger(client.address).start()
                }
            }
            val patch = if (patchable) this.patch else null
            if (patch != null) {
                when (Patch.mutate(buf, p.length, patch)) {
                    Patch.Result.PATCHED -> {
                        log("*** startup packet PATCHED (${++patched}), LaunchSpec ${Patch.lastSpecSize} ***")
                        if (patched == 1) onBoost(true)
                    }
                    Patch.Result.WRONG_SIZE -> {
                        log("startup packet found but LaunchSpec size ${Patch.lastSpecSize} is far from ${Patch.LAUNCH_SPEC_SIZE}: NOT patching")
                        onBoost(false)
                    }
                    Patch.Result.NOT_MATCHED ->
                        // A resumed session never sends the startup packet, so the bitrate can't change.
                        if (patched == 0 && ++seen == 300) {
                            log("no startup packet in this stream (resumed session?): boost unchanged")
                            onBoost(null)
                        }
                }
            }
            try {
                out.setData(buf, 0, p.length)
                up.send(out)
            } catch (_: PortUnreachableException) {
            } catch (_: SocketException) {
            }
        }
    }

    private fun udpUpstream(
        up: DatagramSocket, down: DatagramSocket, client: InetSocketAddress,
        bytes: AtomicLong, loss: VideoLoss?, flows: ConcurrentHashMap<InetSocketAddress, DatagramSocket>,
    ) {
        urgent()
        val buf = ByteArray(65536)
        val p = DatagramPacket(buf, buf.size)
        val out = DatagramPacket(buf, 0, client)
        while (running) {
            p.setData(buf, 0, buf.size)
            try {
                up.receive(p)
                bytes.addAndGet(p.length.toLong())
                loss?.packet(buf, p.length)
                out.setData(buf, 0, p.length)
                down.send(out)
            } catch (e: PortUnreachableException) {
                continue // transient ICMP unreachable; keep the flow alive
            } catch (e: Exception) {
                flows.remove(client)
                closeables.remove(up)
                return
            }
        }
    }

    private fun statsLoop() {
        var prev = 0L
        var logged = 0L
        var tick = 0
        var smooth = 0.0
        var lost5 = 0L; var recv5 = 0L
        var diag = 0
        while (running) {
            try { Thread.sleep(1000) } catch (_: InterruptedException) { return }
            val now = streamBytes.get()
            val mbps = (now - prev) * 8 / 1e6
            prev = now
            smooth = if (smooth == 0.0) mbps else smooth * 0.5 + mbps * 0.5
            val ps5W = ps5Ping.window()
            val portalW = portalPing?.window()
            val lossW = videoLoss.window()
            lost5 += lossW.lost; recv5 += lossW.received
            val ping = "ping router ${routerPing?.window() ?: "-"} | PS5 $ps5W | Portal ${portalW ?: "-"}"
            onStats(smooth, portalW?.takeIf { it.received > 0 }?.avg, ps5W.takeIf { it.received > 0 }?.avg, lossW.pct, ping)
            if (++tick % 5 == 0) {
                val avg5 = (now - logged) * 8 / 5.0 / 1e6
                logged = now
                val total = videoLoss.total()
                val loss = if (recv5 + lost5 > 0) " | video loss %.2f%% (%d/%d, session %d/%d)"
                    .format(lost5 * 100.0 / (recv5 + lost5), lost5, recv5 + lost5, total.lost, total.total) else ""
                lost5 = 0; recv5 = 0
                if (avg5 > 0) log("UDP 9296: %.1f Mbps | %s%s".format(avg5, ping, loss))
                // Diagnostic (first 30 s of video): header byte / size histogram, to verify the Takion video layout.
                if (avg5 > 0 && ++diag <= 6) log("diag 9296 in: ${videoLoss.histogram()}")
                if (avg5 > 0 && diag == 3) videoLoss.samplesText().takeIf { it.isNotEmpty() }?.let { log("diag video headers:\n$it") }
            }
        }
    }
}
