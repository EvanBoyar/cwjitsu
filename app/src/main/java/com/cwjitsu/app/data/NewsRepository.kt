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
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.random.Random

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
 *     which is maddening. Instead a "shuffle bag" plays every headline once
 *     before any repeat, avoids a back-to-back repeat across the cycle seam,
 *     and persists its progress so restarts don't reshuffle.
 *
 *  3. Multi-source. Built-in feeds plus user URLs, parsed as RSS or Atom.
 */
class NewsRepository(private val context: Context) {

    companion object {
        private const val TAG = "CWJitsu/News"
        private const val CACHE_FILE = "news_cache.json"
        private const val PER_FEED_LIMIT = 40
        private const val TOTAL_LIMIT = 500
        private const val CONNECT_TIMEOUT_MS = 8000
        private const val READ_TIMEOUT_MS = 8000

        // Un-forced refreshes within this window of the last successful one
        // are skipped. The News panel triggers a refresh every time it
        // appears; without a floor, hopping between screens re-downloads
        // every feed each time (data + battery for identical headlines).
        private const val MIN_REFRESH_INTERVAL_MS = 10 * 60_000L

        // Shuffle-bag progress is persisted at most this often (plus on
        // every merge). Persisting on every single draw meant a full-cache
        // JSON write to flash every few seconds during a news session; at
        // worst this floor loses a few draws of "already played" state.
        private const val BAG_PERSIST_INTERVAL_MS = 30_000L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val random = Random.Default

    // Guarded by `lock`: the pool and the shuffle-bag state.
    private val lock = Any()
    private val pool = mutableListOf<Headline>()
    private val playedIds = mutableSetOf<String>()
    private var lastId: String? = null
    private var updatedAtMillis: Long? = null
    private var lastBagPersistMillis = 0L

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
     */
    fun nextHeadline(allowedSourceIds: Set<String>): Headline? = synchronized(lock) {
        val eligible = pool.filter { it.sourceId in allowedSourceIds }
        if (eligible.isEmpty()) return null
        var candidates = eligible.filter { it.id !in playedIds }
        if (candidates.isEmpty()) {
            // Cycle through the enabled subset complete - reshuffle just that
            // subset (other sources' bag progress is left alone). Seed the
            // "already played" set with the last headline so we don't play
            // it twice in a row across the seam.
            val eligibleIds = eligible.mapTo(HashSet()) { it.id }
            playedIds.removeAll(eligibleIds)
            lastId?.let { if (eligible.size > 1 && it in eligibleIds) playedIds.add(it) }
            candidates = eligible.filter { it.id !in playedIds }
            if (candidates.isEmpty()) candidates = eligible
        }
        val pick = candidates.random(random)
        playedIds.add(pick.id)
        lastId = pick.id
        // Persist bag progress at most every BAG_PERSIST_INTERVAL_MS, not on
        // every draw - see the constant for why.
        val now = System.currentTimeMillis()
        if (now - lastBagPersistMillis >= BAG_PERSIST_INTERVAL_MS) {
            lastBagPersistMillis = now
            scope.launch { persist() }
        }
        pick
    }

    /** Kick a background refresh of the given feeds. Non-blocking. */
    fun refresh(feeds: List<NewsSource>, force: Boolean = false) {
        scope.launch { doRefresh(feeds, force) }
    }

    /** Refresh and suspend until done. Used by the daily background worker. */
    suspend fun refreshAndAwait(feeds: List<NewsSource>, force: Boolean = false) =
        doRefresh(feeds, force)

    private suspend fun doRefresh(feeds: List<NewsSource>, force: Boolean) {
        if (feeds.isEmpty()) {
            setStatus(message = "No sources selected.")
            return
        }
        // Rate limit: automatic triggers (panel shown, app launch) re-use a
        // recent cache instead of re-downloading every feed. The explicit
        // Refresh button and add-feed pass force=true.
        if (!force) {
            val recentEnough = synchronized(lock) {
                pool.isNotEmpty() && updatedAtMillis
                    ?.let { System.currentTimeMillis() - it < MIN_REFRESH_INTERVAL_MS } == true
            }
            if (recentEnough) return
        }
        if (!hasNetwork()) {
            setStatus(
                message = if (poolSize() == 0) "Offline - no headlines yet."
                          else "Offline - using saved headlines.",
            )
            return
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
                          else "Couldn't refresh - using saved headlines.",
            )
            return
        }
        merge(results, knownIds = feeds.mapTo(HashSet()) { it.id })
        persist()
        val failed = results.filter { it.second.isEmpty() }.map { it.first.name }
        setStatus(
            message = if (failed.isEmpty()) null
                      else "No headlines from: ${failed.joinToString(", ")}",
        )
    }

