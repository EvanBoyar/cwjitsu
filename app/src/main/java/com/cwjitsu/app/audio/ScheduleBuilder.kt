package com.cwjitsu.app.audio

import com.cwjitsu.app.practice.ContentItem
import com.cwjitsu.app.practice.Morse
import com.cwjitsu.app.practice.PracticeConfig
import com.cwjitsu.app.practice.Timing
import kotlin.math.max
import kotlin.random.Random

/**
 * One tone event scheduled at absolute sample indices in the AudioTrack buffer.
 */
data class ToneEvent(
    val startSample: Int,
    val endSample: Int,
    val freqHz: Int,
    val amplitude: Float,
    val label: String = "",
)

/**
 * ScheduleBuilder converts content + config into a list of [ToneEvent]s.
 *
 * Spacing rules (matching the PARIS reference of 50 dot-units per word):
 *   - Tone duration: dot = dash / 3 = 1 dot-time per character WPM.
 *   - Intra-element gap (between elements of the same letter or prosign): 1 dot.
 *   - Inter-character gap (between letters inside a multi-letter item): 3 dot + 3·ext.
 *   - Inter-item gap (between items, between repetitions): 7 dot + 7·ext (word length).
 *
 * When [PracticeConfig.randomizeFrequency] is on, a single random frequency is
 * chosen for the whole [ContentItem] (e.g. one tone per callsign, one per letter
 * or word), so a single practice item sounds coherent.
 */
class ScheduleBuilder(
    private val sampleRate: Int,
    private val random: Random = Random.Default,
) {

    fun build(
        items: List<ContentItem>,
        timesToRepeat: Int,
        config: PracticeConfig,
    ): Schedule {
        if (items.isEmpty()) return Schedule(emptyList(), 0, sampleRate)

        val timings = Timing.compute(config.characterWpm, config.effectiveFarnsworth())
        val dotSamples = msToSamples(timings.dotMs)
        val dashSamples = msToSamples(timings.dashMs)
        val intraGapSamples = msToSamples(timings.intraGapMs)
        val charGapSamples = msToSamples(timings.interCharGapMs)
        val wordGapSamples = msToSamples(timings.interWordGapMs)
        val postPauseSamples = msToSamples(config.postSendPauseMs)

        var cursor = 0
        val events = mutableListOf<ToneEvent>()

        // Emit a single letter's morse pattern. Adds intra-gap (1 dt) between elements.
        // All elements share the supplied [itemFreq] so the whole [ContentItem]
        // sounds like one tone when randomizeFrequency is on.
        fun emitLetterMorse(
            letterMorse: String,
            label: String,
            addTrailingIntra: Boolean,
            itemFreq: Int,
        ) {
            for (i in letterMorse.indices) {
                val c = letterMorse[i]
                if (c == '.' || c == '-') {
                    val dur = if (c == '.') dotSamples else dashSamples
                    events += ToneEvent(
                        startSample = cursor,
                        endSample = cursor + dur,
                        freqHz = itemFreq,
                        amplitude = pickAmp(config),
                        label = label,
                    )
                    cursor += dur
                }
                if (i < letterMorse.length - 1 && letterMorse[i + 1] != ' ') {
                    cursor += intraGapSamples
                }
            }
            if (addTrailingIntra) cursor += intraGapSamples
        }

        fun emitItem(item: ContentItem) {
            val label = item.text
            // One random freq for the whole item so the user hears a single tone
            // per character/word/callsign, not a new tone per dit/dah.
            val itemFreq = pickFreq(config)
            if (item.morseOverride != null) {
                // Prosign: emit the joined morse as a single tight symbol.
                emitLetterMorse(
                    item.morseOverride,
                    label = label,
                    addTrailingIntra = false,
                    itemFreq = itemFreq,
                )
                return
            }
            val letters = item.text.uppercase().mapNotNull(Morse::codeFor)
            if (letters.isEmpty()) return
            for ((li, letterMorse) in letters.withIndex()) {
                val isLast = li == letters.lastIndex
                emitLetterMorse(
                    letterMorse,
                    label = label,
                    addTrailingIntra = !isLast,
                    itemFreq = itemFreq,
                )
                if (!isLast) {
                    // Replace the last intra-gap with the inter-character gap.
                    cursor += charGapSamples - intraGapSamples
                }
            }
        }

        val repeatCount = max(1, timesToRepeat)

        for (rep in 0 until repeatCount) {
            items.forEachIndexed { idx, item ->
                emitItem(item)
                if (idx < items.size - 1) {
                    cursor += wordGapSamples
                } else if (rep < repeatCount - 1) {
                    cursor += wordGapSamples + postPauseSamples
                }
            }
        }

        return Schedule(
            events = events.toList(),
            totalSamples = cursor,
            sampleRate = sampleRate,
        )
    }

    private fun msToSamples(ms: Long): Int = max(1, (ms * sampleRate / 1000).toInt())

    private fun pickFreq(config: PracticeConfig): Int =
        if (config.randomizeFrequency) random.nextInt(config.frequencyMinHz, config.frequencyMaxHz + 1)
        else config.frequencyHz

    private fun pickAmp(config: PracticeConfig): Float =
        if (!config.volumeVariationEnabled) 1.0f
        else 0.85f + random.nextFloat() * 0.15f
}

data class Schedule(
    val events: List<ToneEvent>,
    val totalSamples: Int,
    val sampleRate: Int,
)
