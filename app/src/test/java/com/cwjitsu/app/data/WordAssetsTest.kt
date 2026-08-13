package com.cwjitsu.app.data

import com.cwjitsu.app.practice.CallsignRegistry
import com.cwjitsu.app.practice.WordSet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Guards the bundled word lists themselves, not the code that reads them.
 *
 * The bag's no-repeat guarantee is stated per WORD, so a duplicated entry
 * would quietly break it: the pool would report a size the cycle can never
 * reach, and the duplicate would be twice as likely as everything else.
 * These files are generated and hand-edited, so that is worth a test.
 *
 * Reads the assets straight off the source tree - unit tests run on the JVM
 * with no AssetManager, and the point here is the shipped data.
 */
class WordAssetsTest {

    private fun asset(name: String): List<String> {
        val file = File("src/main/assets/$name")
        assertTrue("missing asset: ${file.absolutePath}", file.exists())
        return file.readLines().map { it.trim() }.filter { it.isNotEmpty() }
    }

    /** Every set backed by a file; CALLSIGN_PREFIXES is derived, not shipped. */
    private fun allAssets(): Map<String, List<String>> =
        buildMap {
            put(WordSet.FREQUENCY_ASSET, asset(WordSet.FREQUENCY_ASSET))
            for (set in WordSet.entries) {
                val name = set.asset ?: continue
                put(name, asset(name))
            }
        }

    @Test
    fun `every list is free of duplicates`() {
        for ((name, words) in allAssets()) {
            val dupes = words.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
            assertTrue("$name has duplicate entries: $dupes", dupes.isEmpty())
        }
    }

    @Test
    fun `every entry is uppercase letters and single spaces`() {
        val allowed = Regex("^[A-Z]+( [A-Z]+)*$")
        for ((name, words) in allAssets()) {
            val bad = words.filterNot { allowed.matches(it) }
            assertTrue("$name has malformed entries: $bad", bad.isEmpty())
        }
    }

    @Test
    fun `the frequency list covers the widest vocabulary setting`() {
        // The largest stop takes 5,000 words; a short asset would silently
        // make the top stop identical to the one below it.
        val words = asset(WordSet.FREQUENCY_ASSET)
        assertTrue("frequency list has only ${words.size} words", words.size >= 5000)
    }

    @Test
    fun `the frequency list keeps common radio words at their natural rank`() {
        // Deliberately NOT deduped against words_radio.txt: someone
        // practicing the plain frequency list must still meet these.
        val words = asset(WordSet.FREQUENCY_ASSET)
        val radio = asset(WordSet.RADIO.asset!!).toSet()
        val shared = words.filter { it in radio }
        assertTrue(
            "expected the two lists to overlap, found ${shared.size} shared words",
            shared.size >= 50,
        )
        for (word in listOf("RADIO", "SIGNAL", "POWER", "CODE", "CALL", "BAND")) {
            assertTrue("$word should be in the frequency list", word in words)
        }
    }

    @Test
    fun `the region lists are complete`() {
        // 50 states + DC + the five inhabited territories (ISO 3166-2:US,
        // minus UM, which has no postal use and no residents).
        assertEquals(56, asset(WordSet.STATES.asset!!).size)
        // 10 provinces + 3 territories.
        assertEquals(13, asset(WordSet.PROVINCES.asset!!).size)
    }

    @Test
    fun `every state name has exactly one code and the other way round`() {
        val names = asset(WordSet.STATES.asset!!)
        val codes = asset(WordSet.STATE_CODES.asset!!)
        assertEquals(
            "each of the ${names.size} places should have one two-letter code",
            names.size, codes.size,
        )
        assertTrue("state codes must all be two letters: $codes", codes.all { it.length == 2 })
        // Spot-check the entries this list existed to add, plus one plain
        // state, so a regenerated file that silently dropped them fails.
        for (code in listOf("DC", "PR", "GU", "AS", "MP", "VI", "OH")) {
            assertTrue("missing state code $code", code in codes)
        }
        for (name in listOf("DISTRICT OF COLUMBIA", "PUERTO RICO", "GUAM")) {
            assertTrue("missing place $name", name in names)
        }
    }

    @Test
    fun `country codes are the full two-letter ISO set`() {
        val codes = asset(WordSet.COUNTRY_CODES.asset!!)
        // ISO 3166-1 currently assigns 249 alpha-2 codes.
        assertEquals(249, codes.size)
        assertTrue("country codes must all be two letters", codes.all { it.length == 2 })
        for (code in listOf("US", "GB", "DE", "JP", "BR", "CA", "AU", "ZA")) {
            assertTrue("missing country code $code", code in codes)
        }
    }

    @Test
    fun `callsign prefixes come from the registry, not a second list`() {
        assertEquals(
            "the prefix set must stay derived, or it can drift from Callsigns",
            null, WordSet.CALLSIGN_PREFIXES.asset,
        )
        val prefixes = CallsignRegistry.allPrefixes
        assertTrue("only ${prefixes.size} prefixes", prefixes.size >= 100)
        assertEquals("prefixes must be unique", prefixes.size, prefixes.toSet().size)
        // A prefix is 1..3 characters of the callsign's leading allocation,
        // letters or digits (4A, 6D and friends are real).
        val bad = prefixes.filterNot { p -> p.length in 1..3 && p.all { it.isLetterOrDigit() } }
        assertTrue("malformed prefixes: $bad", bad.isEmpty())
        // The empty "any prefix" template must not become a practice item.
        assertTrue("blank prefix leaked in", prefixes.none { it.isBlank() })
    }
}
