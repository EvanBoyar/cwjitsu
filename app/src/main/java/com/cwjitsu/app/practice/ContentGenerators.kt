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

    /**
     * A single group of [minSize]..[maxSize] characters drawn from the pool,
     * emitted as ONE [ContentItem] so the group is keyed continuously (normal
     * inter-character gaps) and the orchestrator speaks the answer once, after
     * the whole group has been sent, rather than after each character. The
     * spoken answer spells the whole group (NATO or letter-by-letter per [nato]).
     */
    fun group(minSize: Int, maxSize: Int, nato: Boolean = true): ContentItem {
        val lo = minSize.coerceAtLeast(1)
        val hi = maxSize.coerceAtLeast(lo)
        val size = if (lo >= hi) lo else random.nextInt(lo, hi + 1)
        val text = List(size) { pool.random(random) }.joinToString("")
        return ContentItem(
            text = text,
            spokenAnswer = if (nato) Morse.natoFor(text) else Morse.literalFor(text),
        )
    }
}

/**
 * Renders a shorthand spoken answer from its literal spelling and (possibly
 * unknown) meaning, per [mode]. BOTH speaks the characters first, then the
 * meaning after a comma pause; an unknown meaning falls back to the literal.
 * NONE returns "" - an explicitly blank answer the orchestrator treats as
 * "speak nothing" (null would fall back to speaking the item text).
 */
private fun shorthandAnswer(mode: SpokenAnswerMode, literal: String, meaning: String?): String =
    when (mode) {
        SpokenAnswerMode.NONE -> ""
        SpokenAnswerMode.LITERAL -> literal
        SpokenAnswerMode.MEANING -> meaning ?: literal
        SpokenAnswerMode.BOTH -> if (meaning == null) literal else "$literal, $meaning"
    }

/**
 * Prosign generator: emits a random prosign from [Morse.prosigns], with the prosign's
 * canonical joined Morse so the audio engine does not insert any gap between letters.
 *
 * Spoken answer is rendered according to [spokenMode]:
 *  - LITERAL -> e.g. "<AS>" speaks as "A S"
 *  - MEANING -> e.g. "<AS>" speaks as "wait"
 *  - BOTH    -> e.g. "<AS>" speaks as "A S, wait"
 *
 * The global [PracticeConfig.natoSpokenAnswers] flag does NOT apply to prosigns;
 * the literal spelling is always letter-by-letter.
 */
class ProsignContentGenerator(
    private val spokenMode: SpokenAnswerMode = SpokenAnswerMode.BOTH,
    private val random: Random = Random.Default,
) {
    fun batch(count: Int): List<ContentItem> {
        val keys = Morse.prosigns.keys.toList()
        return List(count) {
            val key = keys.random(random)
            ContentItem(
                text = "<$key>",
                spokenAnswer = shorthandAnswer(
                    spokenMode,
                    literal = Morse.literalFor(key),
                    meaning = Morse.prosignMeanings[key],
                ),
                morseOverride = Morse.prosigns[key],
            )
        }
    }
}

/**
 * Q-code generator: each Q-code is a single practice item. The spoken answer
 * follows [spokenMode]: the literal half is NATO-spelled ([nato] = true) or
 * letter-by-letter ([nato] = false); the meaning half uses the
 * natural-language meaning when one is known (e.g. "QTH" -> "your location")
 * and falls back to the literal spelling otherwise.
 */
