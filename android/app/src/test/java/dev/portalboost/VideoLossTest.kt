package dev.portalboost

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VideoLossTest {
    private fun video(idx: Int) = byteArrayOf(0x02, (idx shr 8).toByte(), idx.toByte(), 0, 0, 0)
    private fun audio(idx: Int) = byteArrayOf(0x03, (idx shr 8).toByte(), idx.toByte(), 0, 0, 0)

    @Test
    fun countsGapsAndIgnoresAudio() {
        val v = VideoLoss()
        for (i in listOf(10, 11, 12, 15, 16)) v.packet(video(i), 6)
        v.packet(audio(99), 6)
        val w = v.window()
        assertEquals(5, w.received); assertEquals(2, w.lost)
        assertEquals(2 * 100.0 / 7, w.pct!!, 1e-9)
        assertEquals(0, v.window().received) // window resets
    }

    @Test
    fun lateArrivalUndoesLoss_andWrapsAt16Bits() {
        val v = VideoLoss()
        v.packet(video(65534), 6); v.packet(video(65535), 6); v.packet(video(1), 6) // 0 missing across the wrap
        assertEquals(1, v.total().lost)
        v.packet(video(0), 6)   // arrives late
        assertEquals(0, v.total().lost)
        v.packet(video(1), 6)   // duplicate
        assertEquals(4, v.total().received)
        v.packet(video(2), 6)
        assertEquals(0, v.total().lost)
    }

    @Test
    fun emptyWindowHasNoPercentage() {
        assertNull(VideoLoss().window().pct)
    }
}
