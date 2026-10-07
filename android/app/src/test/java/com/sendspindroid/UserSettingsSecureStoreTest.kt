package com.sendspindroid

import android.content.Context
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * What happens to the secrets store when it cannot be opened.
 *
 * Robolectric has no AndroidKeyStore, so every attempt to open an encrypted
 * store fails here - the same path a device with a broken Keystore takes.
 * The file names are spelled out because res/xml/backup_rules.xml and
 * res/xml/data_extraction_rules.xml exclude them from backup by name.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UserSettingsSecureStoreTest {

    private val context: Context = RuntimeEnvironment.getApplication()

    private fun secureFile() = context.getSharedPreferences("sendspin_secure_prefs", Context.MODE_PRIVATE)
    private fun plainFile() = context.getSharedPreferences("sendspin_plain_prefs", Context.MODE_PRIVATE)

    @Before
    fun setUp() = UserSettings.resetForTesting()

    @After
    fun tearDown() = UserSettings.resetForTesting()

    @Test
    fun `an unreadable secure store is deleted and never reused as plaintext`() {
        // Stands in for a store this device cannot decrypt.
        secureFile().edit().putString("unreadable", "ciphertext").commit()

        UserSettings.initialize(context)
        assertTrue(UserSettings.setPskRecordsBlob("records"))

        assertFalse("no Keystore, so the store is not encrypted", UserSettings.isEncrypted)
        assertTrue("the unreadable store must be deleted", secureFile().all.isEmpty())
        assertTrue("the plain store has its own file", plainFile().all.containsValue("records"))
    }
}
