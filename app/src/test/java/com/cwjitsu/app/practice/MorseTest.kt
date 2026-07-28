package com.cwjitsu.app.practice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MorseTest {

    @Test
    fun `every character encoding uses only dots and dashes`() {
        for ((ch, code) in Morse.characters) {
            assertTrue(
                "'$ch' has invalid morse '$code'",
                code.isNotEmpty() && code.all { it == '.' || it == '-' },
            )
        }
    }

    @Test
    fun `no two characters share an encoding`() {
        val seen = mutableMapOf<String, Char>()
        for ((ch, code) in Morse.characters) {
            val prev = seen.put(code, ch)
            assertNull("'$ch' and '$prev' both encode as '$code'", prev)
        }
    }

    @Test
    fun `spot check against the international standard`() {
        // Hand-verified against ITU-R M.1677-1.
        val expected = mapOf(
            'E' to ".", 'T' to "-", 'A' to ".-", 'N' to "-.",
            'S' to "...", 'O' to "---", 'P' to ".--.", 'R' to ".-.",
            'I' to "..", '0' to "-----", '5' to ".....", '9' to "----.",
            '?' to "..--..", '/' to "-..-.", '=' to "-...-",
        )
        for ((ch, code) in expected) {
            assertEquals("wrong encoding for '$ch'", code, Morse.characters[ch])
        }
    }

    @Test
    fun `every prosign is the gapless concatenation of its letters`() {
        // A prosign is by definition its member letters run together.
        // SOS = ...---... etc. This catches any typo in either table.
        for ((name, code) in Morse.prosigns) {
            val joined = name.map { Morse.characters.getValue(it) }.joinToString("")
            assertEquals("prosign <$name> is not its letters joined", joined, code)
        }
    }

    @Test
    fun `every prosign has a spoken meaning`() {
        assertEquals(Morse.prosigns.keys, Morse.prosignMeanings.keys)
    }

    @Test
    fun `letters digits and specials partition the character table`() {
        val union = (Morse.letters + Morse.digits + Morse.specials).toSet()
        assertEquals(Morse.characters.keys, union)
        assertTrue(Morse.letters.none { it in Morse.specials })
        assertTrue(Morse.digits.none { it in Morse.specials })
    }

    @Test
    fun `every abbreviation and q-code is sendable in morse`() {
        for (abbr in Morse.abbreviations.keys) {
            assertTrue("abbreviation $abbr has unsendable chars",
                abbr.all { Morse.codeFor(it) != null })
        }
        for (q in Morse.qCodes) {
            assertTrue("q-code $q has unsendable chars",
                q.all { Morse.codeFor(it) != null })
        }
    }

    @Test
    fun `codeFor is case insensitive and null for unknown`() {
        assertEquals(".-", Morse.codeFor('a'))
        assertEquals(".-", Morse.codeFor('A'))
        assertNull(Morse.codeFor('#'))
        assertNull(Morse.codeFor('é'))
    }

    @Test
    fun `nato covers every letter and digit`() {
        for (ch in Morse.letters + Morse.digits) {
            assertTrue("no NATO word for '$ch'", Morse.nato.containsKey(ch))
        }
    }

    @Test
    fun `natoFor spells letters and digits and passes others through`() {
        assertEquals("whiskey one alpha", Morse.natoFor("W1A"))
        assertEquals("kilo / papa", Morse.natoFor("K/P"))
        assertEquals("bravo romeo alpha victor oscar", Morse.natoFor("bravo"))
    }

    @Test
    fun `literalFor separates every character`() {
        assertEquals("A R", Morse.literalFor("AR"))
        assertEquals("7 3", Morse.literalFor("73"))
        assertEquals("", Morse.literalFor(""))
    }

    @Test
    fun `spokenName falls back to the character itself off the nato table`() {
        assertEquals("alpha", Morse.spokenName('a'))
        assertEquals("A", Morse.spokenName('a', useNato = false))
        assertEquals("?", Morse.spokenName('?'))
    }
}
