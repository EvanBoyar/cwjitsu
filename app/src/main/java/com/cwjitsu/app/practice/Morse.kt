package com.cwjitsu.app.practice

/**
 * International Morse code mapping.
 * Prosigns are represented by adjacent letters with no gap (e.g. AR = A followed by R).
 * Slash characters in strings map to inter-word gaps.
 *
 * Dot is represented by '.', dash by '-'.
 */
object Morse {

    val characters: Map<Char, String> = linkedMapOf(
        'A' to ".-",    'B' to "-...",  'C' to "-.-.",  'D' to "-..",
        'E' to ".",     'F' to "..-.",  'G' to "--.",   'H' to "....",
        'I' to "..",    'J' to ".---",  'K' to "-.-",   'L' to ".-..",
        'M' to "--",    'N' to "-.",    'O' to "---",   'P' to ".--.",
        'Q' to "--.-",  'R' to ".-.",   'S' to "...",   'T' to "-",
        'U' to "..-",   'V' to "...-",  'W' to ".--",   'X' to "-..-",
        'Y' to "-.--",  'Z' to "--..",
        '0' to "-----", '1' to ".----", '2' to "..---", '3' to "...--",
        '4' to "....-", '5' to ".....", '6' to "-....", '7' to "--...",
        '8' to "---..", '9' to "----.",
        '.' to ".-.-.-", ',' to "--..--", '?' to "..--..", '\'' to ".----.",
        '!' to "-.-.--", '/' to "-..-.", '(' to "-.--.", ')' to "-.--.-",
        '&' to ".-...", ':' to "---...", ';' to "-.-.-.", '=' to "-...-",
        '+' to ".-.-.", '-' to "-....-", '_' to "..--.-", '"' to ".-..-.",
        '\$' to "...-..-", '@' to ".--.-.",
    )

    /**
     * Prosigns are pairs of letters sent with no inter-character gap.
     */
    val prosigns: Map<String, String> = linkedMapOf(
        "AR" to ".-.-.",     // end of message
        "AS" to ".-...",     // wait
        "BT" to "-...-",     // break / new section
        "CT" to "-.-.-.-",   // start of message (also KA)
        "DO" to "-..---",    // shift to alternate code
        "KN" to "-.--.",     // invitation to named station only
        "SK" to "...-.-",    // end of contact
        "SOS" to "...---...",// distress
        "HH" to "........",  // error / correction
    )

    /**
     * Plain-English meaning for each prosign, used when the user picks
     * "Meaning" in the prosign spoken-answer mode.
     */
    val prosignMeanings: Map<String, String> = linkedMapOf(
        "AR" to "end of message",
        "AS" to "wait",
        "BT" to "break",
        "CT" to "start of message",
        "DO" to "shift to alternate code",
        "KN" to "over to you only",
        "SK" to "end of contact",
        "SOS" to "distress",
        "HH" to "error",
    )

    /**
     * Common Q-codes the user can pick from.
     */
    val qCodes: List<String> = listOf(
        "QRA", "QRB", "QRD", "QRE", "QRF", "QRG", "QRH", "QRI", "QRJ", "QRK",
        "QRL", "QRM", "QRN", "QRO", "QRP", "QRQ", "QRS", "QRT", "QRU", "QRV",
        "QRW", "QRX", "QRY", "QRZ", "QSA", "QSB", "QSD", "QSG", "QSL", "QSM",
        "QSN", "QSO", "QSP", "QSR", "QSS", "QST", "QSU", "QSV", "QSW", "QSX",
        "QSY", "QSZ", "QTH", "QTR", "QTX",
    )

    /**
     * NATO phonetic alphabet for the spoken answer.
     */
    val nato: Map<Char, String> = mapOf(
        'A' to "alpha",   'B' to "bravo",   'C' to "charlie", 'D' to "delta",
        'E' to "echo",    'F' to "foxtrot", 'G' to "golf",    'H' to "hotel",
        'I' to "india",   'J' to "juliett", 'K' to "kilo",    'L' to "lima",
        'M' to "mike",    'N' to "november",'O' to "oscar",   'P' to "papa",
        'Q' to "quebec",  'R' to "romeo",   'S' to "sierra",  'T' to "tango",
        'U' to "uniform", 'V' to "victor",  'W' to "whiskey", 'X' to "x-ray",
        'Y' to "yankee",  'Z' to "zulu",
        '0' to "zero",    '1' to "one",     '2' to "two",     '3' to "three",
        '4' to "four",    '5' to "five",    '6' to "six",     '7' to "seven",
        '8' to "eight",   '9' to "nine",
    )

    /**
     * Look up the Morse code for a single character.
     * Returns null if the character is not in the table.
     */
    fun codeFor(ch: Char): String? = characters[ch.uppercaseChar()]

    /**
     * Look up the spoken form of a character. When [useNato] is true (the
     * default) returns the NATO phonetic word ("alpha"); when false,
     * returns the character itself ("A") so the TTS engine spells it out
     * individually.
     */
    fun spokenName(ch: Char, useNato: Boolean = true): String {
        val upper = ch.uppercaseChar()
        return if (useNato) nato[upper] ?: upper.toString() else upper.toString()
    }

    /**
     * Spell an arbitrary text string as a sequence of NATO words,
     * space-separated. Used for the [nato] = true path of callsigns and
     * free text.
     */
    fun natoFor(text: String): String =
        text.map { ch ->
            val upper = ch.uppercaseChar()
            when {
                upper in 'A'..'Z' -> nato[upper] ?: upper.toString()
                upper in '0'..'9' -> nato[upper] ?: upper.toString()
                ch == '/' -> "slash"
                else -> ch.toString()
            }
        }.joinToString(" ")

    /**
     * Render text for the [nato] = false path: each character is
     * separated by a single space so the TTS engine pronounces them
     * individually. For free text where the user wants words spoken as
     * words, prefer passing the text through unchanged instead.
     */
    fun literalFor(text: String): String =
        text.map { it.toString() }.joinToString(" ")

    /**
     * Speak a prosign's letters literally, space-separated. E.g. "AR" -> "A R",
     * "SOS" -> "S O S".
     */
    fun prosignLiteralFor(key: String): String =
        key.toCharArray().joinToString(" ") { it.toString() }
}
