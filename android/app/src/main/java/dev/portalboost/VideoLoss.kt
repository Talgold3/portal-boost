package dev.portalboost

/**
 * Counts lost PS5->Portal video packets on the home hop.
 *
 * Takion AV packets carry a plaintext header (see chiaki-ng lib/src/takion.c, av_packet_parse):
 * byte 0 low nibble = packet type (2 = video, 3 = audio), bytes 1..2 = 16-bit packet index.
 * Only the payload is encrypted, so the relay can see sequence gaps without any keys.
 * A gap seen here means the packet was lost between the PS5 and the phone (router / home Wi-Fi);
 * loss on the Portal leg is invisible to us.
 */
class VideoLoss {
    private var last = -1
    private var received = 0L
    private var lost = 0L
    private var wReceived = 0L
    private var wLost = 0L
    /** Diagnostic: packets per first header byte, and per size bucket, since reset(). */
    private val byType = LongArray(256)
    private val bySize = LongArray(8)
    /** Diagnostic: headers of consecutive video packets (hex), captured once mid-stream. */
    private var videoSeen = 0L
    private val samples = ArrayList<String>()

    @Synchronized fun reset() {
        last = -1; received = 0; lost = 0; wReceived = 0; wLost = 0
        byType.fill(0); bySize.fill(0); videoSeen = 0; samples.clear()
    }

    /** The captured consecutive video headers, one per line (empty until captured). */
    @Synchronized fun samplesText(): String = samples.joinToString("\n")

    /** Diagnostic summary: which first bytes and sizes the PS5->Portal packets have (to find the video header). */
    @Synchronized fun histogram(): String {
        val types = byType.indices.filter { byType[it] > 0 }.sortedByDescending { byType[it] }.take(8)
            .joinToString(" ") { "%02x:%d".format(it, byType[it]) }
        val sizes = listOf("<64", "<128", "<256", "<512", "<1024", "<1280", "<1500", ">=1500")
            .mapIndexedNotNull { i, l -> if (bySize[i] > 0) "$l:${bySize[i]}" else null }.joinToString(" ")
        return "byte0 [$types] size [$sizes]"
    }

    /** Feed one PS5->Portal packet. Non-video packets are ignored. */
    @Synchronized fun packet(buf: ByteArray, len: Int) {
        if (len < 1) return
        byType[buf[0].toInt() and 0xff]++
        bySize[when { len < 64 -> 0; len < 128 -> 1; len < 256 -> 2; len < 512 -> 3; len < 1024 -> 4; len < 1280 -> 5; len < 1500 -> 6; else -> 7 }]++
        if (len < 3 || (buf[0].toInt() and 0x0f) != TYPE_VIDEO) return
        if (++videoSeen in 2000..2011) {
            samples += "len=$len " + (0 until minOf(len, 24)).joinToString("") { "%02x".format(buf[it].toInt() and 0xff) }
        }
        val idx = ((buf[1].toInt() and 0xff) shl 8) or (buf[2].toInt() and 0xff)
        if (last >= 0) {
            val delta = (idx - last) and 0xffff
            when {
                delta == 1 -> {}
                delta in 2..0x7fff -> lost += delta - 1          // gap ahead: packets missing (so far)
                delta == 0 -> return                             // duplicate: not a new packet
                else -> { received++; if (lost > 0) lost--; return } // late arrival of a packet counted as lost
            }
        }
        received++
        last = idx
    }

    class Window(val received: Long, val lost: Long) {
        val total get() = received + lost
        /** Loss in percent, or null when nothing arrived. */
        val pct: Double? get() = if (total == 0L) null else lost * 100.0 / total
    }

    /** Packets received and lost since the previous call. */
    @Synchronized fun window(): Window {
        val w = Window(received - wReceived, lost - wLost)
        wReceived = received; wLost = lost
        return w
    }

    /** Totals for the whole stream. */
    @Synchronized fun total(): Window = Window(received, lost)

    companion object { const val TYPE_VIDEO = 2 }
}
