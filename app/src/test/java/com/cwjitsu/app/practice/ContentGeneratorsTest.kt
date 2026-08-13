package com.cwjitsu.app.practice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class ContentGeneratorsTest {

    @Test
    fun `character generator draws only from its pool`() {
        val pool = listOf('A', 'B', 'C')
        val items = CharacterContentGenerator(pool, Random(1)).batch(100)
        assertTrue(items.all { it.text.single() in pool })
    }

    @Test
    fun `character spoken answer follows the nato flag`() {
        val gen = CharacterContentGenerator(listOf('A'), Random(1))
        assertEquals("alpha", gen.batch(1, nato = true).single().spokenAnswer)
        assertEquals("A", gen.batch(1, nato = false).single().spokenAnswer)
    }

    @Test
    fun `character group size respects its bounds`() {
        val gen = CharacterContentGenerator(listOf('K'), Random(2))
        repeat(50) {
            val item = gen.group(3, 5)
            assertTrue("group '${item.text}' outside 3..5", item.text.length in 3..5)
        }
    }

    @Test
    fun `character group survives inverted and degenerate bounds`() {
        val gen = CharacterContentGenerator(listOf('K'), Random(3))
        assertEquals(4, gen.group(4, 4).text.length)
        // min > max coerces up rather than crashing in nextInt.
        assertEquals(5, gen.group(5, 2).text.length)
        // Zero/negative floors at 1 character.
        assertTrue(gen.group(0, 0).text.length == 1)
    }

    @Test
    fun `prosign items carry the joined morse override`() {
        val items = ProsignContentGenerator(random = Random(4)).batch(50)
        for (item in items) {
            val name = item.text.removePrefix("<").removeSuffix(">")
            assertTrue("text ${item.text} not wrapped in angle brackets",
                item.text.startsWith("<") && item.text.endsWith(">"))
            assertEquals(Morse.prosigns[name], item.morseOverride)
        }
    }

    @Test
    fun `prosign spoken answer follows the mode`() {
        fun answers(mode: SpokenAnswerMode) =
            ProsignContentGenerator(mode, Random(5)).batch(30).map { it.spokenAnswer!! }

        assertTrue(answers(SpokenAnswerMode.NONE).all { it == "" })
        // LITERAL: spaced letters, never the meaning.
        assertTrue(answers(SpokenAnswerMode.LITERAL).all { ans ->
            ans.split(" ").all { it.length == 1 }
        })
        // MEANING: never contains spaced-out single letters only.
        assertTrue(answers(SpokenAnswerMode.MEANING).all { ans ->
            Morse.prosignMeanings.containsValue(ans)
        })
        // BOTH: "literal, meaning".
        assertTrue(answers(SpokenAnswerMode.BOTH).all { it.contains(", ") })
    }

    @Test
    fun `word generator with an exhausted source emits nothing`() {
        assertTrue(WordContentGenerator { null }.batch(5).isEmpty())
    }

    @Test
    fun `word generator uppercases the sent text and speaks the word`() {
        val items = WordContentGenerator { "hello" }.batch(3)
        for (item in items) {
            assertEquals("HELLO", item.text)
            assertEquals("hello", item.spokenAnswer)
        }
    }

    @Test
    fun `word generator carries the draw so the bag can be confirmed on play`() {
        val items = WordContentGenerator { "hello" }.batch(1)
        // The key is the word itself, and it is the UNCHANGED word, not the
        // uppercased display text - the bag's pool holds the raw entries.
        assertEquals(Draw(DrawKind.WORD, "hello"), items.single().draw)
    }

    @Test
    fun `text generator splits into words by default`() {
        val items = TextContentGenerator().fromUserText("the quick fox", nato = false)
        assertEquals(listOf("THE", "QUICK", "FOX"), items.map { it.text })
        assertTrue(items.none { it.text.contains(' ') })
    }

    @Test
    fun `text generator send-whole keeps one item with original spacing`() {
        val items = TextContentGenerator().fromUserText("cq de w1aw", nato = false, sendWhole = true)
        assertEquals(1, items.size)
        assertEquals("CQ DE W1AW", items.single().text)
    }

    @Test
    fun `text generator applies the character filter before splitting`() {
        val items = TextContentGenerator().fromUserText(
            "don't stop!", nato = false, filter = CharFilter.LETTERS_NUMBERS,
        )
        assertEquals(listOf("DONT", "STOP"), items.map { it.text })
    }

    @Test
    fun `blank or fully filtered text yields no items`() {
        val gen = TextContentGenerator()
        assertTrue(gen.fromUserText("   ").isEmpty())
        assertTrue(gen.fromUserText("!!!", filter = CharFilter.LETTERS_NUMBERS).isEmpty())
    }

    @Test
    fun `qcode and abbreviation items are drawn from their tables`() {
        val q = QCodeContentGenerator(random = Random(7)).batch(30)
        assertTrue(q.all { it.text in Morse.qCodes })
        val a = AbbreviationContentGenerator(random = Random(8)).batch(30)
        assertTrue(a.all { it.text in Morse.abbreviations.keys })
    }
}
