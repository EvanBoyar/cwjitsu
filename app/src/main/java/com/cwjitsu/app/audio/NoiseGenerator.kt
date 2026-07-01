package com.cwjitsu.app.audio

import kotlin.math.sqrt
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

    /** Fill [out] starting at [offset] with [count] mono float samples in [-1, 1]. */
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

    companion object {
        @Suppress("unused")
        fun normalisedGain(type: com.cwjitsu.app.practice.NoiseType): Float = when (type) {
            com.cwjitsu.app.practice.NoiseType.WHITE -> 1.0f / sqrt(2.0).toFloat()
            com.cwjitsu.app.practice.NoiseType.BROWN -> 1.5f / sqrt(2.0).toFloat()
            com.cwjitsu.app.practice.NoiseType.NONE -> 0f
        }
    }
}
