package com.cwjitsu.app.practice

import kotlin.math.max
import kotlin.math.roundToLong

/**
 * Pure timing math for Morse code.
 *
 * All durations are in milliseconds. International standard uses PARIS as the
 * reference word (50 dot-lengths per word).
 */
object Timing {

    /**
     * Compute the Farnsworth extension per inter-character / inter-word gap.
     *
     * @param charWpm Character WPM.
     * @param farnsworthWpm Effective Farnsworth WPM (>= charWpm). If null, no Farnsworth.
     */
    fun farnsworthExtensionMs(charWpm: Int, farnsworthWpm: Int?): Long {
        val fw = farnsworthWpm ?: return 0L
        if (fw <= charWpm) return 0L
        val cwMs = 60_000.0 / charWpm
        val fwMs = 60_000.0 / fw
        // ARRL convention: 19 element spaces per standard word.
        return ((fwMs - cwMs) / 19.0).roundToLong().coerceAtLeast(0L)
    }

    /**
     * Compute the dot length in milliseconds for a given character WPM.
     */
    fun dotMs(charWpm: Int): Long = (1200.0 / charWpm).roundToLong().coerceAtLeast(1L)

    /**
     * Compute all timing values given a config.
     */
    fun compute(charWpm: Int, farnsworthWpm: Int?): Timings {
        val dot = dotMs(charWpm)
        val dash = dot * 3
        val intraGap = dot
        val ext = farnsworthExtensionMs(charWpm, farnsworthWpm)
        val charGap = dot * 3 + ext * 3
        val wordGap = dot * 7 + ext * 7
        return Timings(dotMs = dot, dashMs = dash, intraGapMs = intraGap,
            interCharGapMs = charGap, interWordGapMs = wordGap,
            extensionMs = ext)
    }

    /**
     * Duration of a single element after Farnsworth expansion.
     * Farnsworth only changes *gaps*, never dot/dash length.
     */
    fun elementMs(charWpm: Int): Long = dotMs(charWpm)
}

data class Timings(
    val dotMs: Long,
    val dashMs: Long,
    val intraGapMs: Long,
    val interCharGapMs: Long,
    val interWordGapMs: Long,
    val extensionMs: Long,
) {
    val maxGapMs: Long get() = max(interCharGapMs, interWordGapMs)
}
