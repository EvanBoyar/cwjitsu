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
 * [callsignRandomDecoration] when true, each generated callsign is
 * independently rolled for an optional '/' prefix and '/' suffix. The
 * roll is biased toward "no decoration" — on average the listener hears
 * a plain callsign most of the time, occasionally a host-country
 * "/prefix", occasionally a portable-status "/suffix", and rarely both.
 * Mirrors what you'd hear on the air where every other operator is
 * running portable from somewhere else.
 */
data class MixedConfig(
    val enabledKinds: Set<ContentKind> = DEFAULT_KINDS,
    val callsignCountries: Set<String> = DEFAULT_COUNTRIES,
    val textSource: String = "",
    val callsignRandomDecoration: Boolean = false,
) {
    companion object {
        /** Single source of truth for the default toggle set. */
        val DEFAULT_KINDS: Set<ContentKind> = setOf(
            ContentKind.CHARACTERS,
            ContentKind.CALLSIGNS,
        )

        /** Default country selection: USA. */
        val DEFAULT_COUNTRIES: Set<String> = setOf("United States")
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
