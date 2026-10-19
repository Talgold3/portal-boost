package dev.portalboost

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException

/** Finds a PS5 on the home Wi-Fi with the Remote Play discovery SRCH (UDP 9302). Blocking: call off the UI thread. */
object Ps5Finder {
    data class Found(val ip: String, val name: String)

    private val SRCH = "SRCH * HTTP/1.1\ndevice-discovery-protocol-version:00030010\n".toByteArray()

    /** Tries the saved address first, then a broadcast; the PS5's address can change once it uses DHCP. */
    fun locate(ctx: Context, preferred: String?): Found? {
        val saved = preferred?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { runCatching { InetAddress.getByName(it) }.getOrNull() }
        if (saved != null) search(ctx, saved, 1500)?.let { return it }
        return search(ctx, InetAddress.getByName("255.255.255.255"), 2500)
    }

    private fun search(ctx: Context, target: InetAddress, timeoutMs: Long): Found? {
        try {
            val cm = ctx.getSystemService(ConnectivityManager::class.java)
            val wifi = cm.allNetworks.firstOrNull {
                cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            }
            DatagramSocket().use { s ->
                wifi?.bindSocket(s)
                s.broadcast = true
                s.soTimeout = 400
                val deadline = System.currentTimeMillis() + timeoutMs
                var nextSend = 0L
                val buf = ByteArray(4096)
                while (System.currentTimeMillis() < deadline) {
                    if (System.currentTimeMillis() > nextSend) {
                        s.send(DatagramPacket(SRCH, SRCH.size, target, 9302))
                        nextSend = System.currentTimeMillis() + 700
                    }
                    val p = DatagramPacket(buf, buf.size)
                    try { s.receive(p) } catch (_: SocketTimeoutException) { continue }
                    val body = String(buf, 0, p.length)
                    if (!body.startsWith("HTTP/1.1")) continue
                    val ip = p.address.hostAddress ?: continue
                    val name = body.lineSequence().firstOrNull { it.startsWith("host-name:") }
                        ?.substringAfter(':')?.trim()?.takeIf { it.isNotEmpty() } ?: "PS5"
                    LogBus.log("found PS5 $name at $ip (${body.substringBefore('\n').trim()})")
                    return Found(ip, name)
                }
            }
        } catch (e: Exception) {
            LogBus.log("PS5 search failed: ${e.message}")
        }
        return null
    }
}
