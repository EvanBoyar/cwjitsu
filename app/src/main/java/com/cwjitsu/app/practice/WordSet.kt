package com.cwjitsu.app.practice

/**
 * A curated word list the Words category can fold in on top of the
 * frequency-ranked vocabulary.
 *
 * These are separate from the frequency list rather than merged into it so
 * each one keeps a predictable share of the rounds no matter how wide the
 * vocabulary setting is - see [com.cwjitsu.app.data.WordBag].
 *
 * [label] is user-facing: it names exactly what the set contains, because
 * nothing about what is in the practice pool should require guessing.
 *
 * [asset] is the bundled file the words come from, or null for a set
 * derived at runtime from data the app already holds.
 */
enum class WordSet(val asset: String?, val label: String) {
    RADIO("words_radio.txt", "Amateur radio"),
    COUNTRIES("words_countries.txt", "Countries"),
    // ISO 3166-1 alpha-2. The full standard set rather than codes for just
    // the names in [COUNTRIES]: the complete list is verifiable against the
    // standard, where a hand-built mapping between the two would be exactly
    // the kind of list that quietly goes wrong.
    COUNTRY_CODES("words_country_codes.txt", "Country codes"),
    // Derived from CallsignRegistry, so the prefixes drilled here are
    // always the same ones the Callsigns category generates.
    CALLSIGN_PREFIXES(null, "Callsign prefixes"),
    // 50 states + DC + the five inhabited territories (ISO 3166-2:US).
    STATES("words_states.txt", "US states, DC, territories"),
    STATE_CODES("words_state_codes.txt", "US state codes"),
    PROVINCES("words_provinces.txt", "Canadian provinces"),
    NAMES("words_names.txt", "Names"),
    ;

    companion object {
        /** The frequency-ranked list every Words session draws from. */
        const val FREQUENCY_ASSET = "words_freq.txt"
    }
}
