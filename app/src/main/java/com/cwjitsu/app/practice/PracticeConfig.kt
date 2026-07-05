package com.cwjitsu.app.practice

/**
 * PracticeConfig captures everything that affects how a piece of content
 * is rendered into audio. Persisted to DataStore via SettingsRepository.
 */

/**
 * How the spoken answer for a prosign is rendered.
 */
enum class ProsignSpokenMode {
    /** Speak the literal letters, e.g. "A S" for <AS>. */
    LITERAL,
    /** Speak the plain-English meaning, e.g. "wait" for <AS>. */
    MEANING,
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
    val noiseType: NoiseType = NoiseType.NONE,
    val noiseVolume: Float = 0.0f,
    val sloppyMode: SloppyMode = SloppyMode.OFF,
    val prosignSpokenMode: ProsignSpokenMode = ProsignSpokenMode.LITERAL,
    /**
     * When true, characters and callsigns are spelled as NATO phonetics
     * ("alpha bravo one golf"). When false, each character is spoken
     * individually (e.g. "A B 1 G W"). Prosigns and Q-codes keep their
     * own conventions regardless of this flag.
     */
    val natoSpokenAnswers: Boolean = true,
) {
    init {
        require(characterWpm in 5..60) { "WPM out of range" }
        require(frequencyHz in 300..1500) { "Frequency out of range" }
        require(frequencyMinHz in 300..1500)
        require(frequencyMaxHz in 300..1500)
        require(frequencyMinHz <= frequencyMaxHz)
        require(noiseVolume in 0f..1f)
        require(masterVolume in 0f..1f)
        require(repetitions in 1..20)
    }

    /**
     * Farnsworth stretches gaps to slow the overall speed below the character
     * speed, so only a value strictly below [characterWpm] has any effect.
     */
    fun effectiveFarnsworth(): Int? = farnsworthWpm?.takeIf { it < characterWpm }
}
