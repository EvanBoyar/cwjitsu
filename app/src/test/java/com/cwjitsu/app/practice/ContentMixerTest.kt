package com.cwjitsu.app.practice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContentMixerTest {

    private val words = listOf("hello", "world")

    @Test
    fun `no enabled kinds means no items`() {
        assertTrue(ContentMixer.build(emptySet(), words).isEmpty())
    }

    @Test
    fun `kinds are emitted in declaration order regardless of set order`() {
        // CHARACTERS < WORDS < CALLSIGNS in the enum; hand the set over in
        // scrambled insertion order and expect canonical output order.
        val items = ContentMixer.build(
            linkedSetOf(ContentKind.CALLSIGNS, ContentKind.WORDS, ContentKind.CHARACTERS),
            words,
        )
        assertEquals(3, items.size)
        assertTrue("first item should be a single character", items[0].text.length == 1)
        assertTrue("second item should be a word", items[1].text in listOf("HELLO", "WORLD"))
        assertTrue("third item should be a callsign", items[2].text.any { it.isDigit() })
    }

    @Test
    fun `one callsign per selected country in sorted order`() {
        val items = ContentMixer.build(
            setOf(ContentKind.CALLSIGNS),
            words,
            callsignCountries = setOf("United States", "Canada", "Japan"),
        )
        assertEquals(3, items.size)
        // Canada sorts before Japan before United States.
        assertTrue("expected Canadian prefix, got ${items[0].text}",
            listOf("VE", "VA", "VO", "VY").any { items[0].text.startsWith(it) })
        assertTrue("expected Japanese prefix, got ${items[1].text}",
            items[1].text.first() == 'J')
    }

    @Test
    fun `unknown country falls back instead of crashing`() {
        val items = ContentMixer.build(
            setOf(ContentKind.CALLSIGNS),
            words,
            callsignCountries = setOf("Atlantis"),
        )
        assertEquals(1, items.size)
        assertTrue(items.single().text.isNotBlank())
    }

    @Test
    fun `empty word list emits nothing rather than crashing`() {
        val items = ContentMixer.build(setOf(ContentKind.WORDS), emptyList())
        assertTrue(items.isEmpty())
    }

    @Test
    fun `empty character pool emits nothing rather than crashing`() {
        val items = ContentMixer.build(
            setOf(ContentKind.CHARACTERS),
            words,
            characterPool = emptySet(),
        )
        assertTrue(items.isEmpty())
    }

    @Test
    fun `character groups mode emits one multi-character item`() {
        val items = ContentMixer.build(
            setOf(ContentKind.CHARACTERS),
            words,
            characterGroupsEnabled = true,
            characterGroupMin = 4,
            characterGroupMax = 4,
        )
        assertEquals(1, items.size)
        assertEquals(4, items.single().text.length)
    }

    @Test
    fun `shorthand sub-toggles control what the combined category emits`() {
        val all = ContentMixer.build(setOf(ContentKind.PROSIGNS_QCODES), words)
        assertEquals(3, all.size)
        val none = ContentMixer.build(
            setOf(ContentKind.PROSIGNS_QCODES), words,
            prosignsEnabled = false, qcodesEnabled = false, abbreviationsEnabled = false,
        )
        assertTrue(none.isEmpty())
        val prosignOnly = ContentMixer.build(
            setOf(ContentKind.PROSIGNS_QCODES), words,
            qcodesEnabled = false, abbreviationsEnabled = false,
        )
        assertEquals(1, prosignOnly.size)
        assertTrue(prosignOnly.single().morseOverride != null)
    }

    @Test
    fun `news kind emits the provided headline and nothing without one`() {
        val headline = ContentItem(text = "TEST HEADLINE", singleShot = true, newsId = "x")
        val with = ContentMixer.build(setOf(ContentKind.NEWS), words, newsItem = headline)
        assertEquals(listOf(headline), with)
        val without = ContentMixer.build(setOf(ContentKind.NEWS), words, newsItem = null)
        assertTrue(without.isEmpty())
    }

    @Test
    fun `blank text source emits nothing`() {
        val items = ContentMixer.build(
            setOf(ContentKind.TEXT),
            words,
            textSource = "   ",
        )
        assertTrue(items.isEmpty())
    }

    @Test
    fun `text source flows through with filtering applied`() {
        val items = ContentMixer.build(
            setOf(ContentKind.TEXT),
            words,
            textSource = "cq test!",
            textCharFilter = CharFilter.LETTERS_NUMBERS,
        )
        assertEquals(listOf("CQ", "TEST"), items.map { it.text })
    }
}
