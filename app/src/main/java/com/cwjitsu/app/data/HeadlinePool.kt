package com.cwjitsu.app.data

import com.cwjitsu.app.practice.Headline
import com.cwjitsu.app.practice.NewsSource

/**
 * Pure merge policy for the cached headline pool. No Android, no I/O, no
 * clock reads - the caller supplies `now` - so the rules that decide what is
 * playable can be unit tested directly.
 *
 * The central rule: a refreshed feed's fresh items come first, but headlines
 * that ROTATED OUT of the feed are kept (newest first, up to the per-feed
 * cap) until they age out. Wholesale replacement - the old behavior - capped
 * the playable pool at however many items the feed serves in one response,
 * and the AP mirror serves TEN. A user practicing a single small source
 * exhausted the pool in minutes, forcing replays while genuinely fresh
 * headlines fetched half an hour earlier had already been thrown away.
 */
object HeadlinePool {

    /** Cap per feed: fresh items first, then the newest kept leftovers. */
    const val PER_FEED_LIMIT = 40

    /** Global cap on the whole pool. */
    const val TOTAL_LIMIT = 500

    /**
     * How long a headline stays in the pool after it was last seen in a
     * successful fetch of its feed. Long enough to deepen small feeds'
     * pools, short enough that "news" stays news.
     */
    const val KEEP_ROTATED_OUT_MS: Long = 3L * 24 * 60 * 60 * 1000

    /**
     * Fold one refresh into the pool.
     *
     * - A feed that fetched successfully contributes its fresh items
     *   (stamped [now]), then its previously cached items that were NOT in
     *   this fetch, newest first, dropped once they are older than
     *   [KEEP_ROTATED_OUT_MS], all capped at [PER_FEED_LIMIT].
     * - A feed that failed (offline, HTTP error) keeps its cached items
     *   untouched - the offline-first promise. No age pruning either: three
     *   stale days underground still beats silence.
     * - Feeds no longer configured at all (in [current] but not
     *   [knownFeedIds]) are purged.
     * - Ids are unique across the result; fresh entries win.
     */
    fun merge(
        current: List<Headline>,
        results: List<Pair<NewsSource, List<Headline>>>,
        knownFeedIds: Set<String>,
        now: Long,
    ): List<Headline> {
        val refreshedIds = results
            .filter { it.second.isNotEmpty() }
            .mapTo(HashSet()) { it.first.id }
        val currentByFeed = current
            .filter { it.sourceId in knownFeedIds }
            .groupBy { it.sourceId }

        val merged = ArrayList<Headline>(current.size)
        val seenIds = HashSet<String>()

        // Refreshed feeds first, in result order: fresh items, then the
        // newest still-young leftovers up to the cap.
        for ((feed, fresh) in results) {
            if (feed.id !in refreshedIds) continue
            val stamped = fresh.map { it.copy(fetchedAt = now) }
            val kept = (currentByFeed[feed.id] ?: emptyList())
                .filter { now - it.fetchedAt <= KEEP_ROTATED_OUT_MS }
                .sortedByDescending { it.fetchedAt }
            var added = 0
            for (h in stamped + kept) {
                if (added >= PER_FEED_LIMIT) break
                if (seenIds.add(h.id)) {
                    merged.add(h)
                    added++
                }
            }
        }

        // Feeds that failed this round (or weren't part of it) keep their
        // cached items as-is.
        for ((feedId, items) in currentByFeed) {
            if (feedId in refreshedIds) continue
            for (h in items) {
                if (seenIds.add(h.id)) merged.add(h)
            }
        }

        return if (merged.size <= TOTAL_LIMIT) merged else merged.take(TOTAL_LIMIT)
    }
}
