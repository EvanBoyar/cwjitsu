package com.cwjitsu.app.data

import android.content.Context
import android.util.Log
import com.cwjitsu.app.practice.CallsignRegistry
import com.cwjitsu.app.practice.MixedConfig
import com.cwjitsu.app.practice.WordSet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File

/**
 * The Words category's vocabulary: the bundled word-list assets, the
 * [WordBag] that deals from them without replacement, and the on-disk
 * memory of how far through each cycle the user has got.
 *
 * Cycle progress is persisted for the same reason the news bag is: at the
 * widest vocabulary setting a full cycle is 5,000 words, far more than any
 * one session, so an in-memory-only bag would reshuffle long before it ever
 * emptied and the no-repeat guarantee would never actually hold.
 *
 * What is stored is set MEMBERSHIP (which words have been heard), not
 * shuffle order. That survives a change of pool - narrowing the vocabulary
 * just intersects the stored set with the smaller pool - and it doesn't
 * depend on kotlin.random producing byte-identical shuffles across releases.
 */
class WordPool(private val context: Context) {

    companion object {
        private const val TAG = "CWJitsu/Words"

        /** Cycle progress only; small and rewritten often, so it gets its own file. */
        private const val BAG_FILE = "word_bag.json"

        /**
         * Plays are coalesced for this long before hitting the disk. The
         * news bag writes on every play because losing one headline means
         * replaying a story the user already heard; losing one word costs a
         * single duplicate somewhere inside a 5,000-word cycle, so the
         * writes are worth batching.
         */
        private const val PERSIST_DEBOUNCE_MS = 5_000L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writeMutex = Mutex()

    /**
     * Guards the bag, the loaded word lists, and the applied-config markers.
     * The practice loop calls [next] every round and the UI reads the counts
     * off the main thread.
     */
    private val lock = Any()

    private val bag = WordBag()
    private var frequencyWords: List<String> = emptyList()
    private var setWords: Map<WordSet, List<String>> = emptyMap()
    private var assetsLoaded = false

    // Restore is deferred: the bag can only take a snapshot back once its
    // pools exist, and the pools only exist after the first configure().
    private var pendingState: WordBagState? = null
    private var restored = false
    private var everPlayed = false

    // What the bag is currently configured for, so the per-round configure()
    // is a couple of comparisons rather than a pool rebuild.
    private var appliedPoolSize = -1
    private var appliedSets: Set<WordSet> = emptySet()
    private var appliedPercent = -1

    private var persistJob: Job? = null

    init {
        scope.launch {
            ensureLoaded()
            loadBagFromDisk()
        }
    }

    /**
     * Parse the bundled assets. Idempotent and safe from any thread; the
     * constructor kicks it off in the background, and any caller that gets
     * there first simply pays for the parse.
     */
    fun ensureLoaded() {
        synchronized(lock) {
            if (assetsLoaded) return
            frequencyWords = readAsset(WordSet.FREQUENCY_ASSET)
            setWords = WordSet.entries.associateWith { set ->
                // The prefix set has no asset: it is derived from the
                // registry so it cannot drift from the callsigns the
                // Callsigns category actually generates.
                set.asset?.let(::readAsset) ?: CallsignRegistry.allPrefixes
            }
            assetsLoaded = true
        }
    }

    private fun readAsset(name: String): List<String> = runCatching {
        context.assets.open(name)
            .bufferedReader()
            .use(BufferedReader::readLines)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }.onFailure { Log.w(TAG, "Could not read asset $name", it) }
        .getOrDefault(emptyList())

    /**
     * Point the bag at the pools [config] asks for. Cheap to call every
     * round: unchanged settings do nothing.
     */
    fun configure(config: MixedConfig) {
        ensureLoaded()
        synchronized(lock) {
            val size = MixedConfig.nearestWordPoolSize(config.wordPoolSize)
            val percent = MixedConfig.nearestWordEnrichmentPercent(config.wordEnrichmentPercent)
            val sets = config.wordSets
            if (size == appliedPoolSize && sets == appliedSets && percent == appliedPercent) return
            bag.configure(
                frequencyWords = frequencyWords.take(size),
                setWords = setWords,
                enabled = sets,
                percent = percent,
            )
            appliedPoolSize = size
            appliedSets = sets
            appliedPercent = percent
            applyPendingLocked()
        }
    }

    /**
     * The next word to practice, or null when nothing is loaded. Provisional
     * until [markPlayed] confirms it - see [com.cwjitsu.app.practice.Draw].
     */
    fun next(): String? = synchronized(lock) { bag.next() }

    /** Confirm [word] actually reached the speaker. */
    fun markPlayed(word: String) {
        synchronized(lock) {
            bag.markPlayed(word)
            everPlayed = true
        }
        schedulePersist()
    }

    /** How many words [set] contributes. */
    fun countIn(set: WordSet): Int = synchronized(lock) { setWords[set]?.size ?: 0 }

    /**
     * The full contents of [set], for the UI's "view list" sheet. Nothing
     * about what is in the practice pool should require guessing.
     */
    fun wordsIn(set: WordSet): List<String> = synchronized(lock) { setWords[set].orEmpty() }

    /** The top [limit] frequency-ranked words, in rank order. */
    fun frequencyWords(limit: Int): List<String> =
        synchronized(lock) { frequencyWords.take(limit) }

    /**
     * Write any pending progress now. Called when the session stops, so the
     * debounce window can't lose the tail of a session to a process death.
     */
    fun flush() {
        persistJob?.cancel()
        scope.launch { persist() }
    }

    // ---- Persistence -------------------------------------------------------

    private fun bagFile(): File = File(context.filesDir, BAG_FILE)

    private fun schedulePersist() {
        persistJob?.cancel()
        persistJob = scope.launch {
            delay(PERSIST_DEBOUNCE_MS)
            persist()
        }
    }

    /** Apply a restored snapshot once the pools exist. Caller holds [lock]. */
    private fun applyPendingLocked() {
        val state = pendingState ?: return
        // In-session progress always wins: a restore landing after practice
        // has already started would throw away plays that just happened.
        if (restored || everPlayed) return
        bag.restore(state)
        restored = true
    }

    private suspend fun persist() = withContext(Dispatchers.IO) {
        // Held across snapshot and write so "last write wins" also means
        // "newest state wins", matching NewsRepository's bag writer.
        writeMutex.withLock {
            val state = synchronized(lock) { bag.snapshot() }
            runCatching {
                val o = JSONObject()
                for ((key, words) in state.played) {
                    val arr = JSONArray()
                    for (w in words.sorted()) arr.put(w)
                    o.put(key, arr)
                }
                writeAtomically(bagFile(), o.toString())
            }.onFailure { Log.w(TAG, "Could not persist word bag", it) }
        }
    }

    /**
     * Replace [file] atomically: write a sibling temp, then rename over the
     * target, so a process death mid-write leaves an orphaned temp rather
     * than a half-written file the loader can only discard. Mirrors
     * [NewsRepository]'s writer, including its fallbacks.
     */
    private fun writeAtomically(file: File, text: String) {
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(file)) {
            if (!(file.delete() && tmp.renameTo(file))) {
                file.writeText(text)
                tmp.delete()
            }
        }
    }

    private suspend fun loadBagFromDisk() = withContext(Dispatchers.IO) {
        val file = bagFile()
        if (!file.exists()) return@withContext
        runCatching {
            val o = JSONObject(file.readText())
            val played = LinkedHashMap<String, Set<String>>()
            for (key in o.keys()) {
                val arr = o.optJSONArray(key) ?: continue
                played[key] = (0 until arr.length())
                    .mapNotNull { i -> arr.optString(i).takeIf { it.isNotEmpty() } }
                    .toSet()
            }
            synchronized(lock) {
                pendingState = WordBagState(played)
                // The pools may already be configured (a session can start
                // before this read finishes), in which case apply now.
                if (appliedPoolSize >= 0) applyPendingLocked()
            }
        }.onFailure { Log.w(TAG, "Could not read word bag; starting a fresh cycle", it) }
    }
}
