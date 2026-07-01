package com.cwjitsu.app.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import android.util.Xml
import com.cwjitsu.app.practice.Headline
import com.cwjitsu.app.practice.NewsSource
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
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val random = Random.Default

    // Guarded by `lock`: the pool and the shuffle-bag state.
    private val lock = Any()
    private val pool = mutableListOf<Headline>()
    private val playedIds = mutableSetOf<String>()
    private var lastId: String? = null
    private var updatedAtMillis: Long? = null

    private val _status = MutableStateFlow(NewsStatus())
    val status: StateFlow<NewsStatus> = _status

    init {
        scope.launch { loadFromDisk() }
    }

    /**
     * Draw the next headline without replacement. Returns null only when the
     * pool is empty (nothing cached yet and we're offline). Safe to call every
     * round from the practice loop - it never blocks on I/O.
     */
    fun nextHeadline(): Headline? = synchronized(lock) {
        if (pool.isEmpty()) return null
        var candidates = pool.filter { it.id !in playedIds }
        if (candidates.isEmpty()) {
            // Cycle complete - reshuffle. Seed the "already played" set with
            // just the last headline so we don't play it twice in a row.
            playedIds.clear()
            lastId?.let { if (pool.size > 1) playedIds.add(it) }
            candidates = pool.filter { it.id !in playedIds }
            if (candidates.isEmpty()) candidates = pool.toList()
        }
        val pick = candidates.random(random)
        playedIds.add(pick.id)
        lastId = pick.id
        scope.launch { persist() }
        pick
    }

    /** Kick a background refresh of the given feeds. Non-blocking. */
    fun refresh(feeds: List<NewsSource>) {
        scope.launch { doRefresh(feeds) }
    }

    /** Refresh and suspend until done. Used by the daily background worker. */
    suspend fun refreshAndAwait(feeds: List<NewsSource>) = doRefresh(feeds)

    private suspend fun doRefresh(feeds: List<NewsSource>) {
        if (feeds.isEmpty()) {
            setStatus(message = "No sources selected.")
            return
        }
        if (!hasNetwork()) {
            setStatus(
                message = if (poolSize() == 0) "Offline - no headlines yet."
                          else "Offline - using saved headlines.",
            )
            return
        }
        _status.value = _status.value.copy(refreshing = true, message = null)
        val fetched = coroutineScope {
            feeds.map { feed ->
                async {
                    runCatching { fetchAndParse(feed) }
                        .onFailure { Log.w(TAG, "feed ${feed.name} failed", it) }
                        .getOrElse { emptyList() }
                }
            }.awaitAll()
        }.flatten()

        if (fetched.isEmpty()) {
            setStatus(
                message = if (poolSize() == 0) "Couldn't load any headlines."
                          else "Couldn't refresh - using saved headlines.",
            )
            return
        }
        merge(fetched)
        persist()
        setStatus(message = null)
    }

    /** Merge freshly fetched headlines into the pool, newest first, deduped. */
    private fun merge(fresh: List<Headline>) = synchronized(lock) {
        val seen = HashSet<String>()
        val merged = ArrayList<Headline>(fresh.size + pool.size)
        for (h in fresh + pool) {
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
            return conn.inputStream.use { parseFeed(it, feed.name) }
        } finally {
            conn.disconnect()
        }
    }

    /** Parse RSS 2.0 (<item>) or Atom (<entry>) into headlines. */
    private fun parseFeed(input: java.io.InputStream, sourceName: String): List<Headline> {
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
                            val idBase = guid.ifBlank { link.ifBlank { "$sourceName:$t" } }
                            out.add(Headline(id = idBase, title = t, sourceName = sourceName))
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
                if (id.isBlank() || title.isBlank()) null
                else Headline(id, title, ho.optString("source"))
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
                arr.put(JSONObject().put("id", h.id).put("title", h.title).put("source", h.sourceName))
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
