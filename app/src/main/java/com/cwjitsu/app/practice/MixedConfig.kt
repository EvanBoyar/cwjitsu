package com.cwjitsu.app.practice

/**
 * The user's choice of content for the Home / Mixed practice screen.
 *
 * [enabledKinds] is the set of [ContentKind]s the user wants to hear in each
 * round. The session is continuous, so each round emits exactly one
 * [ContentItem] per enabled kind.
 *
 * [callsignCountries] is the set of country allocations the user wants to
 * drill on when CALLSIGNS is enabled. One callsign is generated per country
 * per round, in sorted (deterministic) order.
 *
 * [textSource] is the user's free-text input used when TEXT is enabled.
 *
 * [callsignRandomPrefix] when true, each generated callsign is independently
 * rolled for an optional host-country '/' prefix (e.g. "W1/").
 *
 * [callsignRandomSuffix] when true, each generated callsign is independently
 * rolled for an optional portable-status '/' suffix (e.g. "/M").
 *
 * Each side is rolled independently at 25% so the listener hears mostly plain
 * callsigns (~56%) with occasional decoration on either side. Mirrors what
 * you'd hear on the air where every other operator is running portable from
 * somewhere else.
 */
/**
 * Which characters of a free-text or news item are actually keyed. Anything
 * filtered out is removed from the sent text before it reaches the schedule
 * builder; the spoken (TTS) answer keeps the original wording.
 */
enum class CharFilter {
    /** Send everything (anything the Morse table doesn't know is skipped). */
    EVERYTHING,

    /** Letters and digits only. */
    LETTERS_NUMBERS,

    /** Letters, digits, and the common punctuation . , ? / */
    LETTERS_NUMBERS_COMMON;

    fun apply(text: String): String = when (this) {
        EVERYTHING -> text
        LETTERS_NUMBERS -> text.filter { it.isLetterOrDigit() || it.isWhitespace() }
        LETTERS_NUMBERS_COMMON -> text.filter {
            it.isLetterOrDigit() || it.isWhitespace() || it in COMMON_CHARS
        }
    // Dropping characters can leave doubled-up spaces (e.g. " - " with the
    // dash removed); collapse them so word gaps stay single.
    }.replace(Regex("\\s+"), " ").trim()

    companion object {
        const val COMMON_CHARS = ".,?/"
    }
}

