package com.cwjitsu.app.practice

/**
 * PracticeConfig captures everything that affects how a piece of content
 * is rendered into audio. Persisted to DataStore via SettingsRepository.
 */

/**
 * What the spoken answer says for shorthand items (prosigns, Q-codes,
 * and abbreviations).
 */
enum class SpokenAnswerMode {
    /** Speak the literal characters, e.g. "A S" for <AS>. */
    LITERAL,
    /** Speak the plain-English meaning, e.g. "wait" for <AS>. */
    MEANING,
    /** Speak both, characters first: "A S, wait". */
    BOTH;

    val speaksLiteral: Boolean get() = this != MEANING
    val speaksMeaning: Boolean get() = this != LITERAL

    companion object {
        /** Combine the two flags into a mode; null when both are off. */
        fun of(literal: Boolean, meaning: Boolean): SpokenAnswerMode? = when {
            literal && meaning -> BOTH
            literal -> LITERAL
            meaning -> MEANING
            else -> null
        }
    }
}

/**
 * How loose the keying should sound. Adds character to the practice
 * session but still keeps the code recognizable.
 */
enum class SloppyMode {
    /**
     * Clean, machine-perfect timing. No jitter.
     */
    OFF,

    /**
     * Emulate a hand-pumped straight key: random +/-jitter on dot/dash
     * durations and inter-element/character gaps. The code is still
     * unambiguous; the rhythm just sounds human.
     */
    STRAIGHT_KEY,
}

data class PracticeConfig(
    val characterWpm: Int = 18,
    val farnsworthWpm: Int? = null,
    /**
     * When true, each sent item gets a random character speed drawn from
     * [characterWpm] - [speedVarMinusWpm] .. [characterWpm] + [speedVarPlusWpm]
     * (clamped to the valid 5..60 WPM window), like copying different
     * operators on the band. Either side may be 0 for one-directional
     * variation.
     */
    val speedVariabilityEnabled: Boolean = false,
    val speedVarPlusWpm: Int = 3,
    val speedVarMinusWpm: Int = 3,
    val repetitions: Int = 3,
    val postSendPauseMs: Long = 1500,
    val answerDelayMs: Long = 2000,
    val answerEnabled: Boolean = true,
    val replayAfterAnswer: Boolean = false,
    val courtesyToneEnabled: Boolean = true,
    val frequencyHz: Int = 600,
    val frequencyMinHz: Int = 500,
    val frequencyMaxHz: Int = 800,
    val randomizeFrequency: Boolean = false,
    val volumeVariationEnabled: Boolean = true,
    val masterVolume: Float = 0.85f,
    /**
     * Volume of the spoken (TTS) answer, independent of [masterVolume]
     * which only scales the CW tone. 1.0 = the engine's full volume.
     */
    val ttsVolume: Float = 1.0f,
    val noiseType: NoiseType = NoiseType.NONE,
    val noiseVolume: Float = 0.0f,
    val sloppyMode: SloppyMode = SloppyMode.OFF,
    val shorthandSpokenMode: SpokenAnswerMode = SpokenAnswerMode.BOTH,
    /**
     * When true, characters and callsigns are spelled as NATO phonetics
     * ("alpha bravo one golf"). When false, each character is spoken
     * individually (e.g. "A B 1 G W"). For shorthand it only affects the
     * character-spelling half of the answer for Q-codes and abbreviations;
     * prosigns are always spelled letter-by-letter.
     */
    val natoSpokenAnswers: Boolean = true,
) {
    init {
        require(characterWpm in 5..60) { "WPM out of range" }
        require(speedVarPlusWpm in 0..MAX_SPEED_VARIATION_WPM)
        require(speedVarMinusWpm in 0..MAX_SPEED_VARIATION_WPM)
        require(frequencyHz in 300..1500) { "Frequency out of range" }
        require(frequencyMinHz in 300..1500)
        require(frequencyMaxHz in 300..1500)
        require(frequencyMinHz <= frequencyMaxHz)
        require(noiseVolume in 0f..1f)
        require(masterVolume in 0f..1f)
        require(ttsVolume in 0f..1f)
        require(repetitions in 1..20)
    }

    /**
     * Farnsworth stretches gaps to slow the overall speed below the character
     * speed, so only a value strictly below [characterWpm] has any effect.
     */
    fun effectiveFarnsworth(): Int? = farnsworthWpm?.takeIf { it < characterWpm }

    companion object {
        /** Largest +/- offset (WPM) the speed-variability slider allows. */
        const val MAX_SPEED_VARIATION_WPM = 15
    }
}
