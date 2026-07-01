package com.cwjitsu.app.practice

import kotlin.random.Random

/**
 * Generates callsigns by picking a [CallsignTemplate] from a country and emitting
 * prefix + digit + suffix.
 *
 * The optional [formatPrefix] and [formatSuffix] are stitched around the
 * core callsign. `null` or empty strings are treated as "no decoration"
 * so the default behaviour is unchanged. For example, with prefix "W1/"
 * and suffix "/P" the output for a US callsign would be "W1/AB4CD/P".
 *
 * ITU Generic with an empty prefix will produce bare digit+letter calls,
 * which is fine for warm-ups that simulate "anything you might hear from
 * anywhere".
 */
class CallsignGenerator(private val random: Random = Random.Default) {

    fun next(
        country: CallsignCountry,
        formatPrefix: String? = null,
        formatSuffix: String? = null,
    ): String {
        val tpl = country.templates.random(random)
        val digit = randomDigit()
        val suffixLen = if (tpl.suffixRange.first == tpl.suffixRange.last)
            tpl.suffixRange.first
        else random.nextInt(tpl.suffixRange.first, tpl.suffixRange.last + 1)
        val suffix = (1..suffixLen).map { randomChar() }.joinToString("")
        val coreCall = tpl.prefix + digit + suffix
        return buildString {
            if (!formatPrefix.isNullOrEmpty()) append(formatPrefix)
            append(coreCall)
            if (!formatSuffix.isNullOrEmpty()) append(formatSuffix)
        }
    }

    fun batch(
        count: Int,
        country: CallsignCountry,
        formatPrefix: String? = null,
        formatSuffix: String? = null,
    ): List<String> = List(count) { next(country, formatPrefix, formatSuffix) }

    private fun randomChar(): Char = ('A'..'Z').random(random)
    private fun randomDigit(): Char = ('0'..'9').random(random)
}
