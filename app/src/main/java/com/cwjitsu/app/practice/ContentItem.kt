package com.cwjitsu.app.practice

/**
 * What kind of content the user is practising on a given run.
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
    // Placeholder for a future feature: spoken news headlines from
    // user-selected sources. Currently emits nothing.
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
)
