package com.sendspindroid

import android.content.Intent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BootReceiverTest {

    @Test
    fun `resumes on boot`() {
        assertTrue(BootReceiver.resumesOn(Intent.ACTION_BOOT_COMPLETED))
    }

    @Test
    fun `resumes after our own update, which kills the process and its player`() {
        assertTrue(BootReceiver.resumesOn(Intent.ACTION_MY_PACKAGE_REPLACED))
    }

    @Test
    fun `ignores other actions`() {
        assertFalse(BootReceiver.resumesOn("android.intent.action.QUICKBOOT_POWERON"))
        assertFalse(BootReceiver.resumesOn(null))
    }
}
