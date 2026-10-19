package dev.portalboost

import java.net.InetAddress
import kotlin.concurrent.thread

/**
 * Continuous ICMP ping via /system/bin/ping (allowed for unrooted apps), every 200 ms.
 * Used to see which Wi-Fi hop adds latency: phone->PS5 (home Wi-Fi) and phone->Portal (Wi-Fi Direct).
 */
class Pinger(private val target: InetAddress) {
    data class Stats(val avg: Double, val max: Double, val sent: Int, val received: Int) {
        val lossPct get() = if (sent == 0) 0 else (sent - received) * 100 / sent
        override fun toString() =
            if (received == 0) "no reply" else "%.1f ms avg / %.1f max%s".format(avg, max, if (lossPct > 0) " ($lossPct% lost)" else "")
    }

    private val lock = Object()
    private val samples = mutableListOf<Double>()
    private var lastSeq = -1
    private var sentInWindow = 0
    @Volatile private var proc: Process? = null

    fun start() = apply {
        thread(name = "ping-${target.hostAddress}", isDaemon = true) {
            try {
                val p = ProcessBuilder("ping", "-n", "-i", "0.2", "-W", "1", target.hostAddress)
                    .redirectErrorStream(true).start()
                proc = p
                val time = Regex("""icmp_seq=(\d+).*time=([\d.]+)""")
                p.inputStream.bufferedReader().forEachLine { line ->
                    val m = time.find(line) ?: return@forEachLine
                    val seq = m.groupValues[1].toInt()
                    synchronized(lock) {
                        sentInWindow += if (lastSeq < 0) 1 else (seq - lastSeq).coerceIn(1, 50)
                        lastSeq = seq
                        samples += m.groupValues[2].toDouble()
                    }
                }
            } catch (_: Exception) {
            }
        }
    }

    /** Stats since the previous call, then resets the window. */
    fun window(): Stats = synchronized(lock) {
        val s = Stats(samples.average().takeIf { !it.isNaN() } ?: 0.0, samples.maxOrNull() ?: 0.0, sentInWindow, samples.size)
        samples.clear(); sentInWindow = 0
        s
    }

    fun stop() { proc?.destroy(); proc = null }
}
