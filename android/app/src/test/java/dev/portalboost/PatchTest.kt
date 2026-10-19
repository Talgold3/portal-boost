package dev.portalboost

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.Base64
import kotlin.random.Random

/** Mirrors the Python self-test: synthetic startup packet with "25000" encrypted at offset 334. */
class PatchTest {
    private val rnd = Random(42)

    private fun varint(v: Int): ByteArray {
        val out = ByteArrayOutputStream()
        var n = v
        while (true) {
            val b = n and 127
            n = n ushr 7
            if (n != 0) out.write(b or 128) else { out.write(b); return out.toByteArray() }
        }
    }

    private class Built(val pkt: ByteArray, val key: ByteArray, val specAt: Int)

    private fun build(specSize: Int = 2296): Built {
        val plain = rnd.nextBytes(1722)
        "25000".toByteArray().copyInto(plain, 334)
        val key = rnd.nextBytes(1722)
        val ct = ByteArray(1722) { (plain[it].toInt() xor key[it].toInt()).toByte() }
        val b64 = Base64.getEncoder().encode(ct).copyOf(specSize).also { a ->
            for (i in a.indices) if (a[i] == 0.toByte()) a[i] = 'A'.code.toByte()
        }
        val inner = ByteArrayOutputStream().apply {
            write(0x08); write(varint(20)); write(0x1a); write(varint(b64.size)); write(b64)
        }.toByteArray()
        val body = varint(inner.size) + inner
        val hdr = ByteArray(29).also { it[26] = 0x08; it[27] = 0; it[28] = 0x12 }
        val pkt = hdr + body
        val l = pkt.size - 13
        pkt[15] = (l shr 8).toByte(); pkt[16] = l.toByte()
        return Built(pkt, key, pkt.size - b64.size)
    }

    private fun plainAt334(b: Built, pkt: ByteArray): String {
        val ct = Base64.getDecoder().decode(pkt.copyOfRange(b.specAt, b.specAt + 2296))
        return String(ByteArray(5) { (ct[334 + it].toInt() xor b.key[334 + it].toInt()).toByte() })
    }

    @Test
    fun patchesAllProfiles() {
        for ((profile, want) in listOf(
            "35" to "35000", "45" to "45000", "55" to "55000",
            "65" to "65000", "100" to "1e+05", "200" to "2e+05",
        )) {
            val b = build()
            val pkt = b.pkt.copyOf()
            assertEquals(Patch.Result.PATCHED, Patch.mutate(pkt, pkt.size, Patch.PROFILES.getValue(profile)))
            assertEquals(want, plainAt334(b, pkt))
            val changed = pkt.indices.count { pkt[it] != b.pkt[it] }
            assertEquals(if (Patch.PROFILES.getValue(profile).size == 1) 1 else 5, changed)
        }
    }

    /**
     * Real Portal shape (from a device log): the ~2.6 KB startup message is split, so the first
     * UDP packet is 1226 bytes and carries field 2 (74 bytes) plus only the start of the 2296-byte LaunchSpec.
     */
    @Test
    fun patchesFirstFragmentOfSplitStartupMessage() {
        for ((profile, want) in listOf("65" to "65000", "100" to "1e+05", "200" to "2e+05")) {
            val plain = rnd.nextBytes(1722)
            "25000".toByteArray().copyInto(plain, 334)
            val key = rnd.nextBytes(1722)
            val b64 = Base64.getEncoder().encode(ByteArray(1722) { (plain[it].toInt() xor key[it].toInt()).toByte() })
            val inner = ByteArrayOutputStream().apply {
                write(0x08); write(varint(20))
                write(0x12); write(varint(74)); write(ByteArray(74) { '1'.code.toByte() })
                write(0x1a); write(varint(b64.size)); write(b64)
            }.toByteArray()
            val full = ByteArray(29).also { it[26] = 0x08; it[27] = 0; it[28] = 0x12 } + varint(inner.size) + inner
            val pkt = full.copyOf(1226)
            pkt[15] = ((1226 - 13) shr 8).toByte(); pkt[16] = (1226 - 13).toByte()
            val specAt = full.size - b64.size
            val before = pkt.copyOf()

            assertEquals(Patch.Result.PATCHED, Patch.mutate(pkt, pkt.size, Patch.PROFILES.getValue(profile)))
            // chars 444..452 of the base64 decode to ciphertext bytes 333..338
            val ct = Base64.getDecoder().decode(pkt.copyOfRange(specAt + 444, specAt + 452))
            val got = String(ByteArray(5) { (ct[1 + it].toInt() xor key[334 + it].toInt()).toByte() })
            assertEquals(want, got)
            assertEquals(if (profile == "65") 1 else 5, pkt.indices.count { pkt[it] != before[it] })
        }
    }

    @Test
    fun leavesOtherPacketsAlone() {
        val wrong = build(2000)
        val copy = wrong.pkt.copyOf()
        assertEquals(Patch.Result.WRONG_SIZE, Patch.mutate(copy, copy.size, Patch.PROFILES.getValue("65")))
        assertArrayEquals(wrong.pkt, copy)

        // Slightly different LaunchSpec size (varying network fields after the bitrate) is still patched.
        val near = build(2304)
        val nearCopy = near.pkt.copyOf()
        assertEquals(Patch.Result.PATCHED, Patch.mutate(nearCopy, nearCopy.size, Patch.PROFILES.getValue("65")))
        assertEquals(2304L, Patch.lastSpecSize)

        val random = rnd.nextBytes(1400)
        assertEquals(Patch.Result.NOT_MATCHED, Patch.mutate(random.copyOf(), random.size, Patch.PROFILES.getValue("65")))
    }
}
