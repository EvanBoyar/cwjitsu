package com.cwjitsu.app.data

import com.cwjitsu.app.practice.WordSet
import kotlin.random.Random

/**
 * Persistable snapshot of a [WordBag]: for each source, which of its words
 * have been heard in the current cycle. Plain maps only, so the
 * serialization format lives with the I/O code in [WordPool] and this file
 * stays free of Android and JSON dependencies (= plain-JUnit testable).
 *
 * The frequency-ranked pool is stored under [WordBag.FREQUENCY_KEY]; every
 * other key is a [WordSet] name.
 */
data class WordBagState(val played: Map<String, Set<String>>)

/**
 * Draw-without-replacement over one pool of words. Every word is dealt once
 * before any word is dealt twice.
 *
 * Deliberately simpler than [HeadlineBag], because the problem is simpler: a
 * word pool is static, local, and known up front, so there is no feed churn
 * to match through and no reason to rank replays by recency. What it does
 * share with [HeadlineBag] is the rule that earned that class its comments -
 * **a draw never mutates state**. Rounds are built one ahead of playback and
 * can be abandoned, so consuming a word at draw time would silently skip it.
 * Only [markPlayed] advances the cycle.
 */
class ShuffleBag(private val random: Random = Random.Default) {

    private var pool: List<String> = emptyList()
    private var poolSet: Set<String> = emptySet()
    private val played = HashSet<String>()

    val size: Int get() = pool.size

    /**
     * Swap in a new pool, keeping cycle progress for the words that survive.
     * Pulling the vocabulary slider down shouldn't restart the cycle for the
     * words that were already in range.
     */
    fun setPool(items: List<String>) {
        pool = items
        poolSet = items.toSet()
        played.retainAll(poolSet)
    }

    /**
     * A candidate word, avoiding anything in [exclude] when possible.
     * [exclude] is a near-term "recently heard" guard, not a filter: if every
     * remaining word is excluded, one is returned anyway rather than
     * reporting the pool empty. Returns null only when the pool itself is
     * empty. Does not mutate state.
     */
    fun draw(exclude: Set<String>): String? {
        if (pool.isEmpty()) return null
        // Cycle exhausted: fall back to the whole pool. The reset itself
        // happens in markPlayed, not here, so an abandoned round cannot
        // restart the cycle.
        val remaining = pool.filter { it !in played }.ifEmpty { pool }
        val fresh = remaining.filter { it !in exclude }
        return (if (fresh.isNotEmpty()) fresh else remaining).random(random)
    }

    /** Confirm [word] actually played, consuming it from the current cycle. */
    fun markPlayed(word: String) {
        if (word !in poolSet) return
        played.add(word)
        if (played.size >= pool.size) {
            // Cycle complete. Begin the next one, but keep the word just
            // heard marked, so a full traversal of the pool still separates
            // it from its own repeat across the cycle boundary.
            played.clear()
            played.add(word)
        }
    }

    fun snapshot(): Set<String> = HashSet(played)

    fun restore(words: Collection<String>) {
        played.clear()
        played.addAll(words.filter { it in poolSet })
    }
}

/**
 * The Words category's draw policy: one [ShuffleBag] over the top-N
 * frequency-ranked words, plus one per enabled [WordSet], and the weighting
 * that decides which of them a given round comes from.
 *
 * Two decisions worth knowing about:
 *
 *  1. The enrichment sets are drawn as their own CATEGORY, not merged into
 *     one pool. Merging 250 amateur-radio terms into 5,000 common words
 *     would surface a radio term about one round in twenty, which defeats
 *     the point of the toggle. Instead [enrichmentPercent] of rounds come
 *     from the enrichment side, split evenly among whichever sets are on, so
 *     a toggle has the same visible effect at any vocabulary setting.
 *
 *  2. The pools deliberately OVERLAP. RADIO is both a common English word
 *     and an amateur-radio term, and it is left in both, so someone
 *     practicing the plain frequency list still meets it. The only
 *     concession is [RECENT_MEMORY]: a word heard in the last few rounds is
 *     avoided when another is available, which stops the two pools from
 *     handing back the same word twice in a row. That is a re-roll, never a
 *     filter - no word is ever made unreachable.
 */
class WordBag(private val random: Random = Random.Default) {

    companion object {
        /** Key for the frequency-ranked pool in [WordBagState.played]. */
        const val FREQUENCY_KEY = "FREQUENCY"

        /**
         * How many recently heard words the draw tries to avoid. Small on
         * purpose: its only jobs are cross-pool collisions and cycle
         * boundaries, and a large value would meaningfully constrain the
         * 100-word setting.
         */
        const val RECENT_MEMORY = 6
    }

    private val frequency = ShuffleBag(random)
    private val enrichment = LinkedHashMap<WordSet, ShuffleBag>()
    private val recent = ArrayDeque<String>()

    private var enabledSets: List<WordSet> = emptyList()
    private var enrichmentPercent: Int = 0

    /**
     * Point the bags at the current pools. [frequencyWords] is already
     * trimmed to the user's vocabulary setting; [setWords] holds every
     * loaded set, of which only [enabled] participate in the draw.
     * Idempotent, so the caller can hand it the same pools every round.
     */
    fun configure(
        frequencyWords: List<String>,
        setWords: Map<WordSet, List<String>>,
        enabled: Set<WordSet>,
        percent: Int,
    ) {
        frequency.setPool(frequencyWords)
        for ((set, words) in setWords) {
            enrichment.getOrPut(set) { ShuffleBag(random) }.setPool(words)
        }
        // Sorted so the draw is reproducible for a given seed regardless of
        // the order the user toggled the chips.
        enabledSets = enabled.sortedBy { it.ordinal }
        enrichmentPercent = percent.coerceIn(0, 100)
    }

    /**
     * The next word to practice, or null when nothing is loaded. Does not
     * mutate the cycle - see [markPlayed].
     */
    fun next(): String? = pickBag()?.draw(recent.toSet())

    /**
     * Confirm [word] actually played. Marked in EVERY pool that holds it, so
     * a word living in both the frequency list and an enrichment set is
     * heard once per cycle rather than once per pool. This consumes, it does
     * not exclude: the word is a candidate again in both pools as soon as
     * their cycles come round.
     */
    fun markPlayed(word: String) {
        frequency.markPlayed(word)
        for (bag in enrichment.values) bag.markPlayed(word)
        recent.addLast(word)
        while (recent.size > RECENT_MEMORY) recent.removeFirst()
    }

    private fun pickBag(): ShuffleBag? {
        val live = enabledSets.mapNotNull { set -> enrichment[set]?.takeIf { it.size > 0 } }
        if (live.isEmpty() || enrichmentPercent <= 0) {
            return frequency.takeIf { it.size > 0 }
        }
        // With no frequency words loaded (asset missing), enrichment carries
        // the whole category rather than the draw going silent.
        if (frequency.size == 0) return live.random(random)
        return if (random.nextInt(100) < enrichmentPercent) live.random(random) else frequency
    }

    fun snapshot(): WordBagState {
        val played = LinkedHashMap<String, Set<String>>()
        played[FREQUENCY_KEY] = frequency.snapshot()
        for ((set, bag) in enrichment) played[set.name] = bag.snapshot()
        return WordBagState(played)
    }

    /** Restore cycle progress. Call AFTER [configure] so the pools exist. */
    fun restore(state: WordBagState) {
        frequency.restore(state.played[FREQUENCY_KEY].orEmpty())
        for ((set, bag) in enrichment) bag.restore(state.played[set.name].orEmpty())
    }
}
