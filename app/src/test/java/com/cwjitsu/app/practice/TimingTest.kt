package com.cwjitsu.app.practice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class TimingTest {

    @Test
    fun `dot length follows the 1200 over wpm rule`() {
        assertEquals(60L, Timing.dotMs(20))
        assertEquals(50L, Timing.dotMs(24))
        assertEquals(40L, Timing.dotMs(30))
        assertEquals(240L, Timing.dotMs(5))
        assertEquals(20L, Timing.dotMs(60))
    }

    @Test
    fun `standard ratios hold without farnsworth`() {
        val t = Timing.compute(20, null)
        assertEquals(t.dotMs * 3, t.dashMs)
        assertEquals(t.dotMs, t.intraGapMs)
        assertEquals(t.dotMs * 3, t.interCharGapMs)
        assertEquals(t.dotMs * 7, t.interWordGapMs)
        assertEquals(0L, t.extensionMs)
    }

    @Test
    fun `a PARIS word takes exactly one minute divided by wpm`() {
        // PARIS is the reference word: 31 dot-units of elements and
        // intra-element gaps plus 19 units of char/word gaps = 50 units.
        // At any WPM the full word (with its trailing word gap) must take
        // 60000/wpm milliseconds. This validates the whole timing model.
        for (wpm in listOf(5, 10, 20, 24, 30, 40, 60)) {
            val t = Timing.compute(wpm, null)
            val parisMs = 31 * t.dotMs + 4 * t.interCharGapMs + t.interWordGapMs
            val targetMs = 60_000.0 / wpm
            assertTrue(
                "PARIS at $wpm wpm took ${parisMs}ms, expected ~${targetMs}ms",
                abs(parisMs - targetMs) <= 0.02 * targetMs + 2,
            )
        }
    }

    @Test
    fun `farnsworth slows the effective speed to the requested wpm`() {
        // With Farnsworth the characters stay at charWpm but the whole
        // PARIS word must now take 60000/farnsworthWpm milliseconds.
        val cases = listOf(20 to 10, 25 to 12, 30 to 15, 18 to 5, 40 to 20)
        for ((cw, fw) in cases) {
            val t = Timing.compute(cw, fw)
            val parisMs = 31 * t.dotMs + 4 * t.interCharGapMs + t.interWordGapMs
            val targetMs = 60_000.0 / fw
            assertTrue(
                "PARIS at $cw/$fw wpm took ${parisMs}ms, expected ~${targetMs}ms",
                abs(parisMs - targetMs) <= 0.02 * targetMs + 2,
            )
        }
    }

    @Test
    fun `farnsworth never changes element durations`() {
        val plain = Timing.compute(20, null)
        val farns = Timing.compute(20, 8)
        assertEquals(plain.dotMs, farns.dotMs)
        assertEquals(plain.dashMs, farns.dashMs)
        assertEquals(plain.intraGapMs, farns.intraGapMs)
    }

    @Test
    fun `farnsworth at or above character speed is a no-op`() {
        assertEquals(0L, Timing.farnsworthExtensionMs(20, 20))
        assertEquals(0L, Timing.farnsworthExtensionMs(20, 25))
        assertEquals(0L, Timing.farnsworthExtensionMs(20, null))
    }

    @Test
    fun `dot length never rounds to zero`() {
        // Guard against future WPM range changes: even absurd speeds
        // must yield a positive dot.
        assertTrue(Timing.dotMs(2000) >= 1L)
    }
}
