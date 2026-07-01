package com.cwjitsu.app.practice

/**
 * Expands a set of enabled [ContentKind]s into a flat ordered
 * [List] of [ContentItem]s, one per kind per round.
 *
 * Kinds are visited in the [ContentKind] declaration order so the output is
 * deterministic. Callsigns are emitted one per country per round, in sorted
 * country-name order, so the user hears a predictable cadence. The [nato]
 * flag toggles between NATO phonetics and per-character literal pronunciation
 * for characters, Q-codes, callsigns, and free text. Prosigns always use
 * their own [prosignMode] and are unaffected by [nato].
 */
object ContentMixer {

    fun build(
        enabledKinds: Set<ContentKind>,
        words: List<String>,
        prosignMode: ProsignSpokenMode = ProsignSpokenMode.LITERAL,
        nato: Boolean = true,
        callsignCountries: Set<String> = MixedConfig.DEFAULT_COUNTRIES,
        textSource: String = "",
        callsignPrefix: String? = MixedConfig.DEFAULT_CALLSIGN_PREFIX,
        callsignSuffix: String? = MixedConfig.DEFAULT_CALLSIGN_SUFFIX,
    ): List<ContentItem> {
        if (enabledKinds.isEmpty()) return emptyList()
        val out = mutableListOf<ContentItem>()

        // Visit kinds in a fixed declaration order so the output is
        // deterministic regardless of Set iteration order.
        for (kind in ContentKind.entries) {
            if (kind !in enabledKinds) continue
            when (kind) {
                ContentKind.CHARACTERS -> out.addAll(
                    CharacterContentGenerator().batch(1, nato)
                )
                ContentKind.PROSIGNS -> out.addAll(
                    ProsignContentGenerator(spokenMode = prosignMode).batch(1)
                )
                ContentKind.QCODES -> out.addAll(
                    QCodeContentGenerator().batch(1, nato)
                )
                ContentKind.WORDS -> out.addAll(WordContentGenerator(words).batch(1))
                ContentKind.TEXT -> {
                    if (textSource.isNotBlank()) {
                        out.addAll(TextContentGenerator().fromUserText(textSource, nato))
                    }
                }
                ContentKind.CALLSIGNS -> {
                    if (callsignCountries.isEmpty()) continue
                    // One callsign per selected country per round, in
                    // sorted (deterministic) order. The optional
                    // prefix / suffix from the user's MixedConfig are
                    // stitched around the core callsign by the
                    // generator (e.g. "W1/AB4CD" or "AB4CD/P").
                    for (countryName in callsignCountries.sorted()) {
                        val country = CallsignRegistry.byName(countryName)
                            ?: CallsignRegistry.countries.first()
                        out.addAll(
                            CallsignGenerator().batch(
                                count = 1,
                                country = country,
                                formatPrefix = callsignPrefix,
                                formatSuffix = callsignSuffix,
                            ).map { callsign ->
                                ContentItem(
                                    text = callsign,
                                    spokenAnswer = if (nato) {
                                        Morse.natoFor(callsign)
                                    } else {
                                        Morse.literalFor(callsign)
                                    },
                                )
                            }
                        )
                    }
                }
            }
        }
        return out
    }
}
