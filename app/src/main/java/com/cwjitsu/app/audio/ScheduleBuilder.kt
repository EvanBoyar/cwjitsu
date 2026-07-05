package com.cwjitsu.app.audio

import com.cwjitsu.app.practice.ContentItem
import com.cwjitsu.app.practice.Morse
import com.cwjitsu.app.practice.PracticeConfig
import com.cwjitsu.app.practice.SloppyMode
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
        //
        // Sloppy-mode perturbations live here so they don't leak into the
        // timing math elsewhere in the pipeline. STRAIGHT_KEY jitters every
        // element/gap duration by a hand-tuned percentage to sound like a
        // hand-pumped key.
        fun emitLetterMorse(
            letterMorse: String,
            label: String,
            addTrailingIntra: Boolean,
            itemFreq: Int,
            itemAmp: Float,
        ) {
            for (i in letterMorse.indices) {
                val c = letterMorse[i]
                if (c == '.' || c == '-') {
                    val baseDur = if (c == '.') dotSamples else dashSamples
                    val dur = jitterElement(baseDur, config.sloppyMode)
                    events += ToneEvent(
                        startSample = cursor,
                        endSample = cursor + dur,
                        freqHz = itemFreq,
                        amplitude = itemAmp,
                        label = label,
                    )
                    cursor += dur
                }
                if (i < letterMorse.length - 1 && letterMorse[i + 1] != ' ') {
                    cursor += jitterGap(intraGapSamples, config.sloppyMode)
                }
            }
            if (addTrailingIntra) {
                cursor += jitterGap(intraGapSamples, config.sloppyMode)
            }
        }

        fun emitItem(item: ContentItem) {
            val label = item.text
            // One random freq for the whole item so the user hears a single tone
            // per character/word/callsign, not a new tone per dit/dah. Same for
            // the amplitude: volume variation rolls once per item (like a
            // different station's signal strength), not per element.
            val itemFreq = pickFreq(config)
            val itemAmp = pickAmp(config)
            if (item.morseOverride != null) {
                // Prosign: emit the joined morse as a single tight symbol.
                emitLetterMorse(
                    item.morseOverride,
                    label = label,
                    addTrailingIntra = false,
                    itemFreq = itemFreq,
                    itemAmp = itemAmp,
                )
                return
            }
            // Split on whitespace so multi-word items (e.g. a news headline)
            // get real inter-word gaps. Single-word items (letters, callsigns,
            // words) have exactly one chunk here, so their timing is unchanged.
            val words = item.text.uppercase()
                .split(Regex("\\s+"))
                .map { it.mapNotNull(Morse::codeFor) }
                .filter { it.isNotEmpty() }
            if (words.isEmpty()) return
            for ((wi, letters) in words.withIndex()) {
                for ((li, letterMorse) in letters.withIndex()) {
                    val isLast = li == letters.lastIndex
                    emitLetterMorse(
                        letterMorse,
                        label = label,
                        addTrailingIntra = !isLast,
                        itemFreq = itemFreq,
                        itemAmp = itemAmp,
                    )
                    if (!isLast) {
                        // Replace the last intra-gap with the inter-character gap.
                        cursor += charGapSamples - intraGapSamples
                    }
                }
                if (wi < words.lastIndex) cursor += wordGapSamples
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

    /**
     * Roll one amplitude for a whole item. The 0.35..1.0 span is ~9 dB -
     * clearly audible as "a weaker station" while the quietest roll still
     * sits well above any background noise. The previous 0.85..1.0 span
     * (~1.4 dB) was below the just-noticeable difference and sounded like
     * the setting did nothing.
     */
    private fun pickAmp(config: PracticeConfig): Float =
        if (!config.volumeVariationEnabled) 1.0f
        else 0.35f + random.nextFloat() * 0.65f

    /**
     * Apply sloppy-mode jitter to an individual element duration (a dot or
     * a dash). +/-30% makes the rhythm obviously loose - +/-10% reads as
     * machine-clean to the human ear. Returns at least 1 sample so the
     * audio engine never receives a zero-length event.
     */
    private fun jitterElement(baseSamples: Int, mode: SloppyMode): Int =
        when (mode) {
            SloppyMode.OFF -> baseSamples
            SloppyMode.STRAIGHT_KEY ->
                jitterSamples(baseSamples, 0.70f..1.30f)
        }

    /**
     * Apply sloppy-mode jitter to a gap. Inter-element / inter-character
     * gaps are where a real straight key sounds most obviously human: the
     * operator listens ahead and over/under-pauses. +/-45% makes the
     * cadence vary widely. Narrower ranges collapse into a uniform rhythm.
     */
    private fun jitterGap(baseSamples: Int, mode: SloppyMode): Int =
        when (mode) {
            SloppyMode.OFF -> baseSamples
            SloppyMode.STRAIGHT_KEY ->
                jitterSamples(baseSamples, 0.55f..1.45f)
        }

    private fun jitterSamples(baseSamples: Int, scaleRange: ClosedFloatingPointRange<Float>): Int {
        // Compute the scale factor manually so this compiles against any
        // Kotlin version. `Random.nextFloat(from, until)` is convenient but
        // not present in every stdlib; doing it ourselves removes the
        // dependency on that overload.
        val span = scaleRange.endInclusive - scaleRange.start
        val factor = scaleRange.start + random.nextFloat() * span
        // Floor at 1 sample so the engine never receives a zero-length
        // event that would just consume CPU for no audible output.
        return max(1, (baseSamples.toFloat() * factor).toInt())
    }
}

data class Schedule(
    val events: List<ToneEvent>,
    val totalSamples: Int,
    val sampleRate: Int,
)
