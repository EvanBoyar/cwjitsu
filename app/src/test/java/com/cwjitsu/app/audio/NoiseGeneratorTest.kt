package com.cwjitsu.app.audio

import com.cwjitsu.app.practice.NoiseType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

class NoiseGeneratorTest {

    @Test
    fun `none writes pure silence`() {
        val buf = FloatArray(1000) { 9f }
        NoiseGenerator(NoiseType.NONE).fill(buf, 0, 1000)
        assertTrue(buf.all { it == 0f })
    }

    @Test
    fun `fill respects offset and count`() {
        val buf = FloatArray(100) { 9f }
        NoiseGenerator(NoiseType.WHITE, Random(1)).fill(buf, 10, 20)
        for (i in 0 until 10) assertEquals("sample $i before offset touched", 9f, buf[i], 0f)
        for (i in 30 until 100) assertEquals("sample $i after range touched", 9f, buf[i], 0f)
        assertTrue((10 until 30).any { buf[it] != 9f })
    }

    @Test
    fun `white noise stays inside its 0_6 headroom and actually varies`() {
        val buf = FloatArray(100_000)
        NoiseGenerator(NoiseType.WHITE, Random(42)).fill(buf, 0, buf.size)
        assertTrue(buf.all { abs(it) <= 0.6f })
        assertTrue("white noise suspiciously quiet", buf.max() > 0.5f)
        assertTrue(buf.min() < -0.5f)
        val mean = buf.average()
        assertTrue("white noise has DC offset $mean", abs(mean) < 0.01)
    }

    @Test
    fun `brown noise is bounded by its clamp times output gain`() {
        // The integrator state is clamped to [-1, 1] and scaled by 3.5, so
        // 3.5 is the hard ceiling. NOTE: the class contract claims [-1, 1]
        // but typical output regularly exceeds that (it relies on the audio
        // engine's final coerceIn for safety); this test pins the true bound.
        val buf = FloatArray(500_000)
        NoiseGenerator(NoiseType.BROWN, Random(42)).fill(buf, 0, buf.size)
        assertTrue(buf.all { abs(it) <= 3.5f })
        assertTrue("brown noise suspiciously quiet", buf.max() > 0.3f)
    }

    @Test
    fun `brown noise is low frequency compared to white`() {
        // Brown noise integrates white noise, so consecutive samples must be
        // far more correlated than white noise's. Average absolute
        // sample-to-sample step is a cheap proxy for spectral tilt.
        fun avgStep(type: NoiseType): Double {
            val buf = FloatArray(100_000)
            NoiseGenerator(type, Random(7)).fill(buf, 0, buf.size)
            var sum = 0.0
            for (i in 1 until buf.size) sum += abs(buf[i] - buf[i - 1]).toDouble()
            val rms = kotlin.math.sqrt(buf.map { it * it.toDouble() }.average())
            return (sum / (buf.size - 1)) / rms
        }
        assertTrue("brown noise not smoother than white", avgStep(NoiseType.BROWN) < avgStep(NoiseType.WHITE) / 2)
    }

    @Test
    fun `reset clears the brown integrator`() {
        val gen = NoiseGenerator(NoiseType.BROWN, Random(3))
        val a = FloatArray(10_000)
        gen.fill(a, 0, a.size)
        gen.reset()
        val b = FloatArray(1)
        gen.fill(b, 0, 1)
        // After reset the integrator restarts near zero: first sample is at
        // most one white-noise step (0.06) scaled by 3.5.
        assertTrue("first sample after reset is ${b[0]}", abs(b[0]) <= 0.06f * 3.5f)
    }
}
