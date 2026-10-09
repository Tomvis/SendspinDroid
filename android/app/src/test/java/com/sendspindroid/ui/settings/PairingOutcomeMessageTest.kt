package com.sendspindroid.ui.settings

import com.sendspindroid.sendspin.pairing.PairAbortReason
import com.sendspindroid.sendspin.pairing.PairingOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Every way a pairing can end has words of its own (#225).
 *
 * Driven by [PairAbortReason.ALL], so a reason added to the protocol without
 * copy fails here and does not reach a user as a raw wire name.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PairingOutcomeMessageTest {

    private val resources = RuntimeEnvironment.getApplication().resources
    private val server = "Living Room"

    private fun aborted(reason: String, sentByUs: Boolean) =
        PairingOutcome.Aborted(reason, sentByUs, server).message(resources)

    @Test
    fun everyAbortReasonHasItsOwnCopy_whicheverSideSentIt() {
        for (sentByUs in listOf(true, false)) {
            val messages = PairAbortReason.ALL.associateWith { reason ->
                assertNotNull("no copy for $reason (sentByUs=$sentByUs)", abortMessageRes(reason, sentByUs))
                aborted(reason, sentByUs)
            }
            messages.forEach { (reason, message) ->
                assertTrue("empty copy for $reason", message.isNotBlank())
                assertTrue("$reason does not name the server: $message", server in message)
                assertTrue("$reason shows its wire name: $message", reason !in message)
            }
            assertEquals(
                "two reasons share copy (sentByUs=$sentByUs): $messages",
                PairAbortReason.ALL.size, messages.values.toSet().size,
            )
        }
    }

    @Test
    fun aReasonEitherSideCanSendSaysWhichSideDid() {
        for (reason in listOf(PairAbortReason.PAIRING_CODE_MISMATCH, PairAbortReason.USER_CANCELLED)) {
            assertTrue(reason, aborted(reason, sentByUs = true) != aborted(reason, sentByUs = false))
        }
    }

    @Test
    fun successAndUnpairAreDistinctFromEveryAbort() {
        val paired = PairingOutcome.Paired(server).message(resources)
        val unpaired = PairingOutcome.Unpaired(server).message(resources)
        val aborts = PairAbortReason.ALL.flatMap { listOf(aborted(it, true), aborted(it, false)) }

        assertTrue(paired.isNotBlank() && server in paired)
        assertTrue(unpaired.isNotBlank() && server in unpaired)
        assertTrue(paired != unpaired)
        assertTrue(paired !in aborts && unpaired !in aborts)
    }

    @Test
    fun aReasonTheProtocolDoesNotDefineIsStillExplained() {
        val message = aborted("something_new", sentByUs = false)

        assertTrue(message, server in message && "something_new" in message)
    }
}
