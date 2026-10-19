package dev.portalboost

import android.os.Handler
import android.os.Looper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** In-process log + status shared between ProxyService and MainActivity. */
object LogBus {
    enum class Boost { PENDING, APPLIED, NOT_APPLIED }

    data class Status(
        val running: Boolean = false,
        val profile: String = "65",
        val hotspotSsid: String? = null,
        val hotspotPass: String? = null,
        val proxyAddr: String? = null,
        val mbps: Double = 0.0,
        val ping: String? = null,
        /** Round-trip ping per hop in ms; null until there is a reply. */
        val portalMs: Double? = null,
        val ps5Ms: Double? = null,
        /** Video packets lost between the PS5 and the phone over the last second, in percent; null without video. */
        val lossPct: Double? = null,
        /** Home Wi-Fi and Portal network frequencies in MHz; -1 when unknown. */
        val homeFreq: Int = -1,
        val groupFreq: Int = -1,
        val ps5Ip: String? = null,
        val ps5Name: String? = null,
        val ps5Found: Boolean = false,
        val portalSeen: Boolean = false,
        val boost: Boost = Boost.PENDING,
    )

    private val main = Handler(Looper.getMainLooper())
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)
    private val lines = ArrayDeque<String>()
    private val listeners = mutableListOf<() -> Unit>()

    @Volatile var status = Status()
        private set

    fun log(msg: String) {
        val line = "${fmt.format(Date())} $msg"
        android.util.Log.i("PortalBoost", msg)
        synchronized(lines) {
            lines.addLast(line)
            while (lines.size > 500) lines.removeFirst()
        }
        notifyListeners()
    }

    fun update(f: (Status) -> Status) {
        synchronized(this) { status = f(status) }
        notifyListeners()
    }

    fun text(): String = synchronized(lines) { lines.joinToString("\n") }

    /** The last [n] lines, for the on-screen log. */
    fun tail(n: Int): String = synchronized(lines) { lines.toList().takeLast(n).joinToString("\n") }

    fun clear() {
        synchronized(lines) { lines.clear() }
        notifyListeners()
    }

    fun addListener(l: () -> Unit) { main.post { listeners.add(l); l() } }
    fun removeListener(l: () -> Unit) { main.post { listeners.remove(l) } }

    private fun notifyListeners() { main.post { listeners.toList().forEach { it() } } }
}
