package com.cwjitsu.app.data

import com.cwjitsu.app.practice.Headline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * The no-repeat guarantees the news feature keeps regressing on, pinned down
 * as unit tests. Every test uses a seeded [Random] so failures reproduce.
 */
class HeadlineBagTest {

    private fun headline(n: Int, source: String = "feed") = Headline(
        id = "https://example.com/story-$n",
        title = "Story number $n with some words",
        sourceId = source,
        sourceName = source,
    )

    private fun pool(count: Int) = (1..count).map { headline(it) }

    @Test
    fun `empty eligible list draws null`() {
        assertNull(HeadlineBag(Random(1)).draw(emptyList(), now = 1_000L))
    }

    @Test
    fun `every headline plays exactly once before any repeat`() {
        val bag = HeadlineBag(Random(42))
        val eligible = pool(10)
        val heard = mutableSetOf<String>()
        var now = 1_000L
        repeat(10) {
            val pick = bag.draw(eligible, now)!!
            assertTrue("repeat of ${pick.id} before pool exhausted", heard.add(pick.id))
            bag.markPlayed(pick, now)
            now += 1_000
        }
        assertEquals(10, heard.size)
    }

    @Test
    fun `no back-to-back repeat even after the pool is exhausted`() {
        val bag = HeadlineBag(Random(7))
        val eligible = pool(3)
        var now = 1_000L
        var previous: String? = null
        repeat(300) {
            val pick = bag.draw(eligible, now)!!
            assertNotEquals("back-to-back repeat", previous, pick.id)
            previous = pick.id
            bag.markPlayed(pick, now)
            now += 1_000
        }
    }

    @Test
    fun `a single-headline pool must repeat rather than starve`() {
        val bag = HeadlineBag(Random(3))
        val eligible = pool(1)
        var now = 1_000L
        repeat(5) {
            val pick = bag.draw(eligible, now)!!
            assertEquals(eligible[0].id, pick.id)
            bag.markPlayed(pick, now)
            now += 1_000
        }
    }

    @Test
    fun `abandoned draws consume nothing`() {
        // A draw is provisional: only markPlayed commits. Drawing over and
        // over without confirming must keep offering unheard headlines and
        // never dip into the heard ones.
        val bag = HeadlineBag(Random(11))
        val eligible = pool(10)
        var now = 1_000L
        val heardIds = eligible.take(5).map { h ->
            bag.markPlayed(h, now)
            now += 1_000
            h.id
        }.toSet()
        repeat(100) {
            val pick = bag.draw(eligible, now)!!
            assertTrue("draw returned already-heard ${pick.id}", pick.id !in heardIds)
        }
    }

    @Test
    fun `forced replays come from the least recently heard end, never the freshest`() {
        val bag = HeadlineBag(Random(5))
        val eligible = pool(9)
        var now = 1_000L
        // Hear all nine in a known order: story 1 oldest, story 9 freshest.
        for (h in eligible) {
            bag.markPlayed(h, now)
            now += 60_000
        }
        val oldestThirdIds = eligible.take(3).map { it.id }.toSet()
        repeat(50) {
            val pick = bag.draw(eligible, now)!!
            assertTrue(
                "replay of ${pick.id} is not from the least-recently-heard third",
                pick.id in oldestThirdIds,
            )
        }
    }

    @Test
    fun `repeats are spaced by at least two thirds of the pool`() {
        val n = 12
        val bag = HeadlineBag(Random(23))
        val eligible = pool(n)
        var now = 1_000L
        val lastSeenAtDraw = mutableMapOf<String, Int>()
        var minGap = Int.MAX_VALUE
        repeat(600) { draw ->
            val pick = bag.draw(eligible, now)!!
            lastSeenAtDraw[pick.id]?.let { minGap = minOf(minGap, draw - it) }
            lastSeenAtDraw[pick.id] = draw
            bag.markPlayed(pick, now)
            now += 1_000
        }
        // With everything heard, a replay must climb back through the
        // least-recently-heard window: at least (pool - 1) minus the window
        // size other plays have to happen first.
        val candidates = n - 1
        val window = (candidates + 2) / 3
        val guaranteed = candidates - window
        assertTrue(
            "repeat gap $minGap below guaranteed spacing $guaranteed",
            minGap >= guaranteed,
        )
    }

