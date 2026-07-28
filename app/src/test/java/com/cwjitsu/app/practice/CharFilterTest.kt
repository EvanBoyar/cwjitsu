package com.cwjitsu.app.practice

import org.junit.Assert.assertEquals
import org.junit.Test

class CharFilterTest {

    @Test
    fun `everything keeps symbols but normalizes whitespace`() {
        assertEquals("A B?", CharFilter.EVERYTHING.apply(" A  B? "))
        assertEquals("K1AW/P = OK", CharFilter.EVERYTHING.apply("K1AW/P\t=  OK"))
    }

    @Test
    fun `letters-numbers strips punctuation and collapses the gap it leaves`() {
        assertEquals("ITS 5PM", CharFilter.LETTERS_NUMBERS.apply("IT'S 5PM!"))
        assertEquals("A B", CharFilter.LETTERS_NUMBERS.apply("A - B"))
        assertEquals("UPTODATE", CharFilter.LETTERS_NUMBERS.apply("UP-TO-DATE"))
    }

    @Test
    fun `common filter keeps period comma question and slash`() {
        assertEquals("W1AW/P, OK?", CharFilter.LETTERS_NUMBERS_COMMON.apply("W1AW/P, OK?!"))
        assertEquals("1.5", CharFilter.LETTERS_NUMBERS_COMMON.apply("#1.5!"))
    }

    @Test
    fun `filters never leave leading or trailing whitespace`() {
        for (f in CharFilter.entries) {
            val out = f.apply("  hello * world  ")
            assertEquals(out, out.trim())
        }
    }
}
