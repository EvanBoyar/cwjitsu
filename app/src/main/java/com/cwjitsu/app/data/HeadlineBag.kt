package com.cwjitsu.app.data

import com.cwjitsu.app.practice.Headline
import kotlin.random.Random

/**
 * Persistable snapshot of a [HeadlineBag]. Plain maps/values only, so the
 * serialization format lives with the I/O code in [NewsRepository] and this
 * class stays free of Android and JSON dependencies (= plain-JUnit testable).
 */
data class HeadlineBagState(
    val playedById: Map<String, Long>,
    val playedByTitle: Map<String, Long>,
    val lastId: String?,
    val lastTitleKey: String?,
)

/**
 * The practice engine's memory of which headlines have been heard, and the
 * policy for choosing the next one. Pure in-memory logic - no Android, no
 * I/O, no clock reads (the caller supplies `now`) - extracted from
 * [NewsRepository] after several rounds of replay bugs proved this logic
 * needs direct unit coverage.
 *
 * Design decisions, each one the product of a replay bug:
 *
 *  1. Played marks are TIMESTAMPED and retained for [RETENTION_MS], NOT
 *     pruned the moment a headline leaves the cached pool. Small feeds (the
 *     AP mirror serves 10 items, rotating every half hour) used to shed
 *     played stories from the pool within hours, and pruning the mark with
 *     the story meant any story that re-entered the feed - aggregator flap,
 *     an edit bumping it back to the top - played again as brand new.
 *
 *  2. Every headline is remembered under BOTH its feed identity (canonical
 *     guid/link) and its normalized TITLE. Feed ids keep finding new ways to
 *     churn (BBC appended revision counters to its guids; other feeds
 *     re-key stories on edit), and each churn variant used to defeat the
 *     id-keyed bag. The title is what the user actually hears, so a match on
 *     either counts as "already heard".
 *
 *  3. When every eligible headline has been heard, the draw picks among the
 *     LEAST RECENTLY heard third rather than wiping the played set and
 *     reshuffling. The old wipe was destructive at draw time - an abandoned
 *     round still reset the whole cycle - and a reshuffle allows a headline
 *     heard moments ago to come straight back. Recency ordering guarantees a
 *     repeat can only occur after most of the pool has played in between.
 *
 *  4. A draw is still only provisional: nothing here changes state until
 *     [markPlayed] confirms the headline actually reached the speaker.
 */
class HeadlineBag(private val random: Random = Random.Default) {

    companion object {
        /** How long a played mark outlives the headline's presence in any feed. */
        const val RETENTION_MS: Long = 14L * 24 * 60 * 60 * 1000

        /** Hard cap on stored marks; oldest are dropped past this. */
        const val MAX_MARKS: Int = 2000

        /**
         * Collapse a title to the form the user's ear would recognize:
         * lowercase, alphanumerics only, single spaces. Two stories that
         * normalize identically are treated as the same headline.
         */
        fun normalizeTitle(title: String): String =
            title.lowercase().replace(NON_ALNUM, " ").trim()

        private val NON_ALNUM = Regex("[^a-z0-9]+")
    }

    private val playedById = HashMap<String, Long>()
    private val playedByTitle = HashMap<String, Long>()
    private var lastId: String? = null
    private var lastTitleKey: String? = null

    /**
     * Choose the next headline from [eligible]. Unheard headlines (no id OR
     * title match) are drawn first, at random. Once everything eligible has
     * been heard, the draw comes from the least-recently-heard third of the
     * pool, never the headline heard most recently (unless it is the only
     * one). Returns null when [eligible] is empty. Does not mutate state.
     */
    fun draw(eligible: List<Headline>, now: Long): Headline? {
        if (eligible.isEmpty()) return null
        val unplayed = eligible.filter { playedAt(it) == null }
        if (unplayed.isNotEmpty()) return unplayed.random(random)

        // Everything has been heard: replay, but from the stalest end, and
        // never straight back into the most recent one.
        val candidates = eligible
            .filter { it.id != lastId && normalizeTitle(it.title) != lastTitleKey }
            .ifEmpty { eligible }
        val window = (candidates.size + 2) / 3
        val oldest = candidates.sortedBy { playedAt(it) ?: 0L }.take(window)
        return oldest.random(random)
    }

    /**
     * Confirm that [headline] actually played at [now]. Idempotent in effect:
     * re-marking (Previous, Restart) just refreshes the recency stamp, which
     * is exactly what recency-ordered replay wants.
     */
    fun markPlayed(headline: Headline, now: Long) {
        markPlayed(headline.id, normalizeTitle(headline.title), now)
    }

    /**
     * [markPlayed] for callers that only have the id (the pool no longer
     * holds the headline, so its title is unknown). [titleKey] may be null.
     */
    fun markPlayed(id: String, titleKey: String?, now: Long) {
        playedById[id] = now
        if (!titleKey.isNullOrBlank()) playedByTitle[titleKey] = now
        lastId = id
        lastTitleKey = titleKey
        prune(now)
    }

    /** When [headline] was last heard, matching by id or title, else null. */
    fun playedAt(headline: Headline): Long? {
        val byId = playedById[headline.id]
        val byTitle = playedByTitle[normalizeTitle(headline.title)]
        return when {
            byId == null -> byTitle
            byTitle == null -> byId
            else -> maxOf(byId, byTitle)
        }
    }

    /**
     * Drop marks past [RETENTION_MS] and enforce [MAX_MARKS] (oldest first).
     * Called from [markPlayed] and after restores; safe to call any time.
     */
    fun prune(now: Long) {
        val cutoff = now - RETENTION_MS
        playedById.entries.removeAll { it.value < cutoff }
        playedByTitle.entries.removeAll { it.value < cutoff }
        trimToCap(playedById)
        trimToCap(playedByTitle)
    }

    private fun trimToCap(marks: HashMap<String, Long>) {
        val excess = marks.size - MAX_MARKS
        if (excess <= 0) return
        marks.entries.sortedBy { it.value }
            .take(excess)
            .forEach { marks.remove(it.key) }
    }

    /** Forget everything: all played marks and the recency state. */
    fun clear() {
        playedById.clear()
        playedByTitle.clear()
        lastId = null
        lastTitleKey = null
    }

    fun snapshot(): HeadlineBagState = HeadlineBagState(
        playedById = HashMap(playedById),
        playedByTitle = HashMap(playedByTitle),
        lastId = lastId,
        lastTitleKey = lastTitleKey,
    )

    fun restore(state: HeadlineBagState, now: Long) {
        playedById.clear(); playedById.putAll(state.playedById)
        playedByTitle.clear(); playedByTitle.putAll(state.playedByTitle)
        lastId = state.lastId
        lastTitleKey = state.lastTitleKey
        prune(now)
    }
}
