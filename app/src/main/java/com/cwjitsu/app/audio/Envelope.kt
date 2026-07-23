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

    /**
     * Gain for a standalone fade-OUT ramp [fadeLen] samples long: 1 at
     * [pos] 0, easing down the same cosine curve to 0 at [pos] >= [fadeLen].
     * Used to declick a tone that is cut off mid-element (e.g. Skip/Previous/
     * Restart), which otherwise pops because the amplitude drops to zero
     * instantaneously instead of through the normal per-element release ramp.
     */
    fun fadeOutGain(pos: Int, fadeLen: Int): Float {
        if (fadeLen <= 0 || pos >= fadeLen) return 0f
        if (pos <= 0) return 1f
        val phase = pos.toDouble() / fadeLen.toDouble()
        return ((1.0 + cos(phase * PI)) / 2.0).toFloat()
    }

    /**
     * Gain for a standalone fade-IN ramp [fadeLen] samples long: 0 at
     * [pos] 0, easing up the cosine curve to 1 at [pos] >= [fadeLen]. Used
     * to declick a tone RESUMED mid-element (pause froze the sine at a
     * nonzero amplitude, so continuing it would snap back in from silence).
     */
    fun fadeInGain(pos: Int, fadeLen: Int): Float {
        if (fadeLen <= 0 || pos >= fadeLen) return 1f
        if (pos <= 0) return 0f
        val phase = pos.toDouble() / fadeLen.toDouble()
        return ((1.0 - cos(phase * PI)) / 2.0).toFloat()
    }
}
