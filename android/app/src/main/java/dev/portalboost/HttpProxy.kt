package dev.portalboost

import android.net.Network
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

/**
 * Minimal HTTP proxy (CONNECT + plain HTTP) for the Portal's internet traffic.
 * The isolated hotspot has no internet, but the Portal refuses Remote Play
 * without it; pointing the Portal's "Proxy Server" setting here gives it
 * PSN access while the PS5's LAN address stays unreachable.
 *
 * Only clients outside the home Wi-Fi subnet are served, so this is not an
 * open proxy on the home network.
 */
class HttpProxy(
    private val port: Int,
    private val upstream: Network?,
    private val isHomeClient: (InetAddress) -> Boolean,
    private val ps5: InetAddress,
    private val log: (String) -> Unit,
) {
    @Volatile private var running = true
    private lateinit var srv: ServerSocket
    private val open = ConcurrentHashMap.newKeySet<Socket>()
    private val seenHosts = ConcurrentHashMap.newKeySet<String>()

    val localPort get() = srv.localPort

    fun start() {
        srv = ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(port)) }
        thread(name = "http-proxy", isDaemon = true) {
            while (running) {
                val c = try { srv.accept() } catch (e: SocketException) { return@thread }
                if (isHomeClient(c.inetAddress)) {
                    runCatching { c.close() }
                    continue
                }
                open.add(c)
                thread(isDaemon = true) { handle(c) }
            }
        }
    }

    fun stop() {
        running = false
        runCatching { srv.close() }
        open.forEach { runCatching { it.close() } }
        open.clear()
    }

    private fun handle(c: Socket) {
        var up: Socket? = null
        try {
            c.soTimeout = 30_000
            val inp = c.getInputStream()
            val head = readHead(inp) ?: return
            val lines = head.split("\r\n")
            val parts = lines[0].split(" ")
            if (parts.size < 3) return
            val (method, target, version) = Triple(parts[0], parts[1], parts[2])

            val host: String
            val hostPort: Int
            var forwardHead: ByteArray? = null
            if (method.equals("CONNECT", ignoreCase = true)) {
                host = target.substringBeforeLast(':')
                hostPort = target.substringAfterLast(':').toIntOrNull() ?: 443
            } else {
                // absolute-form: http://host[:port]/path
                val rest = target.removePrefix("http://")
                val authority = rest.substringBefore('/')
                val path = "/" + rest.substringAfter('/', "")
                host = authority.substringBeforeLast(':').takeIf { authority.contains(':') } ?: authority
                hostPort = if (authority.contains(':')) authority.substringAfterLast(':').toIntOrNull() ?: 80 else 80
                val headers = lines.drop(1).filter {
                    it.isNotEmpty() && !it.startsWith("Proxy-Connection:", true) && !it.startsWith("Connection:", true)
                }
                forwardHead = (listOf("$method $path $version") + headers + listOf("Connection: close", "", ""))
                    .joinToString("\r\n").toByteArray(Charsets.ISO_8859_1)
            }

            val addr = InetAddress.getByName(host)
            if (addr == ps5) log("proxy: Portal asked for the PS5 itself ($host:$hostPort) via the proxy")
            if (seenHosts.add("$host:$hostPort")) log("proxy: Portal -> $host:$hostPort")

            up = Socket().also { s -> upstream?.bindSocket(s) }
            open.add(up)
            up.connect(InetSocketAddress(addr, hostPort), 10_000)
            c.soTimeout = 0
            if (forwardHead == null) {
                c.getOutputStream().write("HTTP/1.1 200 Connection established\r\n\r\n".toByteArray())
            } else {
                up.getOutputStream().write(forwardHead)
            }
            val u = up
            thread(isDaemon = true) { pump(c, u) }
            pump(u, c)
        } catch (e: Exception) {
            if (running && e !is SocketException) log("proxy error: ${e.javaClass.simpleName} ${e.message}")
            runCatching { c.getOutputStream().write("HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\n\r\n".toByteArray()) }
        } finally {
            runCatching { c.close() }; open.remove(c)
            up?.let { runCatching { it.close() }; open.remove(it) }
        }
    }

    /** Reads the request head up to CRLFCRLF (max 16 KB). */
    private fun readHead(inp: InputStream): String? {
        val out = ByteArrayOutputStream()
        var match = 0
        val end = byteArrayOf(13, 10, 13, 10)
        while (out.size() < 16384) {
            val b = inp.read()
            if (b < 0) return null
            out.write(b)
            match = if (b.toByte() == end[match]) match + 1 else if (b == 13) 1 else 0
            if (match == 4) return out.toString("ISO-8859-1").removeSuffix("\r\n\r\n")
        }
        return null
    }

    private fun pump(src: Socket, dst: Socket) {
        val buf = ByteArray(32768)
        try {
            val i = src.getInputStream(); val o = dst.getOutputStream()
            while (true) {
                val n = i.read(buf)
                if (n < 0) break
                o.write(buf, 0, n)
            }
        } catch (_: Exception) {
        } finally {
            runCatching { dst.shutdownOutput() }
            runCatching { src.close() }; runCatching { dst.close() }
        }
    }
}
