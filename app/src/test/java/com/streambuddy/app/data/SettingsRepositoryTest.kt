package com.streambuddy.app.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsRepositoryTest {
    @Test
    fun `legacy settings are retained when encrypted write fails`() {
        var legacySettingsRemoved = false

        migrateLegacySettings(
            writeSettings = { false },
            removeLegacySettings = { legacySettingsRemoved = true },
        )

        assertFalse(legacySettingsRemoved)
    }

    @Test
    fun `legacy settings are removed after encrypted write succeeds`() {
        var legacySettingsRemoved = false

        migrateLegacySettings(
            writeSettings = { true },
            removeLegacySettings = { legacySettingsRemoved = true },
        )

        assertTrue(legacySettingsRemoved)
    }
}
