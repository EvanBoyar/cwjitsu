package com.cwjitsu.app.practice

/**
 * A news feed the user can practice with. Built-in sources ship with the app;
 * custom sources are RSS/Atom URLs the user pastes in.
 *
 * [id] is stable and used as the persistence key (built-ins) or is derived
 * from the URL (custom, prefixed "custom:"), so toggling a source and
 * restarting keeps the same identity.
 */
data class NewsSource(
    val id: String,
    val name: String,
    val url: String,
    val builtIn: Boolean,
) {
    companion object {
        const val CUSTOM_PREFIX = "custom:"

        /** Wrap a user-entered feed URL as a custom [NewsSource]. */
        fun custom(url: String): NewsSource {
            val trimmed = url.trim()
            return NewsSource(
                id = CUSTOM_PREFIX + trimmed,
                name = hostLabel(trimmed),
                url = trimmed,
                builtIn = false,
            )
        }

        /** A short, human-friendly label for a feed URL (its host, sans www). */
        fun hostLabel(url: String): String {
            val host = url
                .substringAfter("://", url)
                .substringBefore('/')
                .removePrefix("www.")
            return host.ifBlank { url }
        }
    }
}

/**
 * The built-in feed list. AP no longer publishes an official public RSS, so
 * its URL is a best-effort mirror - if a feed returns nothing the app just
 * skips it, and the user can always add a working URL as a custom source.
 * (Reuters was dropped: they discontinued public RSS entirely.)
 */
object NewsSources {
    val BUILT_IN: List<NewsSource> = listOf(
        NewsSource("ap", "AP", "https://feedx.net/rss/ap.xml", true),
        NewsSource("npr", "NPR", "https://feeds.npr.org/1001/rss.xml", true),
        NewsSource("bbc", "BBC", "https://feeds.bbci.co.uk/news/world/rss.xml", true),
        NewsSource("guardian", "The Guardian", "https://www.theguardian.com/world/rss", true),
        NewsSource("vox", "Vox", "https://www.vox.com/rss/index.xml", true),
        NewsSource("gothamist", "Gothamist", "https://gothamist.com/feed", true),
        NewsSource("thecity", "The City", "https://www.thecity.nyc/rss/", true),
    )

    fun byId(id: String): NewsSource? = BUILT_IN.firstOrNull { it.id == id }

    /**
     * Resolve the set of feeds the user has actually turned on: the enabled
     * built-ins plus the enabled custom URLs (custom feeds toggle by their
     * "custom:<url>" id, exactly like built-ins toggle by theirs). This
     * governs *playback* - downloads always cover [all] feeds so toggling a
     * source on later already has its headlines cached (possibly offline by
     * then).
     */
    fun active(enabledIds: Set<String>, customUrls: List<String>): List<NewsSource> =
        BUILT_IN.filter { it.id in enabledIds } +
            customUrls.map { NewsSource.custom(it) }.filter { it.id in enabledIds }

    /** Every feed we know about: all built-ins plus the user's custom URLs. */
    fun all(customUrls: List<String>): List<NewsSource> =
        BUILT_IN + customUrls.map { NewsSource.custom(it) }
}

/**
 * One parsed headline. [id] is a stable key (feed guid/link, else a hash).
 * [sourceId] is the owning [NewsSource.id] - the SAME key the enable-toggles
 * use, so playback eligibility and cache replacement are keyed by identity,
 * not by display name (names collide: two custom feeds on one host share a
 * host label). [sourceName] is kept purely for display/logging.
 */
data class Headline(
    val id: String,
    val title: String,
    val sourceId: String,
    val sourceName: String,
)
