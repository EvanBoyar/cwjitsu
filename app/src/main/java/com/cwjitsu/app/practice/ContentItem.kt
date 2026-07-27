package com.cwjitsu.app.practice

/**
 * What kind of content the user is practicing on a given run.
 */
enum class ContentKind {
    CHARACTERS,
    // Prosigns and Q-codes share one category card; which of the two are
    // actually emitted is controlled by MixedConfig.prosignsEnabled /
    // qcodesEnabled when this kind is selected.
    PROSIGNS_QCODES,
    WORDS,
    TEXT,
    CALLSIGNS,
    // Live news headlines drawn from the offline-first NewsRepository cache,
    // filtered to the user's selected sources.
    NEWS,
}

/**
 * A single unit the engine plays/speaks.
 *
 * text          : the canonical display string, e.g. "QTH" or "Hello".
 * spokenAnswer  : how to render the answer aloud (defaults to NATO for letters/digits).
 * morseOverride : if not null, this string of '.' and '-' is used INSTEAD of the lookup in [Morse].
 *                 Useful for prosigns and for arbitrary text where we want our own encoding.
 */
data class ContentItem(
    val text: String,
    val spokenAnswer: String? = null,
    val morseOverride: String? = null,
    // When true, this item ignores the global repetition count and is sent
    // exactly once. Used for long items (e.g. a news headline) where hearing
    // the whole thing repeated would be tedious.
    val singleShot: Boolean = false,
    // For content drawn without replacement, the key identifying the draw, so
    // it can be confirmed once the item actually reaches the speaker. Rounds
    // are generated a whole batch at a time and can be abandoned before every
    // item plays, so drawing and playing are not the same event. Only news
    // headlines set this (see NewsRepository.markPlayed); everything else is
    // generated fresh each round and has nothing to confirm.
    val newsId: String? = null,
)
