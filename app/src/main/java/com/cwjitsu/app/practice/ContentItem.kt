package com.cwjitsu.app.practice

/**
 * What kind of content the user is practising on a given run.
 */
enum class ContentKind {
    CHARACTERS,
    PROSIGNS,
    QCODES,
    WORDS,
    TEXT,
    CALLSIGNS,
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
