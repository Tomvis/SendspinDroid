package com.sendspindroid.ui.settings

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import com.sendspindroid.sendspin.pairing.PairingToken
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A screen reader says what the pairing QR is (#225), and never reads the
 * token out: the token is a secret, and the description is spoken aloud.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PairingQrImageTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val token = PairingToken.encode(
        clientKey = ByteArray(32) { it.toByte() },
        pairingPsk = ByteArray(32) { (0xe0 + it).toByte() },
    )

    @Test
    fun theQrSaysWhatItIs_withoutTheToken() {
        composeTestRule.setContent { PairingQrImage(token = token) }

        val description = composeTestRule
            .onNodeWithContentDescription("pairing token", substring = true)
            .fetchSemanticsNode()
            .config[SemanticsProperties.ContentDescription]
            .single()
        assertFalse(description, token in description)
    }
}
