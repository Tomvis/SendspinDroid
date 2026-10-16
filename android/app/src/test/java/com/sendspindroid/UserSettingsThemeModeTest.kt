package com.sendspindroid

import android.content.SharedPreferences
import androidx.appcompat.app.AppCompatDelegate
import io.mockk.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class UserSettingsThemeModeTest {

    private val prefsStore = mutableMapOf<String, String?>()

    @Before
    fun setUp() {
        UserSettings.resetForTesting()
        val editor = mockk<SharedPreferences.Editor>(relaxed = true) {
            every { putString(any(), any()) } answers {
                prefsStore[firstArg()] = secondArg()
                this@mockk
            }
        }
        val prefs = mockk<SharedPreferences> {
            every { getString(any(), any()) } answers { prefsStore[firstArg()] ?: secondArg() }
            every { edit() } returns editor
        }
        UserSettings.initializeForTesting(prefs, mockk(relaxed = true))
    }

    @After
    fun tearDown() {
        UserSettings.resetForTesting()
    }

    @Test
    fun `theme follows the system by default`() {
        assertEquals(UserSettings.ThemeMode.SYSTEM, UserSettings.themeMode)
    }

    @Test
    fun `chosen theme is persisted`() {
        UserSettings.themeMode = UserSettings.ThemeMode.DARK

        assertEquals(UserSettings.ThemeMode.DARK, UserSettings.themeMode)
    }

    @Test
    fun `unknown stored theme falls back to system`() {
        prefsStore[UserSettings.KEY_THEME_MODE] = "SEPIA"

        assertEquals(UserSettings.ThemeMode.SYSTEM, UserSettings.themeMode)
    }

    @Test
    fun `each theme maps to its AppCompat night mode`() {
        assertEquals(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM, UserSettings.ThemeMode.SYSTEM.nightMode(isTv = false))
        assertEquals(AppCompatDelegate.MODE_NIGHT_NO, UserSettings.ThemeMode.LIGHT.nightMode(isTv = false))
        assertEquals(AppCompatDelegate.MODE_NIGHT_YES, UserSettings.ThemeMode.DARK.nightMode(isTv = false))
    }

    @Test
    fun `on a TV the system theme is dark and a chosen theme is kept`() {
        assertEquals(AppCompatDelegate.MODE_NIGHT_YES, UserSettings.ThemeMode.SYSTEM.nightMode(isTv = true))
        assertEquals(AppCompatDelegate.MODE_NIGHT_NO, UserSettings.ThemeMode.LIGHT.nightMode(isTv = true))
        assertEquals(AppCompatDelegate.MODE_NIGHT_YES, UserSettings.ThemeMode.DARK.nightMode(isTv = true))
    }
}
