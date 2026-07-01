package com.cwjitsu.app.practice

import kotlin.random.Random

/**
 * Character practice: pick N random chars from the Morse table.
 *
 * @param chars Optional explicit whitelist of characters (e.g. A..Z only).
 * @param includeNumbers If true (default), digits and a few punctuation marks are mixed in.
 */
class CharacterContentGenerator(
    private val pool: List<Char> = (('A'..'Z') + ('0'..'9')).toList(),
    private val random: Random = Random.Default,
) {
    fun batch(count: Int, nato: Boolean = true): List<ContentItem> =
        List(count) { pool.random(random) }
            .map { ch ->
                ContentItem(
                    text = ch.toString(),
                    spokenAnswer = Morse.spokenName(ch, nato),
                )
            }
}

/**
 * Prosign generator: emits a random prosign from [Morse.prosigns], with the prosign's
 * canonical joined Morse so the audio engine does not insert any gap between letters.
 *
 * Spoken answer is rendered according to [spokenMode]:
 *  - LITERAL -> e.g. "<AS>" speaks as "A S"
 *  - MEANING -> e.g. "<AS>" speaks as "wait"
 *
 * The global [PracticeConfig.natoSpokenAnswers] flag does NOT apply to prosigns;
 * they always use the [spokenMode] (which is always literal letter-by-letter).
 */
class ProsignContentGenerator(
    private val spokenMode: ProsignSpokenMode = ProsignSpokenMode.LITERAL,
    private val random: Random = Random.Default,
) {
    fun batch(count: Int): List<ContentItem> {
        val keys = Morse.prosigns.keys.toList()
        return List(count) {
            val key = keys.random(random)
            val answer = when (spokenMode) {
                ProsignSpokenMode.LITERAL -> Morse.prosignLiteralFor(key)
                ProsignSpokenMode.MEANING -> Morse.prosignMeanings[key] ?: Morse.prosignLiteralFor(key)
            }
            ContentItem(
                text = "<$key>",
                spokenAnswer = answer,
                morseOverride = Morse.prosigns[key],
            )
        }
    }
}

/**
 * Q-code generator: each Q-code is a single practice item. The spoken answer
 * prefers the natural-language meaning when one is known (e.g. "QTH" ->
 * "your location"). When no meaning is mapped, it falls back to NATO
 * phonetics ([nato] = true) or the bare code itself ([nato] = false).
 */
class QCodeContentGenerator(
    private val random: Random = Random.Default,
) {
    fun batch(count: Int, nato: Boolean = true): List<ContentItem> {
        val spokenMap: Map<String, String> = rememberSpoken()
        return List(count) {
            val code = Morse.qCodes.random(random)
            val answer = spokenMap[code]
                ?: if (nato) Morse.natoFor(code) else code
            ContentItem(
                text = code,
                spokenAnswer = answer,
            )
        }
    }

    /**
     * Maps a few common Q-codes to shortened spoken answers. Falls back to
     * NATO / literal for less common codes.
     */
    private fun rememberSpoken(): Map<String, String> = mapOf(
        "QTH" to "your location",
        "QRM" to "interference",
        "QRN" to "noise",
        "QSB" to "fading",
        "QSL" to "confirm",
        "QRZ" to "calling",
        "QSY" to "change frequency",
        "QRL" to "busy",
        "QRP" to "low power",
        "QRO" to "high power",
        "QRS" to "slow down",
        "QRQ" to "speed up",
        "QRT" to "stop sending",
        "QRV" to "ready",
        "QTR" to "time",
        "QSA" to "signal strength",
    )
}

/**
 * Word generator: pulls random English words from a bundled list.
 * Words are always spoken as themselves; the NATO toggle does not apply.
 */
class WordContentGenerator(
    private val words: List<String>,
    private val random: Random = Random.Default,
) {
    fun batch(count: Int): List<ContentItem> =
        List(count) { words.random(random) }
            .map { ContentItem(text = it.uppercase(), spokenAnswer = it.lowercase()) }
}

/**
 * Free text generator: returns the user's string parsed as one or more practice
 * items, grouped by whitespace. Each word becomes its own [ContentItem].
 *
 * When [nato] is true, the spoken answer is each word NATO-spelled. When false,
 * the spoken answer is the word itself so the TTS engine pronounces it as a
 * natural word.
 */
class TextContentGenerator {
    fun fromUserText(input: String, nato: Boolean = true): List<ContentItem> {
        val raw = input.uppercase().replace(Regex("[^A-Z0-9 .,?/\"]+"), " ")
        return raw.split(Regex("\\s+"))
            .filter { it.isNotBlank() }
            .map { word ->
                ContentItem(
                    text = word,
                    spokenAnswer = if (nato) Morse.natoFor(word) else word,
                )
            }
    }
}
