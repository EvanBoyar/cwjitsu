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
        textSendWhole: Boolean = false,
        textCharFilter: CharFilter = CharFilter.EVERYTHING,
        callsignRandomPrefix: Boolean = false,
        callsignRandomSuffix: Boolean = false,
        callsignMinLength: Int = MixedConfig.CALLSIGN_LENGTH_RANGE.first,
        callsignMaxLength: Int = MixedConfig.CALLSIGN_LENGTH_RANGE.last,
        characterPool: Set<Char> = MixedConfig.DEFAULT_CHARACTER_SET,
        prosignsEnabled: Boolean = true,
        qcodesEnabled: Boolean = true,
        abbreviationsEnabled: Boolean = true,
        newsItem: ContentItem? = null,
    ): List<ContentItem> {
        if (enabledKinds.isEmpty()) return emptyList()
        val out = mutableListOf<ContentItem>()

        // Visit kinds in a fixed declaration order so the output is
        // deterministic regardless of Set iteration order.
        for (kind in ContentKind.entries) {
            if (kind !in enabledKinds) continue
            when (kind) {
                ContentKind.CHARACTERS -> {
                    // Draw from the user's selected characters, in canonical
                    // Morse order. An empty selection emits nothing.
                    val pool = Morse.characters.keys.filter { it in characterPool }
                    if (pool.isNotEmpty()) {
                        out.addAll(CharacterContentGenerator(pool = pool).batch(1, nato))
                    }
                }
                ContentKind.PROSIGNS_QCODES -> {
                    // One combined category; emit a prosign, Q-code, and/or
                    // abbreviation depending on the sub-toggles. All off
                    // emits nothing.
                    if (prosignsEnabled) {
                        out.addAll(ProsignContentGenerator(spokenMode = prosignMode).batch(1))
                    }
                    if (qcodesEnabled) {
                        out.addAll(QCodeContentGenerator().batch(1, nato))
                    }
                    if (abbreviationsEnabled) {
                        out.addAll(AbbreviationContentGenerator().batch(1))
                    }
                }
                ContentKind.WORDS -> out.addAll(WordContentGenerator(words).batch(1))
                ContentKind.TEXT -> {
                    if (textSource.isNotBlank()) {
                        out.addAll(
                            TextContentGenerator().fromUserText(
                                textSource, nato,
                                sendWhole = textSendWhole,
                                filter = textCharFilter,
                            )
                        )
                    }
                }
                ContentKind.CALLSIGNS -> {
                    if (callsignCountries.isEmpty()) continue
                    // One callsign per selected country per round, in
                    // sorted (deterministic) order. Random prefix/suffix
                    // flags roll independently for each emitted callsign
                    // so the listener hears a mix of bare callsigns,
                    // occasional /prefix, occasional /suffix, and
                    // rare both - weighted toward "neither" because
                    // that's how the air actually sounds.
                    for (countryName in callsignCountries.sorted()) {
                        val country = CallsignRegistry.byName(countryName)
                            ?: CallsignRegistry.countries.first()
                        out.addAll(
                            CallsignGenerator().batch(
                                count = 1,
                                country = country,
                                randomPrefix = callsignRandomPrefix,
                                randomSuffix = callsignRandomSuffix,
                                minLength = callsignMinLength,
                                maxLength = callsignMaxLength,
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
                ContentKind.NEWS -> {
                    // One headline per round, pre-fetched by the caller from
                    // the offline-first NewsRepository. Null when nothing is
                    // cached yet (e.g. first run with no connection).
                    if (newsItem != null) out.add(newsItem)
                }
            }
        }
        return out
    }
}