class QCodeContentGenerator(
    private val spokenMode: SpokenAnswerMode = SpokenAnswerMode.BOTH,
    private val random: Random = Random.Default,
) {
    fun batch(count: Int, nato: Boolean = true): List<ContentItem> {
        val spokenMap: Map<String, String> = rememberSpoken()
        return List(count) {
            val code = Morse.qCodes.random(random)
            ContentItem(
                text = code,
                spokenAnswer = shorthandAnswer(
                    spokenMode,
                    literal = if (nato) Morse.natoFor(code) else Morse.literalFor(code),
                    meaning = spokenMap[code],
                ),
            )
        }
    }

    /**
     * Short spoken meaning for every Q-code in [Morse.qCodes], favoring the
     * amateur-radio sense where it differs from modern ITU (e.g. QRJ keeps
     * the original "signals too weak"). Grounded in the ARRL Q-signals
     * reference sheet and the ITU table on Wikipedia's Q-code article.
     */
    private fun rememberSpoken(): Map<String, String> = mapOf(
        "QRA" to "station name",
        "QRB" to "distance between stations",
        "QRD" to "your destination",
        "QRE" to "estimated arrival time",
        "QRF" to "returning to place",
        "QRG" to "exact frequency",
        "QRH" to "frequency varies",
        "QRI" to "transmission tone",
        "QRJ" to "signals too weak",
        "QRK" to "readability",
        "QRL" to "busy",
        "QRM" to "interference",
        "QRN" to "noise",
        "QRO" to "high power",
        "QRP" to "low power",
        "QRQ" to "speed up",
        "QRS" to "slow down",
        "QRT" to "stop sending",
        "QRU" to "nothing for you",
        "QRV" to "ready",
        "QRW" to "inform them I'm calling",
        "QRX" to "stand by",
        "QRY" to "your turn number",
        "QRZ" to "who is calling me",
        "QSA" to "signal strength",
        "QSB" to "fading",
        "QSD" to "keying defective",
        "QSG" to "send messages in batches",
        "QSK" to "break in",
        "QSL" to "confirm",
        "QSM" to "repeat last message",
        "QSN" to "heard you on frequency",
        "QSO" to "contact",
        "QSP" to "relay",
        "QSR" to "repeat your call",
        "QSS" to "working frequency",
        "QST" to "general call",
        "QSU" to "reply on this frequency",
        "QSV" to "send series of Vs",
        "QSW" to "sending on this frequency",
        "QSX" to "listening on frequency",
        "QSY" to "change frequency",
        "QSZ" to "send each word twice",
        "QTC" to "messages to send",
        "QTH" to "your location",
        "QTR" to "time",
        "QTX" to "keep station open",
    )
}

/**
 * Abbreviation generator: common CW abbreviations (73, TU, WX...) from
 * [Morse.abbreviations]. Sent as ordinary spaced characters - unlike
 * prosigns nothing is joined, because that is how abbreviations are keyed
 * on the air. The spoken answer follows [spokenMode], matching the Q-code
 * behavior: literal spelling (NATO or letter-by-letter per [nato]),
 * meaning, or both.
 */
class AbbreviationContentGenerator(
    private val spokenMode: SpokenAnswerMode = SpokenAnswerMode.BOTH,
    private val random: Random = Random.Default,
) {
    fun batch(count: Int, nato: Boolean = true): List<ContentItem> {
        val keys = Morse.abbreviations.keys.toList()
        return List(count) {
            val abbr = keys.random(random)
            ContentItem(
                text = abbr,
                spokenAnswer = shorthandAnswer(
                    spokenMode,
                    literal = if (nato) Morse.natoFor(abbr) else Morse.literalFor(abbr),
                    meaning = Morse.abbreviations[abbr],
                ),
            )
        }
    }
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
 * Free text generator: returns the user's string as practice items.
 *
 * With [sendWhole] false (default), the text splits on whitespace and each
 * word becomes its own [ContentItem] - answered and repeated individually.
 * With [sendWhole] true, the whole text is ONE item; the schedule builder
 * inserts proper word gaps inside a multi-word item, so the text is keyed
 * continuously like a news headline.
 *
 * [filter] trims the keyed characters down (e.g. letters+digits only) before
 * anything is split; the spoken answer is built from the same filtered text
 * so the answer matches what was actually sent.
 *
 * When [nato] is true, the spoken answer is NATO-spelled. When false, the
 * spoken answer is the text itself so the TTS engine pronounces natural words.
 */
class TextContentGenerator {
    fun fromUserText(
        input: String,
        nato: Boolean = true,
        sendWhole: Boolean = false,
        filter: CharFilter = CharFilter.EVERYTHING,
    ): List<ContentItem> {
        val cleaned = filter.apply(input.uppercase())
        if (cleaned.isBlank()) return emptyList()
        if (sendWhole) {
            return listOf(
                ContentItem(
                    text = cleaned,
                    spokenAnswer = if (nato) Morse.natoFor(cleaned) else cleaned,
                )
            )
        }
        return cleaned.split(Regex("\\s+"))
            .filter { it.isNotBlank() }
            .map { word ->
                ContentItem(
                    text = word,
                    spokenAnswer = if (nato) Morse.natoFor(word) else word,
                )
            }
    }
}
