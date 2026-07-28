package com.cwjitsu.app.audio

import com.cwjitsu.app.practice.ContentItem
import com.cwjitsu.app.practice.Morse
import com.cwjitsu.app.practice.PracticeConfig
import com.cwjitsu.app.practice.SloppyMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.log10
import kotlin.random.Random

/**
 * Sample rate of 1000 makes 1 sample == 1 ms, so every expected value can be
 * written directly in dot-units with no rounding: at 20 WPM one dot is
 * exactly 60 samples.
 */
class ScheduleBuilderTest {

    private val sampleRate = 1000
    private val dot = 60 // samples per dot at 20 WPM and 1 kHz

    private fun cleanConfig() = PracticeConfig(
        characterWpm = 20,
        farnsworthWpm = null,
        randomizeFrequency = false,
        volumeVariationEnabled = false,
        speedVariabilityEnabled = false,
        sloppyMode = SloppyMode.OFF,
    )

    private fun build(vararg texts: String, config: PracticeConfig = cleanConfig()) =
        ScheduleBuilder(sampleRate, Random(1)).build(texts.map { ContentItem(it) }, config)

    @Test
    fun `empty input yields an empty schedule`() {
        val s = ScheduleBuilder(sampleRate).build(emptyList(), cleanConfig())
        assertTrue(s.events.isEmpty())
        assertEquals(0, s.totalSamples)
    }

    @Test
    fun `single E is one dot long`() {
        val s = build("E")
        assertEquals(1, s.events.size)
        assertEquals(0, s.events[0].startSample)
        assertEquals(dot, s.events[0].endSample)
        assertEquals(dot, s.totalSamples)
    }

    @Test
    fun `PARIS occupies exactly 43 dot units`() {
        // The PARIS reference word is 50 dot-units including its trailing
        // 7-unit word gap; a lone item has no trailing gap, leaving 43.
        val s = build("PARIS")
        assertEquals(43 * dot, s.totalSamples)
        // P(4) A(2) R(3) I(2) S(3) = 14 tone events.
        assertEquals(14, s.events.size)
    }

    @Test
    fun `events never overlap and never run past the schedule end`() {
        val s = build("HELLO WORLD", "CQ CQ DE W1AW")
        var prevEnd = -1
        for (e in s.events) {
            assertTrue("event starts before previous ended", e.startSample >= prevEnd)
            assertTrue("zero or negative length event", e.endSample > e.startSample)
            prevEnd = e.endSample
        }
        assertTrue(s.events.last().endSample <= s.totalSamples)
    }

    @Test
    fun `letters inside a word are separated by 3 dots`() {
        val s = build("EE")
        assertEquals(2, s.events.size)
        val gap = s.events[1].startSample - s.events[0].endSample
        assertEquals(3 * dot, gap)
    }

    @Test
    fun `words inside one item are separated by 7 dots`() {
        val s = build("E E")
        assertEquals(2, s.events.size)
        val gap = s.events[1].startSample - s.events[0].endSample
        assertEquals(7 * dot, gap)
    }

    @Test
    fun `separate items are separated by 7 dots`() {
        val s = build("E", "E")
        val gap = s.events[1].startSample - s.events[0].endSample
        assertEquals(7 * dot, gap)
    }

    @Test
    fun `prosign override is keyed with intra gaps only`() {
        // <AR> = .-.-. : 5 elements (1+3+1+3+1 = 9 units) + 4 intra gaps = 13.
        val item = ContentItem(text = "<AR>", morseOverride = Morse.prosigns.getValue("AR"))
        val s = ScheduleBuilder(sampleRate, Random(1)).build(listOf(item), cleanConfig())
        assertEquals(5, s.events.size)
        assertEquals(13 * dot, s.totalSamples)
    }

    @Test
    fun `characters without a morse mapping are dropped silently`() {
        val s = build("É~#")
        assertTrue(s.events.isEmpty())
        assertEquals(0, s.totalSamples)
    }

    @Test
    fun `unknown characters inside a word do not add gaps`() {
        // "AéB" must key exactly like "AB".
        assertEquals(build("AB").totalSamples, build("AéB").totalSamples)
    }

    @Test
    fun `fixed frequency is used when randomization is off`() {
        val s = build("SOS", config = cleanConfig().copy(frequencyHz = 700))
        assertTrue(s.events.all { it.freqHz == 700 })
    }

