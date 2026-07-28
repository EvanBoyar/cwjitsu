package com.cwjitsu.app.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckerTest {

    @Test
    fun `plain newer versions are detected`() {
        assertTrue(UpdateChecker.isNewer("1.0.4", "1.0.3"))
        assertTrue(UpdateChecker.isNewer("1.1.0", "1.0.9"))
        assertTrue(UpdateChecker.isNewer("2.0.0", "1.9.9"))
    }

    @Test
    fun `versions compare numerically not lexically`() {
        // The classic trap: "10" < "9" as strings.
        assertTrue(UpdateChecker.isNewer("1.0.10", "1.0.9"))
        assertFalse(UpdateChecker.isNewer("1.0.9", "1.0.10"))
    }

    @Test
    fun `equal and older versions are not newer`() {
        assertFalse(UpdateChecker.isNewer("1.0.3", "1.0.3"))
        assertFalse(UpdateChecker.isNewer("1.0.2", "1.0.3"))
        assertFalse(UpdateChecker.isNewer("0.9.9", "1.0.0"))
    }

    @Test
    fun `leading v is tolerated on either side`() {
        assertTrue(UpdateChecker.isNewer("v1.0.4", "1.0.3"))
        assertTrue(UpdateChecker.isNewer("1.0.4", "v1.0.3"))
        assertFalse(UpdateChecker.isNewer("v1.0.3", "v1.0.3"))
    }

    @Test
    fun `pre-release junk is stripped before comparing`() {
        assertFalse(UpdateChecker.isNewer("v1.0.3-rc1", "1.0.3"))
        assertTrue(UpdateChecker.isNewer("v1.0.4-rc1", "1.0.3"))
    }

    @Test
    fun `different segment counts pad with zero`() {
        assertFalse(UpdateChecker.isNewer("1.0", "1.0.0"))
        assertFalse(UpdateChecker.isNewer("1.0.0", "1.0"))
        assertTrue(UpdateChecker.isNewer("1.0.0.1", "1.0"))
    }

    @Test
    fun `garbage never reports an update`() {
        assertFalse(UpdateChecker.isNewer("", "1.0.3"))
        assertFalse(UpdateChecker.isNewer("latest", "1.0.3"))
        assertFalse(UpdateChecker.isNewer("...", "1.0.3"))
    }
}
