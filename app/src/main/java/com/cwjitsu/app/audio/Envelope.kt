package com.cwjitsu.app.audio

import kotlin.math.PI
import kotlin.math.cos

/**
 * Cosine-shaped fade-in / fade-out ramp applied at the start and end of every dot/dash.
 * Hides the click that occurs when a hard sine wave is suddenly switched on/off.
 */
class Envelope(private val sampleRate: Int) {

    /**
     * Length of each fade in/out in milliseconds. Short to preserve timing fidelity.
     */
    val rampMs: Int = 5

    private val rampSamples: Int = (sampleRate * rampMs / 1000).coerceAtLeast(2)

    /** Compute gain multiplier for sample [index] within a tone that's [toneLenSamples] long. */
    fun gain(index: Int, toneLenSamples: Int): Float {
        if (toneLenSamples <= 2 * rampSamples) return 1f
        return when {
            index < rampSamples -> fade(index)
            index > toneLenSamples - rampSamples -> fade(toneLenSamples - index)
            else -> 1f
        }
    }

    private fun fade(pos: Int): Float {
        val phase = pos.toDouble() / rampSamples.toDouble()
        return ((1.0 - cos(phase * PI)) / 2.0).toFloat()
    }
}
