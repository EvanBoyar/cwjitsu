package com.cwjitsu.app.data

import com.cwjitsu.app.practice.Headline
import com.cwjitsu.app.practice.NewsSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * End-to-end simulation of the exact conditions under which headlines kept
 * replaying in the field: ONE enabled source that serves only 10 items at a
 * time and rotates a handful of new stories every half hour (the AP mirror),
 * a user practicing continuously, refreshes folding in mid-session, and app
 * restarts persisting the bag through snapshot/restore.
 *
 * The invariant under test, everywhere: a headline is never heard twice
 * while ANY cached playable headline remains unheard.
 */
class NewsRepeatScenarioTest {

    private val ap = NewsSource("ap", "AP", "https://ap.example/rss", true)

    private fun story(n: Int) = Headline(
        id = "https://apnews.example/article/story-$n",
        title = "AP wire story number $n about something newsworthy",
        sourceId = ap.id,
        sourceName = ap.name,
    )

    /** The feed's current 10-item window: the newest 10 of stories 1..[latest]. */
    private fun feedWindow(latest: Int): List<Headline> =
        ((latest - 9).coerceAtLeast(1)..latest).map { story(it) }.reversed()

    @Test
    fun `a day of practice on one small rotating feed never repeats while unheard headlines exist`() {
        val bag = HeadlineBag(Random(1234))
        var pool = emptyList<Headline>()
        var latest = 10
        var now = 0L

        val heardAtLeastOnce = mutableSetOf<String>()
        var replayCount = 0

        fun refresh() {
            pool = HeadlinePool.merge(pool, listOf(ap to feedWindow(latest)), setOf(ap.id), now)
        }

        refresh()
        // 16 "half hours" (a full practice day): each tick the feed gains 5
        // new stories, then the user hears 30 headlines a minute apart.
        repeat(16) {
            latest += 5
            refresh()
            repeat(30) {
                now += 60_000
                val pick = bag.draw(pool, now) ?: error("pool unexpectedly empty")
                val unheardRemaining = pool.count { bag.playedAt(it) == null }
                if (pick.id in heardAtLeastOnce) {
                    replayCount++
                    // The one thing the user must never experience: a replay
                    // while something unheard was sitting in the pool.
                    assertEquals(
                        "replayed ${pick.id} while $unheardRemaining unheard headlines were available",
                        0, unheardRemaining,
                    )
                }
                heardAtLeastOnce.add(pick.id)
                bag.markPlayed(pick, now)
            }
        }

        // Accumulation must have deepened the pool well past the feed's
        // 10-item window, up to the per-feed cap.
        assertEquals(HeadlinePool.PER_FEED_LIMIT, pool.size)
        // The day produced 16 * 5 + 10 = 90 distinct stories for 480 plays,
        // so replays are expected (the invariant above checked each one was
        // legitimate) - and the pool kept absorbing new stories, so nearly
        // every distinct story should have been heard at least once.
        assertTrue("480 plays of 90 stories must include replays", replayCount > 0)
        assertTrue("expected the user to hear most distinct stories", heardAtLeastOnce.size >= 85)
    }

    @Test
    fun `an app restart mid-session forgets nothing`() {
        var bag = HeadlineBag(Random(99))
        var pool = emptyList<Headline>()
        var now = 0L
        pool = HeadlinePool.merge(pool, listOf(ap to feedWindow(10)), setOf(ap.id), now)

        val heard = mutableSetOf<String>()
        repeat(6) {
            now += 60_000
            val pick = bag.draw(pool, now)!!
            assertTrue(heard.add(pick.id))
            bag.markPlayed(pick, now)
        }

        // Process death and relaunch: bag state round-trips through the
        // snapshot, the pool through the (already tested) cache.
        bag = HeadlineBag(Random(100)).also { it.restore(bag.snapshot(), now) }

        // The remaining 4 unheard stories must all play before any repeat.
        repeat(4) {
            now += 60_000
            val pick = bag.draw(pool, now)!!
            assertTrue("restart replayed ${pick.id}", heard.add(pick.id))
            bag.markPlayed(pick, now)
        }
        assertEquals(10, heard.size)
    }

    @Test
    fun `a heard story leaving and re-entering the feed does not replay as new`() {
        // The pruning bug: played marks used to die the moment a story left
        // the pool, so a story that rotated out and came back played again.
        // Marks now outlive pool membership by the retention window.
        val bag = HeadlineBag(Random(55))
        var pool = emptyList<Headline>()
        var now = 0L
        pool = HeadlinePool.merge(pool, listOf(ap to listOf(story(1), story(2))), setOf(ap.id), now)

        // Hear story 1.
        now += 60_000
        bag.markPlayed(story(1), now)

        // Story 1 leaves the feed AND ages out of the pool entirely.
        now += HeadlinePool.KEEP_ROTATED_OUT_MS + 60_000
        pool = HeadlinePool.merge(pool, listOf(ap to listOf(story(3))), setOf(ap.id), now)
        assertTrue(pool.none { it.id == story(1).id })

        // It re-enters the feed alongside an unheard story.
        now += 60_000
        pool = HeadlinePool.merge(pool, listOf(ap to listOf(story(1), story(4))), setOf(ap.id), now)

        // Unheard stories 3 and 4 are in the pool, so the heard story must
        // never surface ahead of them.
        val heardId = story(1).id
        repeat(50) {
            val pick = bag.draw(pool, now)!!
            assertTrue("re-entered heard story replayed as new", pick.id != heardId)
        }
    }

    @Test
    fun `switching sources on mid-session does not replay the already-heard ones`() {
        // The pool caches every feed; playback filters by the enabled set.
        // Toggling a second source on must not disturb the first source's
        // played memory.
        val bbc = NewsSource("bbc", "BBC", "https://bbc.example/rss", true)
        fun bbcStory(n: Int) = Headline(
            id = "https://bbc.example/news/story-$n",
            title = "BBC bulletin item number $n of the evening",
            sourceId = bbc.id,
            sourceName = bbc.name,
        )
        val bag = HeadlineBag(Random(77))
        var now = 0L
        val pool = HeadlinePool.merge(
            emptyList(),
            listOf(ap to feedWindow(10), bbc to (1..10).map { bbcStory(it) }),
            setOf(ap.id, bbc.id),
            now,
        )

        // Practice with only AP enabled until all 10 AP stories are heard.
        val apOnly = pool.filter { it.sourceId == ap.id }
        val apHeard = mutableSetOf<String>()
        repeat(10) {
            now += 60_000
            val pick = bag.draw(apOnly, now)!!
            assertTrue(apHeard.add(pick.id))
            bag.markPlayed(pick, now)
        }

        // Enable BBC too: the next 10 draws must all be unheard BBC stories,
        // never an AP replay.
        val both = pool.filter { it.sourceId == ap.id || it.sourceId == bbc.id }
        repeat(10) {
            now += 60_000
            val pick = bag.draw(both, now)!!
            assertEquals("AP story replayed while BBC unheard", bbc.id, pick.sourceId)
            bag.markPlayed(pick, now)
        }
    }
}
