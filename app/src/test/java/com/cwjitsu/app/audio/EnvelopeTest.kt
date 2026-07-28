package com.cwjitsu.app.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EnvelopeTest {

    private val sampleRate = 48_000
    private val env = Envelope(sampleRate)
    private val ramp = sampleRate * env.rampMs / 1000 // 240 samples

    @Test
    fun `tone starts from silence and ramps to full gain`() {
        val toneLen = 10 * ramp
        assertEquals(0f, env.gain(0, toneLen), 1e-6f)
        assertEquals(1f, env.gain(ramp, toneLen), 1e-6f)
        assertEquals(1f, env.gain(toneLen / 2, toneLen), 1e-6f)
    }

    @Test
    fun `attack ramp rises monotonically`() {
        val toneLen = 10 * ramp
        var prev = -1f
        for (i in 0..ramp) {
            val g = env.gain(i, toneLen)
            assertTrue("gain fell from $prev to $g at sample $i", g >= prev)
            assertTrue(g in 0f..1f)
            prev = g
        }
    }

    @Test
    fun `release ramp mirrors the attack ramp`() {
        val toneLen = 10 * ramp
        for (i in 0 until ramp) {
            assertEquals(
                "asymmetric envelope at offset $i",
                env.gain(i, toneLen),
                env.gain(toneLen - i, toneLen),
                1e-6f,
            )
        }
    }

    @Test
    fun `no sample-to-sample gain jump exceeds the click threshold`() {
        // A step bigger than ~2% of full scale between adjacent samples is
        // audible as a click, which is the one thing an envelope exists to
        // prevent.
        val toneLen = 4 * ramp
        var prev = env.gain(0, toneLen)
        for (i in 1..toneLen) {
            val g = env.gain(i, toneLen)
            assertTrue("gain jumped ${g - prev} at sample $i", kotlin.math.abs(g - prev) < 0.02f)
            prev = g
        }
    }

    @Test
    fun `very short tones skip the envelope entirely`() {
        val toneLen = 2 * ramp
        for (i in 0 until toneLen) {
            assertEquals(1f, env.gain(i, toneLen), 1e-6f)
        }
    }

    @Test
    fun `standalone fade out runs from full gain to silence`() {
        val len = 100
        assertEquals(1f, env.fadeOutGain(0, len), 1e-6f)
        assertEquals(0.5f, env.fadeOutGain(len / 2, len), 1e-3f)
        assertEquals(0f, env.fadeOutGain(len, len), 1e-6f)
        assertEquals(0f, env.fadeOutGain(len + 50, len), 1e-6f)
        var prev = 2f
        for (i in 0..len) {
            val g = env.fadeOutGain(i, len)
            assertTrue("fade-out rose at $i", g <= prev)
            prev = g
        }
    }

    @Test
    fun `standalone fade in runs from silence to full gain`() {
        val len = 100
        assertEquals(0f, env.fadeInGain(0, len), 1e-6f)
        assertEquals(0.5f, env.fadeInGain(len / 2, len), 1e-3f)
        assertEquals(1f, env.fadeInGain(len, len), 1e-6f)
        assertEquals(1f, env.fadeInGain(len + 50, len), 1e-6f)
    }

    @Test
    fun `degenerate fade lengths do not divide by zero`() {
        assertEquals(0f, env.fadeOutGain(0, 0), 1e-6f)
        assertEquals(1f, env.fadeInGain(0, 0), 1e-6f)
        assertEquals(0f, env.fadeOutGain(5, -1), 1e-6f)
        assertEquals(1f, env.fadeInGain(5, -1), 1e-6f)
    }
}
