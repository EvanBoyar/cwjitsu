package com.cwjitsu.app.data

import com.cwjitsu.app.practice.WordSet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class WordBagTest {

    private val freq = listOf("ALPHA", "BRAVO", "CHARLIE", "DELTA", "ECHO")

    private fun bagOf(
        frequency: List<String> = freq,
        sets: Map<WordSet, List<String>> = emptyMap(),
        enabled: Set<WordSet> = emptySet(),
        percent: Int = 0,
        seed: Int = 1,
    ) = WordBag(Random(seed)).apply { configure(frequency, sets, enabled, percent) }

    /** Draw and immediately confirm, the way a played round does. */
    private fun WordBag.play(): String? = next()?.also { markPlayed(it) }

    @Test
    fun `every word is dealt once before any repeats`() {
        val bag = bagOf()
        val heard = List(freq.size) { bag.play() }
        assertEquals(freq.toSet(), heard.toSet())
    }

    @Test
    fun `a draw does not consume - only markPlayed does`() {
        val bag = bagOf()
        // Ten draws with nothing confirmed must not exhaust a five-word pool.
        // This is the abandoned-round case: rounds are built one ahead of
        // playback, so consuming at draw time would silently skip words.
        repeat(10) { assertTrue(bag.next() in freq) }
        val heard = List(freq.size) { bag.play() }
        assertEquals(freq.toSet(), heard.toSet())
    }

    @Test
    fun `a word is not repeated across the cycle boundary`() {
        val bag = bagOf()
        val heard = List(freq.size * 3) { bag.play() }
        for (i in 1 until heard.size) {
            assertNotEquals("repeat at position $i in $heard", heard[i - 1], heard[i])
        }
    }

    @Test
    fun `narrowing the pool keeps progress for the words that survive`() {
        val bag = bagOf()
        val first = bag.play()
        val second = bag.play()
        // The user pulls the vocabulary slider down; the two words already
        // heard are still in range, so they should stay heard.
        bag.configure(freq.take(4), emptyMap(), emptySet(), 0)
        val next = List(2) { bag.play() }
        assertTrue("$first should not come back so soon", first !in next)
        assertTrue("$second should not come back so soon", second !in next)
    }

    @Test
    fun `an empty pool draws nothing`() {
        assertNull(bagOf(frequency = emptyList()).next())
    }

    @Test
    fun `enrichment is off when no sets are enabled`() {
        val bag = bagOf(
            sets = mapOf(WordSet.RADIO to listOf("QRP")),
            enabled = emptySet(),
            percent = 50,
        )
        repeat(20) { assertTrue(bag.play() in freq) }
    }

    @Test
    fun `enabled sets take roughly their configured share`() {
        val radio = listOf("QRP", "QRO", "QSL")
        val bag = bagOf(
            sets = mapOf(WordSet.RADIO to radio),
            enabled = setOf(WordSet.RADIO),
            percent = 50,
            seed = 7,
        )
        val heard = List(400) { bag.play() }
        val fromRadio = heard.count { it in radio }
        // Wide bounds: this asserts the weighting is wired up at all, not
        // that a seeded RNG hits an exact ratio.
        assertTrue("radio share was $fromRadio/400", fromRadio in 100..300)
    }

    @Test
    fun `a word in two pools is consumed from both`() {
        // RADIO lives in the frequency list AND the amateur radio set, on
        // purpose. Playing it should mark it in both, so it comes up once
        // per cycle rather than once per pool.
        val bag = bagOf(
            frequency = listOf("RADIO", "OTHER"),
            sets = mapOf(WordSet.RADIO to listOf("RADIO", "QRP")),
            enabled = setOf(WordSet.RADIO),
            percent = 50,
        )
        val heard = List(4) { bag.play() }
        assertEquals("RADIO should appear once per cycle, not twice: $heard",
            1, heard.take(3).count { it == "RADIO" })
    }

    @Test
    fun `progress survives a snapshot and restore`() {
        val bag = bagOf()
        val heard = List(3) { bag.play() }
        val state = bag.snapshot()

        val restored = bagOf(seed = 99)
        restored.restore(state)
        val next = List(2) { restored.play() }
        assertEquals(
            "the restored bag should finish the cycle, not restart it",
            freq.toSet() - heard.toSet(), next.toSet(),
        )
    }

    @Test
    fun `restore drops words that are no longer in the pool`() {
        val bag = bagOf(frequency = freq.take(2))
        bag.restore(WordBagState(mapOf(WordBag.FREQUENCY_KEY to setOf("ALPHA", "GONE"))))
        // Only BRAVO is unheard, so it must come next; the stale mark for a
        // word outside the pool must not shrink what is left to hear.
        assertEquals("BRAVO", bag.play())
    }
}
