package com.cwjitsu.app.practice

import kotlin.random.Random

/**
 * Generates callsigns by picking a [CallsignTemplate] from a country and emitting
 * prefix + digit + suffix.
 *
 * ITU Generic with an empty prefix will produce bare digit+letter calls, which is fine
 * for warm-ups that simulate "anything you might hear from anywhere".
 */
class CallsignGenerator(private val random: Random = Random.Default) {

    fun next(country: CallsignCountry): String {
        val tpl = country.templates.random(random)
        val digit = randomDigit()
        val suffixLen = if (tpl.suffixRange.first == tpl.suffixRange.last)
            tpl.suffixRange.first
        else random.nextInt(tpl.suffixRange.first, tpl.suffixRange.last + 1)
        val suffix = (1..suffixLen).map { randomChar() }.joinToString("")
        return tpl.prefix + digit + suffix
    }

    fun batch(count: Int, country: CallsignCountry): List<String> =
        List(count) { next(country) }

    private fun randomChar(): Char = ('A'..'Z').random(random)
    private fun randomDigit(): Char = ('0'..'9').random(random)
}