    @Test
    fun `randomized frequency is constant within an item and stays in range`() {
        val config = cleanConfig().copy(
            randomizeFrequency = true, frequencyMinHz = 500, frequencyMaxHz = 800,
        )
        repeat(50) { seed ->
            val builder = ScheduleBuilder(sampleRate, Random(seed))
            val s = builder.build(listOf(ContentItem("SOS"), ContentItem("TEST")), config)
            val freqsPerItem = s.events.groupBy { it.label }.mapValues { (_, ev) ->
                ev.map { it.freqHz }.distinct()
            }
            for ((label, freqs) in freqsPerItem) {
                assertEquals("item $label changed frequency mid-item", 1, freqs.size)
                assertTrue("frequency ${freqs[0]} out of range", freqs[0] in 500..800)
            }
        }
    }

    @Test
    fun `volume variation keeps consecutive items at least 6 dB apart`() {
        val config = cleanConfig().copy(volumeVariationEnabled = true)
        val builder = ScheduleBuilder(sampleRate, Random(7))
        val items = (1..40).map { ContentItem("E$it") }
        val s = builder.build(items, config)
        val ampPerItem = s.events.groupBy { it.label }.mapValues { (_, ev) ->
            ev.map { it.amplitude }.distinct().single()
        }
        val dbs = items.map { -20f * log10(ampPerItem.getValue(it.text)) }
        for (i in 1 until dbs.size) {
            val step = abs(dbs[i] - dbs[i - 1])
            assertTrue("items $i-1 and $i only ${step} dB apart", step >= 5.99f)
        }
        // Every roll stays inside the documented 18 dB window below full scale.
        assertTrue(dbs.all { it >= -0.01f && it <= ScheduleBuilder.VOLUME_VARIATION_RANGE_DB + 0.01f })
    }

    @Test
    fun `volume variation off means full amplitude everywhere`() {
        val s = build("SOS", "TEST")
        assertTrue(s.events.all { it.amplitude == 1.0f })
    }

    @Test
    fun `straight key jitter stays within its advertised bounds`() {
        val config = cleanConfig().copy(sloppyMode = SloppyMode.STRAIGHT_KEY)
        repeat(20) { seed ->
            val s = ScheduleBuilder(sampleRate, Random(seed))
                .build(listOf(ContentItem("PARIS")), config)
            for (e in s.events) {
                val len = e.endSample - e.startSample
                // Elements jitter +/-30% around one dot or one dash.
                val okDot = len >= (dot * 0.70 - 1) && len <= (dot * 1.30 + 1)
                val okDash = len >= (3 * dot * 0.70 - 1) && len <= (3 * dot * 1.30 + 1)
                assertTrue("element length $len outside jitter bounds", okDot || okDash)
                assertTrue(len >= 1)
            }
        }
    }

    @Test
    fun `speed variability stays inside the clamped wpm window`() {
        // Base 6 WPM with -15 variation must clamp at the 5 WPM floor:
        // the slowest legal dot is 1200/5 = 240 ms.
        val config = PracticeConfig(
            characterWpm = 6,
            speedVariabilityEnabled = true,
            speedVarPlusWpm = 15,
            speedVarMinusWpm = 15,
            randomizeFrequency = false,
            volumeVariationEnabled = false,
            sloppyMode = SloppyMode.OFF,
        )
        repeat(50) { seed ->
            val s = ScheduleBuilder(sampleRate, Random(seed))
                .build(listOf(ContentItem("E")), config)
            val len = s.events.single().endSample
            assertTrue("dot of $len ms implies wpm outside 5..21", len in 57..240)
        }
    }

    @Test
    fun `farnsworth stretches gaps but not elements`() {
        val plain = build("EE")
        val farns = build("EE", config = cleanConfig().copy(farnsworthWpm = 10))
        // Same element lengths...
        assertEquals(
            plain.events.map { it.endSample - it.startSample },
            farns.events.map { it.endSample - it.startSample },
        )
        // ...but a wider inter-character gap.
        val plainGap = plain.events[1].startSample - plain.events[0].endSample
        val farnsGap = farns.events[1].startSample - farns.events[0].endSample
        assertTrue(farnsGap > plainGap)
    }
}
