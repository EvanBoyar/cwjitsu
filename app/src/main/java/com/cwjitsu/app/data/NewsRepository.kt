package com.cwjitsu.app.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import android.util.Xml
import com.cwjitsu.app.practice.Headline
import com.cwjitsu.app.practice.NewsSource
import com.cwjitsu.app.practice.NewsSources
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Snapshot of the news cache/refresh state for the UI. */
data class NewsStatus(
    val headlineCount: Int = 0,
    val updatedAtMillis: Long? = null,
    val refreshing: Boolean = false,
    val message: String? = null,
)

/**
 * Owns the news headlines used by the practice engine. Design goals, in order:
 *
 *  1. Offline-first. The app is used underground a lot, so practice NEVER
 *     waits on the network: [nextHeadline] serves from an in-memory pool that
 *     is loaded from a local JSON cache on startup. Network fetches only
 *     refresh that cache in the background, and fail fast when offline.
 *
 *  2. No repeats. Random selection replays the same few headlines constantly,
 *     which is maddening. Every headline plays once before any replay, and
 *     unavoidable replays (small pools) come from the least-recently-heard
 *     end. The policy and its persistence-worthy state live in the pure,
 *     unit-tested [HeadlineBag]; this class just owns the I/O around it.
 *
 *  3. Multi-source. Built-in feeds plus user URLs, parsed as RSS or Atom.
 *     Small feeds serve only ~10 items at a time, so the pool ACCUMULATES
 *     recently rotated-out headlines per feed (see [HeadlinePool.merge])
 *     instead of mirroring exactly what the feed serves this instant.
 */
class NewsRepository(private val context: Context) {