    @Test
    fun `a heard story re-keyed under a new id is still recognized by title`() {
        // The class of bug that keeps coming back: feeds re-key a story
        // (revision counters in guids, CMS migrations), the id-keyed memory
        // misses it, and the user hears it again. The title is what the ear
        // remembers, so a title match must count as heard.
        val bag = HeadlineBag(Random(9))
        val original = Headline(
            id = "https://example.com/original-id#0",
            title = "Parliament passes the budget bill",
            sourceId = "feed",
            sourceName = "feed",
        )
        bag.markPlayed(original, now = 1_000L)
        val rekeyed = original.copy(id = "https://example.com/completely-new-id")
        val unheard = Headline(
            id = "https://example.com/other",
            title = "A different story entirely",
            sourceId = "feed",
            sourceName = "feed",
        )
        repeat(50) {
            val pick = bag.draw(listOf(rekeyed, unheard), now = 2_000L)!!
            assertEquals("re-keyed heard story drawn while unheard exists", unheard.id, pick.id)
        }
    }

    @Test
    fun `title matching survives punctuation and case changes`() {
        assertEquals(
            HeadlineBag.normalizeTitle("Parliament passes the budget bill"),
            HeadlineBag.normalizeTitle("  PARLIAMENT passes: the budget bill!  "),
        )
        assertNotEquals(
            HeadlineBag.normalizeTitle("Parliament passes the budget bill"),
            HeadlineBag.normalizeTitle("Parliament rejects the budget bill"),
        )
    }

    @Test
    fun `marks expire after the retention window`() {
        val bag = HeadlineBag(Random(2))
        val h = headline(1)
        bag.markPlayed(h, now = 1_000L)
        assertNotNull(bag.playedAt(h))
        bag.prune(now = 1_000L + HeadlineBag.RETENTION_MS + 1)
        assertNull("expired mark still present", bag.playedAt(h))
    }

    @Test
    fun `marks survive shy of the retention window`() {
        val bag = HeadlineBag(Random(2))
        val h = headline(1)
        bag.markPlayed(h, now = 1_000L)
        bag.prune(now = 1_000L + HeadlineBag.RETENTION_MS - 1)
        assertNotNull("mark dropped before retention elapsed", bag.playedAt(h))
    }

    @Test
    fun `mark count is capped, dropping oldest first`() {
        val bag = HeadlineBag(Random(2))
        val total = HeadlineBag.MAX_MARKS + 100
        for (i in 1..total) {
            bag.markPlayed(headline(i), now = i.toLong())
        }
        val state = bag.snapshot()
        assertTrue(state.playedById.size <= HeadlineBag.MAX_MARKS)
        assertTrue(state.playedByTitle.size <= HeadlineBag.MAX_MARKS)
        assertNull("oldest mark should be dropped", bag.playedAt(headline(1)))
        assertNotNull("newest mark should survive", bag.playedAt(headline(total)))
    }

    @Test
    fun `clear forgets every mark and the recency state`() {
        val bag = HeadlineBag(Random(6))
        val eligible = pool(5)
        var now = 1_000L
        for (h in eligible) {
            bag.markPlayed(h, now)
            now += 1_000
        }
        bag.clear()
        val state = bag.snapshot()
        assertTrue(state.playedById.isEmpty())
        assertTrue(state.playedByTitle.isEmpty())
        assertNull(state.lastId)
        assertNull(state.lastTitleKey)
        // Everything is unheard again: the next five draws cover the whole
        // pool with no repeats, exactly like a fresh install.
        val heard = mutableSetOf<String>()
        repeat(5) {
            val pick = bag.draw(eligible, now)!!
            assertTrue(heard.add(pick.id))
            bag.markPlayed(pick, now)
            now += 1_000
        }
    }

    @Test
    fun `snapshot and restore preserve the memory exactly`() {
        val bag = HeadlineBag(Random(4))
        val eligible = pool(6)
        var now = 1_000L
        for (h in eligible.take(4)) {
            bag.markPlayed(h, now)
            now += 1_000
        }
        val restored = HeadlineBag(Random(4))
        restored.restore(bag.snapshot(), now)
        for (h in eligible) {
            assertEquals("playedAt mismatch for ${h.id}", bag.playedAt(h), restored.playedAt(h))
        }
        // The restored bag must go on serving the unheard ones first.
        val unheardIds = eligible.drop(4).map { it.id }.toSet()
        repeat(50) {
            val pick = restored.draw(eligible, now)!!
            assertTrue(pick.id in unheardIds)
        }
    }
}
