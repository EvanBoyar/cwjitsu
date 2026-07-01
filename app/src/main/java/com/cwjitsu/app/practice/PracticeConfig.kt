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

data class PracticeConfig(
    val characterWpm: Int = 18,
    val farnsworthWpm: Int? = null,
    val repetitions: Int = 3,
    val postSendPauseMs: Long = 1500,
    val answerDelayMs: Long = 2000,
    val answerEnabled: Boolean = true,
    val courtesyToneEnabled: Boolean = true,
    val courtesyToneMs: Long = 400,
    val frequencyHz: Int = 600,
    val frequencyMinHz: Int = 500,
    val frequencyMaxHz: Int = 800,
    val randomizeFrequency: Boolean = false,
    val toneFadingEnabled: Boolean = true,
    val volumeVariationEnabled: Boolean = true,
    val masterVolume: Float = 0.85f,
    val noiseType: NoiseType = NoiseType.NONE,
    val noiseVolume: Float = 0.0f,
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

    fun effectiveFarnsworth(): Int? = farnsworthWpm?.takeIf { it >= characterWpm }
}
