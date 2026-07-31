package com.cwjitsu.app.data

import com.cwjitsu.app.practice.Headline
import com.cwjitsu.app.practice.NewsSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HeadlinePoolTest {

    private val feedA = NewsSource("a", "Feed A", "https://a.example/rss", true)
    private val feedB = NewsSource("b", "Feed B", "https://b.example/rss", true)

    private fun headline(feed: NewsSource, n: Int, fetchedAt: Long = 0L) = Headline(
        id = "https://${feed.id}.example/story-$n",
        title = "${feed.name} story $n",
        sourceId = feed.id,
        sourceName = feed.name,
        fetchedAt = fetchedAt,
    )

    @Test
    fun `fresh items are stamped with the merge time`() {
        val now = 50_000L
        val merged = HeadlinePool.merge(
            current = emptyList(),
            results = listOf(feedA to listOf(headline(feedA, 1), headline(feedA, 2))),
            knownFeedIds = setOf(feedA.id),
            now = now,
        )
        assertEquals(2, merged.size)
        assertTrue(merged.all { it.fetchedAt == now })
    }

    @Test
    fun `headlines that rotated out of the feed are kept until they age out`() {
        val t0 = 1_000L
        val old = headline(feedA, 1, fetchedAt = t0)
        val freshOnly = listOf(headline(feedA, 2))

        // Well within the keep window: the rotated-out story stays playable.
        val young = HeadlinePool.merge(
            current = listOf(old),
            results = listOf(feedA to freshOnly),
            knownFeedIds = setOf(feedA.id),
            now = t0 + HeadlinePool.KEEP_ROTATED_OUT_MS - 1,
        )
        assertEquals(2, young.size)
        assertTrue(young.any { it.id == old.id && it.fetchedAt == t0 })

        // Past the window: it ages out.
        val aged = HeadlinePool.merge(
            current = listOf(old),
            results = listOf(feedA to freshOnly),
            knownFeedIds = setOf(feedA.id),
            now = t0 + HeadlinePool.KEEP_ROTATED_OUT_MS + 1,
        )
        assertEquals(1, aged.size)
        assertTrue(aged.none { it.id == old.id })
    }

    @Test
    fun `per-feed cap prefers fresh items then the newest leftovers`() {
        val now = 1_000_000L
        // 50 previously cached stories with increasing fetchedAt (story 50
        // newest), plus 10 fresh ones.
        val current = (1..50).map { headline(feedA, it, fetchedAt = now - 100_000 + it * 100L) }
        val fresh = (51..60).map { headline(feedA, it) }
        val merged = HeadlinePool.merge(
            current = current,
            results = listOf(feedA to fresh),
            knownFeedIds = setOf(feedA.id),
            now = now,
        )
        assertEquals(HeadlinePool.PER_FEED_LIMIT, merged.size)
        // All 10 fresh present.
        assertTrue(fresh.all { f -> merged.any { it.id == f.id } })
        // Remaining 30 slots hold the NEWEST leftovers (stories 21..50).
        val keptIds = merged.filter { it.fetchedAt != now }.map { it.id }
        assertEquals(30, keptIds.size)
        assertTrue((21..50).all { n -> keptIds.contains(headline(feedA, n).id) })
    }

    @Test
    fun `a failed feed keeps its cached headlines regardless of age`() {
        val ancient = headline(feedA, 1, fetchedAt = 1L)
        val merged = HeadlinePool.merge(
            current = listOf(ancient),
            results = listOf(
                feedA to emptyList(), // fetch failed
                feedB to listOf(headline(feedB, 1)),
            ),
            knownFeedIds = setOf(feedA.id, feedB.id),
            now = 1_000_000_000L,
        )
        assertTrue("offline-first: failed feed lost its cache", merged.any { it.id == ancient.id })
        assertEquals(2, merged.size)
    }

    @Test
    fun `headlines from removed feeds are purged`() {
        val merged = HeadlinePool.merge(
            current = listOf(headline(feedA, 1, fetchedAt = 1_000L)),
            results = listOf(feedB to listOf(headline(feedB, 1))),
            knownFeedIds = setOf(feedB.id), // feed A no longer configured
            now = 2_000L,
        )
        assertEquals(1, merged.size)
        assertEquals(feedB.id, merged[0].sourceId)
    }

    @Test
    fun `a story still in the feed is not duplicated and its stamp is refreshed`() {
        val t0 = 1_000L
        val now = 2_000L
        val story = headline(feedA, 1, fetchedAt = t0)
        val merged = HeadlinePool.merge(
            current = listOf(story),
            results = listOf(feedA to listOf(story.copy(fetchedAt = 0L))),
            knownFeedIds = setOf(feedA.id),
            now = now,
        )
        assertEquals(1, merged.size)
        assertEquals(now, merged[0].fetchedAt)
    }

    @Test
    fun `total pool size is capped`() {
        val feeds = (1..20).map { NewsSource("f$it", "F$it", "https://f$it.example", true) }
        val results = feeds.map { f -> f to (1..HeadlinePool.PER_FEED_LIMIT).map { headline(f, it) } }
        val merged = HeadlinePool.merge(
            current = emptyList(),
            results = results,
            knownFeedIds = feeds.mapTo(HashSet()) { it.id },
            now = 1_000L,
        )
        assertEquals(HeadlinePool.TOTAL_LIMIT, merged.size)
    }
}
