package com.cwjitsu.app.practice

import kotlin.random.Random

/**
 * Independent roll probability for adding a "/" prefix or "/" suffix to a
 * callsign when [CallsignGenerator.next] / [batch] is asked for random
 * decoration. Each side is rolled independently at this rate, so the
 * distribution is:
 *   - ~56% neither
 *   - ~19% prefix only
 *   - ~19% suffix only
 *   -  ~6% both
 * This is the "more commonly neither" shape the user asked for. Tuned to
 * 25% per element rather than 50% so that plain callsigns remain the
 * default on the air and the listener hears decoration as the less
 * common case instead of half the time.
 *
 * The two sides are now independent flags: [randomPrefix] and
 * [randomSuffix]. Setting only one of them yields a tighter
 * distribution centered on "the side you toggled is sometimes present,
 * the other side always absent".
 */
private const val RANDOM_DECORATION_PROBABILITY = 0.25f

/**
 * Generates callsigns by picking a [CallsignTemplate] from a country and emitting
 * prefix + digit + suffix.
 *
 * Three mutation modes per side (prefix and suffix are independent):
 *   1. No decoration — pass `null` or empty for [formatPrefix] / [formatSuffix]
 *      and leave [randomPrefix] / [randomSuffix] false (default).
 *   2. Fixed decoration — pass specific strings for [formatPrefix] /
 *      [formatSuffix]; they are stitched around the core callsign every
 *      time. `null` or empty means "no decoration" on that side.
 *   3. Occasional random decoration — set [randomPrefix] / [randomSuffix]
 *      to true; the corresponding [formatPrefix] / [formatSuffix] are
 *      ignored. Each call rolls
 *      [RANDOM_DECORATION_PROBABILITY] independently for prefix and
 *      suffix, weighted toward "neither", so the user hears mostly bare
 *      callsigns with an occasional /prefix, /suffix, or both.
 */
class CallsignGenerator(private val random: Random = Random.Default) {

    fun next(
        country: CallsignCountry,
        formatPrefix: String? = null,
        formatSuffix: String? = null,
        randomPrefix: Boolean = false,
        randomSuffix: Boolean = false,
    ): String {
        // Resolve effective prefix/suffix for this single call. When
        // random* is on, the side is independently rolled and a non-null
        // option is sampled from the registry list — the null entries
        // are dropped so we never accidentally pick "no prefix" as if
        // it were a value (which would just produce a bare callsign
        // anyway, but doing it explicitly keeps the intent clear).
        val effectivePrefix: String? = if (randomPrefix) {
            if (random.nextFloat() < RANDOM_DECORATION_PROBABILITY) {
                CALLSIGN_PREFIX_OPTIONS.filterNotNull().random(random)
            } else null
        } else formatPrefix

        val effectiveSuffix: String? = if (randomSuffix) {
            if (random.nextFloat() < RANDOM_DECORATION_PROBABILITY) {
                CALLSIGN_SUFFIX_OPTIONS.filterNotNull().random(random)
            } else null
        } else formatSuffix

        val tpl = country.templates.random(random)
        val digit = randomDigit()
        val suffixLen = if (tpl.suffixRange.first == tpl.suffixRange.last)
            tpl.suffixRange.first
        else random.nextInt(tpl.suffixRange.first, tpl.suffixRange.last + 1)
        val suffix = (1..suffixLen).map { randomChar() }.joinToString("")
        val coreCall = tpl.prefix + digit + suffix
        return buildString {
            if (!effectivePrefix.isNullOrEmpty()) append(effectivePrefix)
            append(coreCall)
            if (!effectiveSuffix.isNullOrEmpty()) append(effectiveSuffix)
        }
    }

    fun batch(
        count: Int,
        country: CallsignCountry,
        formatPrefix: String? = null,
        formatSuffix: String? = null,
        randomPrefix: Boolean = false,
        randomSuffix: Boolean = false,
    ): List<String> = List(count) {
        next(country, formatPrefix, formatSuffix, randomPrefix, randomSuffix)
    }

    private fun randomChar(): Char = ('A'..'Z').random(random)
    private fun randomDigit(): Char = ('0'..'9').random(random)
}
