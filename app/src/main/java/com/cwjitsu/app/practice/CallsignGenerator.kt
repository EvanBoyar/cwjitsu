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
 *   1. No decoration - pass `null` or empty for [formatPrefix] / [formatSuffix]
 *      and leave [randomPrefix] / [randomSuffix] false (default).
 *   2. Fixed decoration - pass specific strings for [formatPrefix] /
 *      [formatSuffix]; they are stitched around the core callsign every
 *      time. `null` or empty means "no decoration" on that side.
 *   3. Occasional random decoration - set [randomPrefix] / [randomSuffix]
 *      to true; the corresponding [formatPrefix] / [formatSuffix] are
 *      ignored. Each call rolls
 *      [RANDOM_DECORATION_PROBABILITY] independently for prefix and
 *      suffix, weighted toward "neither", so the user hears mostly bare
 *      callsigns with an occasional /prefix, /suffix, or both.
 *
 * [next]'s minLength / maxLength bound the CORE callsign length
 * (prefix + digit + suffix, excluding any '/' decoration). Templates that
 * can't reach the requested range are skipped when possible; otherwise the
 * suffix length is clamped as close to the bounds as the template allows.
 */
class CallsignGenerator(private val random: Random = Random.Default) {

    fun next(
        country: CallsignCountry,
        formatPrefix: String? = null,
        formatSuffix: String? = null,
        randomPrefix: Boolean = false,
        randomSuffix: Boolean = false,
        minLength: Int = 0,
        maxLength: Int = Int.MAX_VALUE,
    ): String {
        // Resolve effective prefix/suffix for this single call. When
        // random* is on, the side is independently rolled and a non-null
        // option is sampled from the registry list - the null entries
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

        // Length bounds apply to the CORE callsign (prefix + digit + suffix),
        // not the '/' decoration. How far a template's reachable lengths sit
        // outside [minLength, maxLength]; 0 means it can hit the range.
        fun distance(t: CallsignTemplate): Int {
            val shortest = t.prefix.length + 1 + t.suffixRange.first
            val longest = t.prefix.length + 1 + t.suffixRange.last
            return maxOf(minLength - longest, shortest - maxLength, 0)
        }
        // Prefer templates that can hit the range. When none can (e.g. every
        // prefix in the country is too short for the min), use the ones that
        // get closest, so a 7..7 request against 1-2 char prefixes yields a
        // consistent 6 rather than a mix of 5s and 6s.
        val best = country.templates.minOf(::distance)
        val candidates = country.templates.filter { distance(it) == best }
        val tpl = candidates.random(random)
        val digit = randomDigit()
        val suffixLo = (minLength - tpl.prefix.length - 1)
            .coerceIn(tpl.suffixRange.first, tpl.suffixRange.last)
        val suffixHi = (maxLength - tpl.prefix.length - 1)
            .coerceIn(tpl.suffixRange.first, tpl.suffixRange.last)
        val suffixLen = if (suffixLo >= suffixHi) suffixLo
        else random.nextInt(suffixLo, suffixHi + 1)
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
        minLength: Int = 0,
        maxLength: Int = Int.MAX_VALUE,
    ): List<String> = List(count) {
        next(country, formatPrefix, formatSuffix, randomPrefix, randomSuffix, minLength, maxLength)
    }

    private fun randomChar(): Char = ('A'..'Z').random(random)
    private fun randomDigit(): Char = ('0'..'9').random(random)
}
