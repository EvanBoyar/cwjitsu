package com.cwjitsu.app.audio

import kotlin.random.Random

/**
 * White or brown noise generator. Stateful so the audio thread can ask for next
 * samples without ever blocking.
 */
class NoiseGenerator(
    private val type: com.cwjitsu.app.practice.NoiseType,
    private val random: Random = Random.Default,
) {
    private var brownState: Float = 0f

    /**
     * Fill [out] starting at [offset] with [count] mono float samples.
     * White noise stays within +/-0.6. Brown noise's integrator state is
     * clamped to +/-1 but the 3.5x make-up gain means peaks can exceed
     * full scale; the audio engine's final per-sample clamp bounds the
     * mix. Deliberately left as-is: rescaling to a strict +/-1 would
     * change the noise character users are accustomed to.
     */
    fun fill(out: FloatArray, offset: Int, count: Int) {
        when (type) {
            com.cwjitsu.app.practice.NoiseType.NONE -> {
                for (i in 0 until count) out[offset + i] = 0f
            }
            com.cwjitsu.app.practice.NoiseType.WHITE -> {
                for (i in 0 until count) out[offset + i] = (random.nextFloat() * 2f - 1f) * 0.6f
            }
            com.cwjitsu.app.practice.NoiseType.BROWN -> {
                for (i in 0 until count) {
                    val white = random.nextFloat() * 2f - 1f
                    brownState = (brownState * 0.98f + white * 0.06f).coerceIn(-1f, 1f)
                    out[offset + i] = brownState * 3.5f
                }
            }
        }
    }

    fun reset() {
        brownState = 0f
    }
}
