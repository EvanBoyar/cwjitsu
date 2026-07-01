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
 */
data class MixedConfig(
    val enabledKinds: Set<ContentKind> = DEFAULT_KINDS,
    val callsignCountries: Set<String> = DEFAULT_COUNTRIES,
    val textSource: String = "",
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