data class MixedConfig(
    val enabledKinds: Set<ContentKind> = DEFAULT_KINDS,
    val callsignCountries: Set<String> = DEFAULT_COUNTRIES,
    val textSource: String = "",
    // When true, the Text category sends the whole source text as ONE item
    // (with normal word gaps inside it) instead of one item per word.
    val textSendWhole: Boolean = false,
    // Per-category character filters for the long-form content kinds.
    val textCharFilter: CharFilter = CharFilter.EVERYTHING,
    val newsCharFilter: CharFilter = CharFilter.EVERYTHING,
    val callsignRandomPrefix: Boolean = false,
    val callsignRandomSuffix: Boolean = false,
    // Bounds on the generated core callsign length (prefix + digit + suffix,
    // NOT counting any '/' decoration). Defaults span the full range the
    // registry can produce, so out of the box nothing is filtered.
    val callsignMinLength: Int = CALLSIGN_LENGTH_RANGE.first,
    val callsignMaxLength: Int = CALLSIGN_LENGTH_RANGE.last,
    val characterSet: Set<Char> = DEFAULT_CHARACTER_SET,
    // When true, the Characters category sends a random-length GROUP of
    // characters as a single item (e.g. "KRQ5T") instead of one character at
    // a time. The group is keyed continuously with normal inter-character
    // gaps and gets a single spoken answer after the whole group is sent.
    val characterGroupsEnabled: Boolean = false,
    // Inclusive bounds on the random group size, within [CHARACTER_GROUP_RANGE].
    // Only consulted when [characterGroupsEnabled] is true.
    val characterGroupMin: Int = 3,
    val characterGroupMax: Int = 5,
    // Words: how far down the frequency-ranked list the draw reaches. Always
    // cumulative from rank 1, so raising it widens the vocabulary rather than
    // swapping in a harder band. Snapped to [WORD_POOL_SIZES] on load.
    val wordPoolSize: Int = DEFAULT_WORD_POOL_SIZE,
    // Curated lists folded in on top of the frequency pool. Amateur radio is
    // on by default - it is what makes Words worth drilling for CW.
    val wordSets: Set<WordSet> = DEFAULT_WORD_SETS,
    // Share of Words rounds drawn from the enrichment sets rather than the
    // frequency pool, split evenly among whichever sets are enabled. Ignored
    // when [wordSets] is empty.
    val wordEnrichmentPercent: Int = DEFAULT_WORD_ENRICHMENT_PERCENT,
    // Sub-toggles for the combined Prosigns & Q-codes category. All default
    // on so selecting the category drills everything, matching the old
    // behavior of enabling the two separate categories.
    val prosignsEnabled: Boolean = true,
    val qcodesEnabled: Boolean = true,
    val abbreviationsEnabled: Boolean = true,
    // News: which built-in sources are on (by NewsSource id) and any custom
    // RSS/Atom feed URLs the user added.
    val enabledNewsSources: Set<String> = DEFAULT_NEWS_SOURCES,
    val customNewsFeeds: List<String> = emptyList(),
    // When true, each news headline is sent exactly once, ignoring the global
    // repetition count - headlines are long, so repeating them is tedious.
    val newsNoRepeat: Boolean = true,
) {
    companion object {
        /** Single source of truth for the default toggle set. */
        val DEFAULT_KINDS: Set<ContentKind> = setOf(
            ContentKind.CHARACTERS,
            ContentKind.CALLSIGNS,
        )

        /** Default country selection: USA. */
        val DEFAULT_COUNTRIES: Set<String> = setOf("United States")

        /**
         * Everything the registry can emit: the shortest core callsign is
         * ITU Generic's empty prefix + digit + 1 letter (2 chars), the
         * longest is a 3-char prefix (e.g. KH6) + digit + 3 letters (7).
         */
        val CALLSIGN_LENGTH_RANGE: IntRange = 2..7

        /**
         * Allowed sizes for a character group when the Characters category is
         * in "groups" mode: 2 to 8 characters per group.
         */
        val CHARACTER_GROUP_RANGE: IntRange = 2..8

        /**
         * Which characters the Characters category draws from by default:
         * the full letters + digits set (the previous hardcoded pool), with
         * punctuation/symbols left off until the user opts in. An empty set
         * is a valid choice and simply makes the category emit nothing.
         */
        val DEFAULT_CHARACTER_SET: Set<Char> =
            (Morse.letters + Morse.digits).toSet()

        /**
         * Default enabled news sources: a couple of reliable wire feeds so the
         * News category works out of the box once the user turns it on.
         */
        val DEFAULT_NEWS_SOURCES: Set<String> = setOf("npr", "bbc")

        /**
         * Vocabulary settings for the Words category, as ranks into the
         * frequency-ordered word list. Each is cumulative from rank 1.
         */
        val WORD_POOL_SIZES: List<Int> = listOf(100, 500, 1000, 2500, 5000)

        /** Middle stop: broad enough to be interesting, narrow enough to learn. */
        const val DEFAULT_WORD_POOL_SIZE: Int = 1000

        /** Selectable shares of rounds drawn from the enrichment sets. */
        val WORD_ENRICHMENT_PERCENTS: List<Int> = listOf(10, 20, 30, 40, 50)

        const val DEFAULT_WORD_ENRICHMENT_PERCENT: Int = 30

        /** Amateur radio only; the rest are opt-in. */
        val DEFAULT_WORD_SETS: Set<WordSet> = setOf(WordSet.RADIO)

        /** Snap an arbitrary saved value onto the nearest legal stop. */
        fun nearestWordPoolSize(value: Int): Int =
            WORD_POOL_SIZES.minByOrNull { kotlin.math.abs(it - value) } ?: DEFAULT_WORD_POOL_SIZE

        /** Snap an arbitrary saved value onto the nearest legal stop. */
        fun nearestWordEnrichmentPercent(value: Int): Int =
            WORD_ENRICHMENT_PERCENTS.minByOrNull { kotlin.math.abs(it - value) }
                ?: DEFAULT_WORD_ENRICHMENT_PERCENT
    }
}

/**
 * Options for the optional '/' prefix (region/host indicator) on
 * generated callsigns. The first entry is `null` which means "no prefix".
 */
val CALLSIGN_PREFIX_OPTIONS: List<String?> = listOf(
    null,
    // US call districts
    "W1/", "W2/", "W3/", "W4/", "W5/", "W6/", "W7/", "W8/", "W9/", "W0/",
    // Canadian call districts
    "VE1/", "VE3/", "VE7/",
    // Other common host prefixes
    "G/", "M/",
    "DL/", "F/", "I/", "EA/", "JA/", "VK/",
)

/**
 * Options for the optional '/' suffix (status indicator) on
 * generated callsigns. The first entry is `null` which means "no suffix".
 */
val CALLSIGN_SUFFIX_OPTIONS: List<String?> = listOf(
    null,
    "/P", "/M", "/MM", "/AM", "/QRP", "/A",
)
