package com.cwjitsu.app.practice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class CallsignGeneratorTest {

    private val us = CallsignRegistry.byName("United States")
        ?: error("registry lost the United States")

    @Test
    fun `core callsign is always prefix digit suffix`() {
        val gen = CallsignGenerator(Random(1))
        val prefixes = us.templates.map { it.prefix }
        repeat(200) {
            val call = gen.next(us)
            val prefix = prefixes.filter { call.startsWith(it) }.maxByOrNull { it.length }
            assertTrue("callsign $call has no US prefix", prefix != null)
            val rest = call.removePrefix(prefix!!)
            assertTrue("callsign $call missing single digit after prefix",
                rest.isNotEmpty() && rest[0].isDigit())
            val suffix = rest.drop(1)
            assertTrue("callsign $call suffix '$suffix' not 1-3 letters",
                suffix.length in 1..3 && suffix.all { it in 'A'..'Z' })
        }
    }

    @Test
    fun `every generated callsign is sendable in morse`() {
        val gen = CallsignGenerator(Random(2))
        for (country in CallsignRegistry.countries) {
            val call = gen.next(country, randomPrefix = true, randomSuffix = true)
            assertTrue("callsign $call from ${country.name} has unsendable chars",
                call.all { Morse.codeFor(it) != null })
        }
    }

    @Test
    fun `length bounds are honored when a template can reach them`() {
        val gen = CallsignGenerator(Random(3))
        repeat(100) {
            val call = gen.next(us, minLength = 4, maxLength = 5)
            assertTrue("core $call not in 4..5", call.length in 4..5)
        }
    }

    @Test
    fun `unreachable length request degrades to the closest consistent length`() {
        // The doc promises a 7..7 request against short prefixes yields a
        // consistent nearest length rather than a mix.
        val gen = CallsignGenerator(Random(4))
        val lengths = (1..100).map { gen.next(us, minLength = 7, maxLength = 7).length }.toSet()
        assertEquals("expected one consistent fallback length, got $lengths", 1, lengths.size)
        assertEquals(6, lengths.single())
    }

    @Test
    fun `fixed decoration is stitched on verbatim`() {
        val gen = CallsignGenerator(Random(5))
        val call = gen.next(us, formatPrefix = "DL/", formatSuffix = "/QRP")
        assertTrue(call.startsWith("DL/"))
        assertTrue(call.endsWith("/QRP"))
        // Core between the decorations still holds its shape.
        val core = call.removePrefix("DL/").removeSuffix("/QRP")
        assertTrue(core.length >= 3 && core.none { it == '/' })
    }

    @Test
    fun `no decoration flags means no slashes ever`() {
        val gen = CallsignGenerator(Random(6))
        repeat(100) {
            assertTrue(gen.next(us).none { it == '/' })
        }
    }

    @Test
    fun `random decoration only draws from the option tables`() {
        val gen = CallsignGenerator(Random(7))
        val prefixes = CALLSIGN_PREFIX_OPTIONS.filterNotNull()
        val suffixes = CALLSIGN_SUFFIX_OPTIONS.filterNotNull()
        repeat(300) {
            val call = gen.next(us, randomPrefix = true, randomSuffix = true)
            val pre = prefixes.filter { call.startsWith(it) }
            val suf = suffixes.filter { call.endsWith(it) }
            val stripped = (pre.maxByOrNull { it.length }?.let { call.removePrefix(it) } ?: call)
                .let { c -> suf.maxByOrNull { it.length }?.let { c.removeSuffix(it) } ?: c }
            assertTrue("unexplained slash in $call", stripped.none { it == '/' })
        }
    }

    @Test
    fun `zero weight templates are never picked`() {
        val country = CallsignCountry(
            name = "Test", region = "Nowhere",
            templates = listOf(
                CallsignTemplate("ZZ", weight = 0),
                CallsignTemplate("K", weight = 5),
            ),
        )
        val gen = CallsignGenerator(Random(8))
        repeat(200) {
            assertTrue(gen.next(country).startsWith("K"))
        }
    }

    @Test
    fun `all-zero weights fall back to uniform instead of crashing`() {
        val country = CallsignCountry(
            name = "Test", region = "Nowhere",
            templates = listOf(
                CallsignTemplate("A", weight = 0),
                CallsignTemplate("B", weight = 0),
            ),
        )
        val gen = CallsignGenerator(Random(9))
        val seen = (1..200).map { gen.next(country).first() }.toSet()
        assertEquals(setOf('A', 'B'), seen)
    }

    @Test
    fun `registry length range matches what the registry can actually emit`() {
        // MixedConfig promises 2..7. Verify against the real templates.
        val shortest = CallsignRegistry.countries.flatMap { it.templates }
            .minOf { it.prefix.length + 1 + it.suffixRange.first }
        val longest = CallsignRegistry.countries.flatMap { it.templates }
            .maxOf { it.prefix.length + 1 + it.suffixRange.last }
        assertEquals(MixedConfig.CALLSIGN_LENGTH_RANGE.first, shortest)
        assertEquals(MixedConfig.CALLSIGN_LENGTH_RANGE.last, longest)
    }

    @Test
    fun `every registry prefix is sendable in morse`() {
        for (country in CallsignRegistry.countries) {
            for (t in country.templates) {
                assertTrue("${country.name} prefix '${t.prefix}' has unsendable chars",
                    t.prefix.all { Morse.codeFor(it) != null })
            }
        }
    }
}
