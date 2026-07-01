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
 * [callsignPrefix] is an optional string prepended to every generated
 * callsign (e.g. "W1/") to simulate a host-country / regional indicator
 * used when an operator is portable in another call area. `null` or empty
 * means "no prefix".
 *
 * [callsignSuffix] is an optional string appended to every generated
 * callsign (e.g. "/P", "/M", "/MM", "/QRP") to indicate portable / mobile /
 * maritime-mobile / low-power operation. `null` or empty means "no suffix".
 */
data class MixedConfig(
    val enabledKinds: Set<ContentKind> = DEFAULT_KINDS,
    val callsignCountries: Set<String> = DEFAULT_COUNTRIES,
    val textSource: String = "",
    val callsignPrefix: String? = DEFAULT_CALLSIGN_PREFIX,
    val callsignSuffix: String? = DEFAULT_CALLSIGN_SUFFIX,
) {
    companion object {
        /** Single source of truth for the default toggle set. */
        val DEFAULT_KINDS: Set<ContentKind> = setOf(
            ContentKind.CHARACTERS,
            ContentKind.CALLSIGNS,
        )

        /** Default country selection: USA. */
        val DEFAULT_COUNTRIES: Set<String> = setOf("United States")

        /** No '/' prefix by default. */
        val DEFAULT_CALLSIGN_PREFIX: String? = null

        /** No '/' suffix by default. */
        val DEFAULT_CALLSIGN_SUFFIX: String? = null
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

/** Human-readable label for a prefix option (used by the UI). */
fun formatCallsignPrefixLabel(value: String?): String =
    if (value.isNullOrEmpty()) "None" else value

/** Human-readable label for a suffix option (used by the UI). */
fun formatCallsignSuffixLabel(value: String?): String =
    if (value.isNullOrEmpty()) "None" else value
