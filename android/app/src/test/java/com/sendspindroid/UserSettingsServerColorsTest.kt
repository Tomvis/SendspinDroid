package com.sendspindroid

import android.content.SharedPreferences
import io.mockk.every
import io.mockk.mockk
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The Album colors switch: on until the user turns it off. */
class UserSettingsServerColorsTest {

    private val prefsStore = mutableMapOf<String, Boolean>()

    @Before
    fun setUp() {
        UserSettings.resetForTesting()
        val editor = mockk<SharedPreferences.Editor>(relaxed = true) {
            every { putBoolean(any(), any()) } answers {
                prefsStore[firstArg()] = secondArg()
                this@mockk
            }
        }
        val prefs = mockk<SharedPreferences> {
            every { getBoolean(any(), any()) } answers { prefsStore[firstArg()] ?: secondArg() }
            every { edit() } returns editor
        }
        UserSettings.initializeForTesting(prefs, mockk(relaxed = true))
    }

    @After
    fun tearDown() {
        UserSettings.resetForTesting()
    }

    @Test
    fun `server colors are on by default`() {
        assertTrue(UserSettings.serverColors)
    }

    @Test
    fun `turning server colors off is persisted`() {
        UserSettings.serverColors = false

        assertFalse(UserSettings.serverColors)
        assertFalse(prefsStore.getValue(UserSettings.KEY_SERVER_COLORS))
    }

    @Test
    fun `server colors are on before the settings are loaded`() {
        UserSettings.resetForTesting()

        assertTrue(UserSettings.serverColors)
    }
}