    /**
     * Fold a refresh's results into the pool. A feed that fetched
     * successfully REPLACES its cached headlines outright, so stale
     * headlines age out instead of accumulating up to [TOTAL_LIMIT];
     * a feed that failed (offline, HTTP error) keeps its cached
     * headlines - the offline-first promise. Headlines from feeds
     * that are no longer configured at all (e.g. a removed custom
     * feed) are purged.
     */
    private fun merge(
        results: List<Pair<NewsSource, List<Headline>>>,
        knownIds: Set<String>,
    ) = synchronized(lock) {
        val refreshedIds = results
            .filter { it.second.isNotEmpty() }
            .mapTo(HashSet()) { it.first.id }
        val fresh = results.flatMap { it.second }
        val kept = pool.filter { it.sourceId in knownIds && it.sourceId !in refreshedIds }
        val seen = HashSet<String>()
        val merged = ArrayList<Headline>(fresh.size + kept.size)
        for (h in fresh + kept) {
            if (seen.add(h.id)) merged.add(h)
            if (merged.size >= TOTAL_LIMIT) break
        }
        pool.clear()
        pool.addAll(merged)
        // Drop bag progress for headlines that aged out of the pool.
        val ids = pool.mapTo(HashSet()) { it.id }
        playedIds.retainAll(ids)
        if (lastId !in ids) lastId = null
        updatedAtMillis = System.currentTimeMillis()
    }

    private fun fetchAndParse(feed: NewsSource): List<Headline> {
        val conn = (URL(feed.url).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "CWJitsu/1.0 (Android)")
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
        while (event != XmlPullParser.END_DOCUMENT && out.size < PER_FEED_LIMIT) {
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
                            val idBase = guid.ifBlank { link.ifBlank { "${feed.id}:$t" } }
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

    // ---- Persistence -------------------------------------------------------

    private fun cacheFile(): File = File(context.filesDir, CACHE_FILE)

    private suspend fun loadFromDisk() = withContext(Dispatchers.IO) {
        val file = cacheFile()
        if (!file.exists()) {
            setStatus(message = "No headlines yet - connect and refresh.")
            return@withContext
        }
        runCatching {
            val o = JSONObject(file.readText())
            val arr = o.optJSONArray("headlines") ?: JSONArray()
            val loaded = (0 until arr.length()).mapNotNull { i ->
                val ho = arr.optJSONObject(i) ?: return@mapNotNull null
                val id = ho.optString("id"); val title = ho.optString("title")
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
                Headline(id = id, title = title, sourceId = sourceId, sourceName = name)
            }
            val played = o.optJSONArray("playedIds") ?: JSONArray()
            synchronized(lock) {
                pool.clear(); pool.addAll(loaded)
                playedIds.clear()
                for (i in 0 until played.length()) played.optString(i)?.let { playedIds.add(it) }
                lastId = o.optString("lastId").ifBlank { null }
                updatedAtMillis = o.optLong("updatedAt").takeIf { it > 0 }
            }
        }.onFailure { Log.w(TAG, "loadFromDisk failed", it) }
        setStatus(message = if (poolSize() == 0) "No headlines yet - connect and refresh." else null)
    }

    private suspend fun persist() = withContext(Dispatchers.IO) {
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
                )
            }
            o.put("headlines", arr)
            o.put("playedIds", JSONArray(playedIds.toList()))
            o.put("lastId", lastId ?: "")
            o.put("updatedAt", updatedAtMillis ?: 0L)
            o.toString()
        }
        runCatching { cacheFile().writeText(snapshot) }
            .onFailure { Log.w(TAG, "persist failed", it) }
    }
}