    companion object {
        private const val TAG = "CWJitsu/News"
        private const val CACHE_FILE = "news_cache.json"
        // Played-headline progress lives in its own small file, separate from
        // the full headline cache, so it can be rewritten on every play
        // without rewriting the whole headline list each time.
        private const val BAG_FILE = "news_bag.json"
        private const val CONNECT_TIMEOUT_MS = 8000
        private const val READ_TIMEOUT_MS = 8000

        // Un-forced refreshes within this window of the last successful one
        // are skipped. The News panel triggers a refresh every time it
        // appears; without a floor, hopping between screens re-downloads
        // every feed each time (data + battery for identical headlines).
        private const val MIN_REFRESH_INTERVAL_MS = 10 * 60_000L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Serializes bag-file writes. Plays, refreshes and flushBag() all fire
    // their own persistBag() onto the IO pool, which imposes no ordering
    // between them: two writers could interleave inside File.writeText and
    // leave torn JSON - which loadFromDisk swallows, silently resetting the
    // ENTIRE bag - or simply finish out of order and leave an older snapshot
    // on disk. Held across both the snapshot and the write so that "last
    // write wins" also means "newest state wins". Always acquired BEFORE
    // `lock`, never the other way round, so the two can't deadlock.
    private val bagWriteMutex = Mutex()

    // Same job for the headline cache: two refreshes can overlap (the News
    // panel triggers one on every appearance, the daily worker fires its own),
    // and each ends in a persistCache(). Its own mutex rather than sharing
    // [bagWriteMutex] - the two files are independent, and a bag write on
    // every draw shouldn't queue behind a 500-headline cache write.
    private val cacheWriteMutex = Mutex()

    // Guarded by `lock`: the pool and the played-headline memory. A draw
    // is NOT a play - the bag only changes state on markPlayed, so a round
    // that is generated but abandoned before its headline sounds costs
    // nothing (see HeadlineBag).
    private val lock = Any()
    private val pool = mutableListOf<Headline>()
    private val bag = HeadlineBag()
    private var updatedAtMillis: Long? = null

    private val _status = MutableStateFlow(NewsStatus())
    val status: StateFlow<NewsStatus> = _status

    init {
        scope.launch { loadFromDisk() }
    }

    /**
     * Draw the next headline without replacement, considering only headlines
     * whose [Headline.sourceId] is in [allowedSourceIds] - the cache
     * deliberately holds *every* feed's headlines so a source can be toggled
     * on while offline, but playback must honor the user's current selection.
     * Returns null when nothing eligible is cached. Safe to call every round
     * from the practice loop - it never blocks on I/O.
     *
     * The draw is only provisional: the caller must confirm it with
     * [markPlayed] once the headline actually reaches the speaker, or it
     * returns to the bag.
     */
    fun nextHeadline(allowedSourceIds: Set<String>): Headline? = synchronized(lock) {
        bag.draw(pool.filter { it.sourceId in allowedSourceIds }, System.currentTimeMillis())
    }

    /**
     * How many cached headlines are actually playable with the given source
     * selection. The pool deliberately caches every feed, so the total cache
     * count on its own overstates what a session can draw from - the UI
     * shows this number alongside it.
     */
    fun eligibleCount(allowedSourceIds: Set<String>): Int = synchronized(lock) {
        pool.count { it.sourceId in allowedSourceIds }
    }

    /**
     * Confirm that a headline drawn by [nextHeadline] actually played,
     * recording it as heard. Re-marking (Previous, Restart) just refreshes
     * the recency stamp, which is what recency-ordered replay wants.
     *
     * Bag progress is persisted here rather than at draw time. The file is
     * small and dedicated (played marks only), not the full headline cache,
     * so the write is cheap - and without it a session's most recent plays
     * are lost when the process is reclaimed, replaying already-heard
     * headlines on the next launch.
     */
    fun markPlayed(id: String) {
        synchronized(lock) {
            // The bag remembers plays by title as well as id, so id churn in
            // a feed (revised guids, re-keyed stories) can't resurrect an
            // already-heard headline. The title comes from the pool; if the
            // headline aged out between draw and play, the id mark alone
            // still records it.
            val title = pool.firstOrNull { it.id == id }?.title
            bag.markPlayed(id, title?.let { HeadlineBag.normalizeTitle(it) }, System.currentTimeMillis())
        }
        scope.launch { persistBag() }
    }

    /** Kick a background refresh of the given feeds. Non-blocking. */
    fun refresh(feeds: List<NewsSource>, force: Boolean = false) {
        scope.launch { doRefresh(feeds, force) }
    }

    /** Refresh and suspend until done. Used by the daily background worker. */
    suspend fun refreshAndAwait(feeds: List<NewsSource>, force: Boolean = false) =
        doRefresh(feeds, force)

    // The whole refresh runs on IO regardless of the caller's dispatcher:
    // callers include UI coroutine scopes (main thread), and the feed
    // fetches inside would otherwise inherit that dispatcher and die with
    // NetworkOnMainThreadException.
    private suspend fun doRefresh(
        feeds: List<NewsSource>,
        force: Boolean,
    ): Unit = withContext(Dispatchers.IO) {
        if (feeds.isEmpty()) {
            setStatus(message = "No sources selected.")
            return@withContext
        }
        // Rate limit: automatic triggers (panel shown, app launch) re-use a
        // recent cache instead of re-downloading every feed. The explicit
        // Refresh button and add-feed pass force=true.
        if (!force) {
            val recentEnough = synchronized(lock) {
                pool.isNotEmpty() && updatedAtMillis
                    ?.let { System.currentTimeMillis() - it < MIN_REFRESH_INTERVAL_MS } == true
            }
            if (recentEnough) return@withContext
        }
        if (!hasNetwork()) {
            // The cached count is on its own line in the UI, so these notes
            // say only what the count can't: why it isn't fresher.
            setStatus(
                message = if (poolSize() == 0) "Offline. Connect to download headlines."
                          else "Offline. Using saved headlines.",
            )
            return@withContext
        }
        _status.value = _status.value.copy(refreshing = true, message = null)
        val results: List<Pair<NewsSource, List<Headline>>> = coroutineScope {
            feeds.map { feed ->
                async {
                    feed to runCatching { fetchAndParse(feed) }
                        .onFailure { Log.w(TAG, "feed ${feed.name} failed", it) }
                        .getOrElse { emptyList() }
                }
            }.awaitAll()
        }

        if (results.all { it.second.isEmpty() }) {
            setStatus(
                message = if (poolSize() == 0) "Couldn't load any headlines."
                          else "Couldn't refresh any feed. Using saved headlines.",
            )
            return@withContext
        }
        merge(results, knownIds = feeds.mapTo(HashSet()) { it.id })
        // merge() rewrites the pool and prunes bag progress for aged-out
        // headlines, so both files need writing.
        persistCache()
        persistBag()
        val failed = results.filter { it.second.isEmpty() }.map { it.first }
        // A feed that failed keeps whatever it already had cached (see
        // [merge]), so a failure has two very different meanings and the note
        // has to tell them apart. Reporting both as "No headlines from: X"
        // claimed practice was broken when the offline-first path had in fact
        // done its job and X's saved headlines were still playing.
        val (stale, missing) = synchronized(lock) {
            val cached = pool.mapTo(HashSet()) { it.sourceId }
            failed.partition { it.id in cached }
        }
        setStatus(message = refreshNote(stale.map { it.name }, missing.map { it.name }))
    }

    /**
     * What a refresh couldn't do, or null when it did everything. Kept
     * deliberately narrow: the UI shows the cached headline count on its own
     * line, so this says only what that count can't convey. [stale] sources
     * failed but still have playable headlines; [missing] ones have nothing.
     */
    private fun refreshNote(stale: List<String>, missing: List<String>): String? {
        val parts = mutableListOf<String>()
        if (stale.isNotEmpty()) {
            parts += "Couldn't refresh ${stale.joinToString(", ")}. Still using saved headlines."
        }
        if (missing.isNotEmpty()) {
            parts += "No headlines yet from ${missing.joinToString(", ")}."
        }
        return parts.joinToString(" ").ifBlank { null }
    }

    /**
     * Fold a refresh's results into the pool via [HeadlinePool.merge]:
     * fresh items plus each feed's recently rotated-out headlines, capped
     * and aged; failed feeds keep their cached items (the offline-first
     * promise); removed feeds are purged.
     *
     * Played marks are deliberately NOT pruned against the new pool. They
     * expire on their own schedule (see [HeadlineBag]) - pruning them with
     * the pool meant a story rotating out of a 10-item feed was forgotten
     * within hours, and replayed as new if it ever came back.
     */
    private fun merge(
        results: List<Pair<NewsSource, List<Headline>>>,
        knownIds: Set<String>,
    ) = synchronized(lock) {
        val now = System.currentTimeMillis()
        val merged = HeadlinePool.merge(pool, results, knownIds, now)
        pool.clear()
        pool.addAll(merged)
        bag.prune(now)
        updatedAtMillis = now
    }

    private fun fetchAndParse(feed: NewsSource): List<Headline> {
        val conn = (URL(feed.url).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = true
            // Browser-shaped UA (with the app token appended for feed
            // operators' logs): Cloudflare bot rules on some feeds (e.g.
            // Gothamist) return 403 to unknown non-browser user agents,
            // while every mainstream feed is fine with a browser UA.
            setRequestProperty(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36 CWJitsu/1.0",
            )
            setRequestProperty("Accept", "application/rss+xml, application/atom+xml, application/xml, text/xml")
        }
        try {
            if (conn.responseCode !in 200..299) {
                Log.w(TAG, "feed ${feed.name} HTTP ${conn.responseCode}")
                return emptyList()
            }
            return conn.inputStream.use { parseFeed(it, feed) }
        } finally {
            conn.disconnect()
        }
    }

    /** Parse RSS 2.0 (<item>) or Atom (<entry>) into headlines. */
    private fun parseFeed(input: java.io.InputStream, feed: NewsSource): List<Headline> {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(input, null)

        val out = mutableListOf<Headline>()
        var inEntry = false
        var title = ""
        var link = ""
        var guid = ""

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT && out.size < HeadlinePool.PER_FEED_LIMIT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    when (parser.name.lowercase()) {
                        "item", "entry" -> {
                            inEntry = true; title = ""; link = ""; guid = ""
                        }
                        "title" -> if (inEntry && title.isEmpty()) title = readText(parser)
                        "link" -> if (inEntry && link.isEmpty()) {
                            // Atom carries the URL in href; RSS in the body text.
                            link = parser.getAttributeValue(null, "href") ?: readText(parser)
                        }
                        "guid", "id" -> if (inEntry && guid.isEmpty()) guid = readText(parser)
                    }
                }
                XmlPullParser.END_TAG -> {
                    if (parser.name.lowercase().let { it == "item" || it == "entry" }) {
                        inEntry = false
                        val t = cleanTitle(title)
                        if (t.isNotBlank()) {
                            val idBase = canonicalId(guid)
                                .ifBlank { canonicalId(link).ifBlank { "${feed.id}:$t" } }
                            out.add(
                                Headline(
                                    id = idBase,
                                    title = t,
                                    sourceId = feed.id,
                                    sourceName = feed.name,
                                )
                            )
                        }
                    }
                }
            }
            event = parser.next()
        }
        return out
    }

    /**
     * Read the full text of the element the parser is currently positioned on
     * (a START_TAG), concatenating plain text and CDATA and leaving the parser
     * on the matching END_TAG. Robust to however the parser tokenizes CDATA.
     */
    private fun readText(parser: XmlPullParser): String {
        val sb = StringBuilder()
        var depth = 1
        while (depth > 0) {
            when (parser.next()) {
                XmlPullParser.TEXT, XmlPullParser.CDSECT -> sb.append(parser.text)
                XmlPullParser.START_TAG -> depth++
                XmlPullParser.END_TAG -> depth--
                XmlPullParser.END_DOCUMENT -> return sb.toString()
            }
        }
        return sb.toString()
    }

    /**
     * Canonical identity for a headline: the ARTICLE, not the revision.
     *
     * Most feeds publish a stable permalink guid, but BBC appends a revision
     * counter as a URL fragment - ".../articles/clyj834jn5lo#0" becomes "#1"
     * the first time the story is edited, and a typical BBC world feed has
     * most of its items already past #0. Keying off the raw guid made every
     * edit a brand-new headline: [merge] replaces a refreshed feed's entries
     * wholesale, so the old id left the pool, `playedIds.retainAll` dropped
     * it with the rest, and an already-heard story came straight back around.
     *
     * Only URLs are fragment-stripped; the synthesized "<feedId>:<title>"
     * fallback is left alone because a headline can legitimately contain '#'.
     * Note this affects identity ONLY - the pool is rebuilt from each fetch,
     * so a revised story still plays with its updated wording.
     */
    private fun canonicalId(raw: String): String {
        val trimmed = raw.trim()
        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) return trimmed
        return trimmed.substringBefore('#').trim().ifBlank { trimmed }
    }

    private fun cleanTitle(raw: String): String =
        raw.replace(Regex("<[^>]+>"), " ")      // strip stray HTML
            .replace('\u00A0', ' ')            // non-breaking space
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun hasNetwork(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return true // if we can't tell, optimistically try
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun poolSize(): Int = synchronized(lock) { pool.size }

    private fun setStatus(message: String?) {
        synchronized(lock) {
            _status.value = NewsStatus(
                headlineCount = pool.size,
                updatedAtMillis = updatedAtMillis,
                refreshing = false,
                message = message,
            )
        }
    }

    /**
     * Flush played-headline progress to disk now. Called when a session stops
     * or the app is backgrounded so the last few plays survive the process
     * being reclaimed. Plays already persist the bag individually; this is a
     * cheap belt-and-suspenders write for the moment before a likely teardown.
     */
    fun flushBag() {
        scope.launch { persistBag() }
    }

    /**
     * Wipe the entire news state: cached headlines, freshness stamp, and the
     * played-headline memory, in memory and on disk. User-invoked from the
     * News panel (behind a confirmation) as the clean-slate reset when the
     * cache or the no-repeat memory is suspected of misbehaving. The empty
     * state is persisted through the normal serialized writers, so a flush
     * can't race a concurrent refresh into a torn file; a refresh that is
     * already in flight may still land afterwards and repopulate the cache,
     * which is fine - flushing is about discarding the past, not blocking
     * the future.
     */
    fun flushAll() {
        synchronized(lock) {
            pool.clear()
            bag.clear()
            updatedAtMillis = null
        }
        setStatus(message = "Headlines and play history cleared.")
        scope.launch {
            persistCache()
            persistBag()
        }
    }

    // ---- Persistence -------------------------------------------------------

    private fun cacheFile(): File = File(context.filesDir, CACHE_FILE)
    private fun bagFile(): File = File(context.filesDir, BAG_FILE)

    /**
     * Replace [file] with [text] atomically: write a sibling temp, then
     * rename over the target. A process death mid-write then leaves an
     * orphaned temp rather than a half-written file that the loader can only
     * throw away - and for the bag file, throwing it away means silently
     * resetting every headline to unplayed. Callers hold the matching write
     * mutex, which is what keeps two writers off the same temp path.
     */
    private fun writeAtomically(file: File, text: String) {
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(text)
        // Same directory, so this is an atomic replace. Rename onto an
        // existing target can fail on some filesystems; deleting the target
        // and renaming again keeps the worst case at "file briefly missing"
        // (the loader starts fresh) instead of "file half-written". Only if
        // that also fails does the direct (non-atomic) write run - it still
        // beats dropping the data entirely.
        if (!tmp.renameTo(file)) {
            if (!(file.delete() && tmp.renameTo(file))) {
                file.writeText(text)
                tmp.delete()
            }
        }
    }

    private suspend fun loadFromDisk() = withContext(Dispatchers.IO) {
        val file = cacheFile()
        if (!file.exists()) {
            setStatus(message = "Connect and tap Refresh to download headlines.")
            return@withContext
        }
        runCatching {
            val now = System.currentTimeMillis()
            val o = JSONObject(file.readText())
            val cacheStamp = o.optLong("updatedAt").takeIf { it > 0 }
            val arr = o.optJSONArray("headlines") ?: JSONArray()
            val loaded = (0 until arr.length()).mapNotNull { i ->
                val ho = arr.optJSONObject(i) ?: return@mapNotNull null
                // Migration: caches written before ids were canonicalized hold
                // raw guids, so normalize on the way in - otherwise the bag
                // marks below would miss every headline until the next
                // successful refresh rewrote the pool.
                val id = canonicalId(ho.optString("id")); val title = ho.optString("title")
                if (id.isBlank() || title.isBlank()) return@mapNotNull null
                val name = ho.optString("source")
                // Migration: caches written before headlines carried the
                // stable source id only stored the display name. Built-in
                // names map back to their ids; a legacy custom entry's URL
                // can't be reconstructed from its host label, so it gets an
                // empty id (never eligible) and is replaced by the next
                // successful refresh.
                val sourceId = ho.optString("sourceId").ifBlank {
                    NewsSources.BUILT_IN.firstOrNull { it.name == name }?.id ?: ""
                }
                // Migration: entries written before fetchedAt existed take
                // the cache's own freshness stamp so they age out normally.
                val fetchedAt = ho.optLong("fetchedAt").takeIf { it > 0 }
                    ?: cacheStamp ?: now
                Headline(
                    id = id, title = title, sourceId = sourceId,
                    sourceName = name, fetchedAt = fetchedAt,
                )
            }
            // Bag state lives in its own file; fall back to the legacy
            // in-cache fields for caches written before the split.
            val bagJson = runCatching {
                bagFile().takeIf { it.exists() }?.let { JSONObject(it.readText()) }
            }.onFailure {
                // Loud on purpose: a swallowed read failure here silently
                // resets the played-headline memory, and that used to happen
                // without a trace.
                Log.w(TAG, "bag file unreadable - played-headline memory reset", it)
            }.getOrNull() ?: o
            val state = readBagState(bagJson, now)
            synchronized(lock) {
                // distinctBy: canonicalization can collapse two cached entries
                // that were distinct under their raw guids (same article, two
                // revisions). merge() would dedupe on the next refresh anyway.
                pool.clear(); pool.addAll(loaded.distinctBy { it.id })
                bag.restore(state, now)
                updatedAtMillis = cacheStamp
            }
        }.onFailure { Log.w(TAG, "loadFromDisk failed", it) }
        setStatus(message = if (poolSize() == 0) "Connect and tap Refresh to download headlines." else null)
    }

    /**
     * Decode persisted [HeadlineBagState]. Current format: "playedById" and
     * "playedByTitle" objects mapping key -> played-at millis, plus the two
     * "last" fields. Legacy format (pre-timestamps): a "playedIds" string
     * array and "lastId"; those marks are stamped [now] so they enter the
     * retention window fresh, and canonicalized like the pool ids.
     */
    private fun readBagState(o: JSONObject, now: Long): HeadlineBagState {
        val byId = HashMap<String, Long>()
        val byTitle = HashMap<String, Long>()
        val idsObj = o.optJSONObject("playedById")
        if (idsObj != null) {
            for (key in idsObj.keys()) {
                idsObj.optLong(key).takeIf { it > 0 }?.let { byId[canonicalId(key)] = it }
            }
            val titlesObj = o.optJSONObject("playedByTitle")
            if (titlesObj != null) {
                for (key in titlesObj.keys()) {
                    titlesObj.optLong(key).takeIf { it > 0 }?.let { byTitle[key] = it }
                }
            }
        } else {
            val legacy = o.optJSONArray("playedIds") ?: JSONArray()
            for (i in 0 until legacy.length()) {
                legacy.optString(i)?.takeIf { it.isNotBlank() }?.let { byId[canonicalId(it)] = now }
            }
        }
        return HeadlineBagState(
            playedById = byId,
            playedByTitle = byTitle,
            lastId = canonicalId(o.optString("lastId")).ifBlank { null },
            lastTitleKey = o.optString("lastTitleKey").ifBlank { null },
        )
    }

    /**
     * Write the headline cache (headlines + freshness stamp). Merge only.
     * Serialized and atomic for the same reasons as [persistBag]; a torn
     * cache is less costly (the next refresh rebuilds it) but it still
     * strands the user with no headlines until then - exactly the offline
     * moment the cache exists for.
     */
    private suspend fun persistCache() = withContext(Dispatchers.IO) {
        cacheWriteMutex.withLock {
            val snapshot = synchronized(lock) {
                val o = JSONObject()
                val arr = JSONArray()
                for (h in pool) {
                    arr.put(
                        JSONObject()
                            .put("id", h.id)
                            .put("title", h.title)
                            .put("sourceId", h.sourceId)
                            .put("source", h.sourceName)
                            .put("fetchedAt", h.fetchedAt)
                    )
                }
                o.put("headlines", arr)
                o.put("updatedAt", updatedAtMillis ?: 0L)
                o.toString()
            }
            runCatching { writeAtomically(cacheFile(), snapshot) }
                .onFailure { Log.w(TAG, "persistCache failed", it) }
        }
    }

    /**
     * Write just the played-headline progress. Cheap enough for every play.
     *
     * Serialized by [bagWriteMutex], which is held across the snapshot AND
     * the write: taking the snapshot under `lock` alone left a window where
     * an older snapshot could win the race to disk, and concurrent writers
     * could tear the file outright. Written via temp-then-rename so that a
     * process death mid-write leaves an orphaned temp rather than a
     * half-written bag that [loadFromDisk] can only discard.
     */
    private suspend fun persistBag() = withContext(Dispatchers.IO) {
        bagWriteMutex.withLock {
            val snapshot = synchronized(lock) {
                val state = bag.snapshot()
                val byId = JSONObject()
                for ((id, at) in state.playedById) byId.put(id, at)
                val byTitle = JSONObject()
                for ((key, at) in state.playedByTitle) byTitle.put(key, at)
                JSONObject()
                    .put("playedById", byId)
                    .put("playedByTitle", byTitle)
                    .put("lastId", state.lastId ?: "")
                    .put("lastTitleKey", state.lastTitleKey ?: "")
                    .toString()
            }
            runCatching { writeAtomically(bagFile(), snapshot) }
                .onFailure { Log.w(TAG, "persistBag failed", it) }
        }
    }
}
