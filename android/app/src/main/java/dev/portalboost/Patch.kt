package dev.portalboost

import java.util.Base64

/**
 * Bitrate mutation for the first Takion "BIG" startup packet (UDP 9296).
 * Port of mutate() from portal_proxy.py / atameric/ps5-portal-high-bitrate:
 * the LaunchSpec is stream-cipher encrypted then base64'd, so XOR-ing the
 * ciphertext at plaintext offset 334 turns "25000" into the chosen bitrate.
 */
object Patch {
    const val OFFSET = 334
    const val LAUNCH_SPEC_SIZE = 2296
    private val LAUNCH_SPEC_SIZES = (LAUNCH_SPEC_SIZE - 16L)..(LAUNCH_SPEC_SIZE + 16L)

    /** LaunchSpec size of the last startup packet seen (for the log). */
    @Volatile var lastSpecSize = 0L
        private set

    // Plaintext at OFFSET is "25000" (kbps). Single-byte profiles replace the leading '2' (0x32):
    // '3' -> 35000, '4' -> 45000, '5' -> 55000, '6' -> 65000. 100/200 rewrite it to "1e+05"/"2e+05".
    val PROFILES: Map<String, ByteArray> = mapOf(
        "35" to byteArrayOf(0x01),
        "45" to byteArrayOf(0x06),
        "55" to byteArrayOf(0x07),
        "65" to byteArrayOf(0x04),
        "100" to hex("03501b0005"),
        "200" to hex("00501b0005"),
    )

    enum class Result { NOT_MATCHED, PATCHED, WRONG_SIZE }

    /** Patches [buf] in place (length never changes). Only touches the narrowly matched startup packet. */
    fun mutate(buf: ByteArray, len: Int, patch: ByteArray): Result {
        fun u(i: Int) = buf[i].toInt() and 0xff
        if (len < 32 || u(0) != 0 || u(13) != 0 || u(14) > 1) return Result.NOT_MATCHED
        for (i in 5..8) if (u(i) != 0) return Result.NOT_MATCHED
        for (i in 21..25) if (u(i) != 0) return Result.NOT_MATCHED
        if (u(26) != 0x08 || u(27) != 0x00 || u(28) != 0x12) return Result.NOT_MATCHED
        if (len != ((u(15) shl 8) or u(16)) + 13) return Result.NOT_MATCHED
        try {
            var pos = readVar(buf, 29, len).second
            val (tag, p1) = readVar(buf, pos, len)
            val (version, p2) = readVar(buf, p1, len)
            if (tag != 8L || version != 20L) return Result.NOT_MATCHED
            pos = p2
            while (pos < len) {
                val (t, a1) = readVar(buf, pos, len)
                if (t and 7L != 2L) return Result.NOT_MATCHED
                // The startup message spans two UDP packets, so the LaunchSpec size (2296)
                // is larger than this packet; only the window around OFFSET must be present.
                val (size, a2) = readVar(buf, a1, len)
                if (size > 1 shl 20) return Result.NOT_MATCHED
                pos = a2
                if (t shr 3 == 3L) {
                    lastSpecSize = size
                    // The size varies slightly with fields after the bitrate (network rtt/mtu values),
                    // which don't move OFFSET; anything further off is a different layout.
                    if (size !in LAUNCH_SPEC_SIZES) return Result.WRONG_SIZE
                    val lo = OFFSET / 3 * 3
                    val a = pos + lo / 3 * 4
                    val end = (OFFSET + patch.size + 2) / 3 * 3
                    val b = pos + end / 3 * 4
                    if (b > len) return Result.NOT_MATCHED
                    val raw = Base64.getDecoder().decode(String(buf, a, b - a, Charsets.US_ASCII))
                    for (i in patch.indices) {
                        raw[OFFSET - lo + i] = (raw[OFFSET - lo + i].toInt() xor patch[i].toInt()).toByte()
                    }
                    val enc = Base64.getEncoder().encode(raw)
                    if (enc.size != b - a) return Result.NOT_MATCHED
                    System.arraycopy(enc, 0, buf, a, enc.size)
                    return Result.PATCHED
                }
                pos += size.toInt()
            }
        } catch (e: IllegalArgumentException) {
        } catch (e: IndexOutOfBoundsException) {
        }
        return Result.NOT_MATCHED
    }

    /** Diagnostic: why [mutate] would not match this packet (first failing check), plus a short header dump. */
    fun explain(buf: ByteArray, len: Int): String {
        fun u(i: Int) = buf[i].toInt() and 0xff
        val hdr = (0 until minOf(len, 40)).joinToString("") { "%02x".format(u(it)) }
        fun r(reason: String) = "len=$len $reason hdr=$hdr"
        if (len < 32) return r("short")
        if (u(0) != 0) return r("type=${u(0)}")
        if (u(13) != 0 || u(14) > 1) return r("chunk=${u(13)} flags=${u(14)}")
        if ((5..8).any { u(it) != 0 }) return r("gmac-nonzero")
        if ((21..25).any { u(it) != 0 }) return r("bytes21-25-nonzero")
        if (u(26) != 0x08 || u(27) != 0x00 || u(28) != 0x12) return r("no-08-00-12")
        val chunkLen = (u(15) shl 8) or u(16)
        if (len != chunkLen + 13) return r("chunklen=$chunkLen")
        return try {
            var pos = readVar(buf, 29, len).second
            val (tag, p1) = readVar(buf, pos, len)
            val (version, p2) = readVar(buf, p1, len)
            if (tag != 8L || version != 20L) return r("tag=$tag version=$version")
            pos = p2
            val fields = mutableListOf<String>()
            while (pos < len) {
                val (t, a1) = readVar(buf, pos, len)
                if (t and 7L != 2L) { fields += "f${t shr 3}w${t and 7}"; break }
                val (size, a2) = readVar(buf, a1, len)
                fields += "f${t shr 3}:$size"
                pos = a2 + size.toInt()
            }
            r("fields=${fields.joinToString(",")}")
        } catch (e: Exception) {
            r("parse-error ${e.message}")
        }
    }

    private fun readVar(b: ByteArray, start: Int, len: Int): Pair<Long, Int> {
        var p = start
        var n = 0L
        for (shift in 0 until 70 step 7) {
            if (p >= len) throw IllegalArgumentException("short varint")
            val c = b[p].toInt() and 0xff
            p++
            n = n or ((c and 127).toLong() shl shift)
            if (c < 128) return n to p
        }
        throw IllegalArgumentException("bad varint")
    }

    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
