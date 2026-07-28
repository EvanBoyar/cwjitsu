package com.cwjitsu.app.practice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class PracticeConfigTest {

    @Test
    fun `defaults construct without error`() {
        PracticeConfig()
    }

    @Test
    fun `farnsworth only takes effect strictly below character speed`() {
        assertEquals(10, PracticeConfig(characterWpm = 20, farnsworthWpm = 10).effectiveFarnsworth())
        assertNull(PracticeConfig(characterWpm = 20, farnsworthWpm = 20).effectiveFarnsworth())
        assertNull(PracticeConfig(characterWpm = 20, farnsworthWpm = 25).effectiveFarnsworth())
        assertNull(PracticeConfig(characterWpm = 20, farnsworthWpm = null).effectiveFarnsworth())
    }

    @Test
    fun `out-of-range values are rejected at construction`() {
        assertThrows(IllegalArgumentException::class.java) { PracticeConfig(characterWpm = 4) }
        assertThrows(IllegalArgumentException::class.java) { PracticeConfig(characterWpm = 61) }
        assertThrows(IllegalArgumentException::class.java) { PracticeConfig(frequencyHz = 200) }
        assertThrows(IllegalArgumentException::class.java) { PracticeConfig(masterVolume = 1.5f) }
        assertThrows(IllegalArgumentException::class.java) { PracticeConfig(repetitions = 0) }
        assertThrows(IllegalArgumentException::class.java) {
            PracticeConfig(frequencyMinHz = 900, frequencyMaxHz = 500)
        }
    }

    @Test
    fun `spoken answer mode round-trips through its flag pair`() {
        for (mode in SpokenAnswerMode.entries) {
            assertEquals(mode, SpokenAnswerMode.of(mode.speaksLiteral, mode.speaksMeaning))
        }
    }
}
