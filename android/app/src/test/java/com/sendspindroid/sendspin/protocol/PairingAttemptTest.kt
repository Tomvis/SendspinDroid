package com.sendspindroid.sendspin.protocol

import com.sendspindroid.sendspin.crypto.Base64Url
import com.sendspindroid.sendspin.crypto.PskCategory
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A pairing attempt as the handler runs it: which messages go out and in what
 * order, how `pairing_index` is counted, and what ends an attempt.
 *
 * pairing.md, "Entering and leaving pairing", "Pairing PSK Flow" and
 * "Messages". The flows' own rules are pinned in the shared module; these are
 * the rules that only exist once a flow is wired to a connection.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PairingAttemptTest {

    private val dynamic = "dynamic_pairing_code"

    private fun handler(matched: PskCategory) =
        CrossTerminationTestHandler().also { it.matchedCategory = matched }

    private fun CrossTerminationTestHandler.receive(text: String) {
        handleTextMessageForTest(text)
        scope.runCurrent()
    }

    /** Everything sent that is a pairing message, plus any close. */
    private fun CrossTerminationTestHandler.pairingEvents(): List<String> =
        events.filter { it == "close" || it.startsWith("send:client/pair-") || it == "send:pair/abort" }

    private fun CrossTerminationTestHandler.sentPayloads(type: String): List<JsonObject> = sent
        .map { Json.parseToJsonElement(it).jsonObject }
        .filter { it["type"]?.jsonPrimitive?.content == type }
        .map { it["payload"]!!.jsonObject }

    private fun CrossTerminationTestHandler.abortReasons(): List<String> =
        sentPayloads("pair/abort").map { it["reason"]!!.jsonPrimitive.content }

    private fun CrossTerminationTestHandler.pairInitIndexes(): List<Int> =
        sentPayloads("client/pair-init").map { it["pairing_index"]!!.jsonPrimitive.content.toInt() }

    private fun serverPairInit() =
        """{"type":"server/pair-init","payload":{"nonce_A":"${Base64Url.encode(ByteArray(32) { 5 })}"}}"""

    private fun serverPairAuth() =
        """{"type":"server/pair-auth","payload":{"pake_msg_1":"${Base64Url.encode(VALID_SHARE)}"}}"""

    /** A well-formed tag that cannot verify: what a mistyped code produces. */
    private fun serverPairConfirm() =
        """{"type":"server/pair-confirm","payload":{"server_kc":"${Base64Url.encode(ByteArray(64))}"}}"""

    private val serverPairFinalize = """{"type":"server/pair-finalize","payload":{}}"""

    /** A dynamic attempt driven up to a failed `server_kc`. */
    private fun mismatchedAttempt(): CrossTerminationTestHandler {
        val handler = handler(PskCategory.SENTINEL)
        handler.receive(pairingActivateJson(dynamic))
        handler.receive(serverPairInit())
        assertNotNull("the code is shown once server/pair-init arrives", handler.shownCode)
        handler.receive(serverPairAuth())
        handler.clearEvents()
        handler.receive(serverPairConfirm())
        return handler
    }

    // ========== Pairing PSK flow ==========

    @Test
    fun `the pairing psk flow sends pair-init then pair-finalize without waiting`() {
        val handler = handler(PskCategory.PAIRING)

        handler.receive(pairingActivateJson("pairing_psk"))

        assertEquals(
            listOf("send:client/pair-init", "send:client/pair-finalize"),
            handler.pairingEvents(),
        )
        // "commit_B: Required in the Dynamic Pairing Code Flow; absent otherwise."
        assertEquals(
            """{"pairing_index":1}""",
            handler.sentPayloads("client/pair-init").single().toString(),
        )
    }

    @Test
    fun `a new pairing activation supersedes the pairing psk attempt in flight`() {
        val handler = handler(PskCategory.PAIRING)
        handler.receive(pairingActivateJson("pairing_psk"))
        val firstPsk = handler.sentPayloads("client/pair-finalize").single()["long_term_psk"]!!
            .jsonPrimitive.content
        handler.clearEvents()

        handler.receive(pairingActivateJson("pairing_psk"))

        assertEquals(
            listOf("send:client/pair-init", "send:client/pair-finalize"),
            handler.pairingEvents(),
        )
        assertEquals(listOf(2), handler.pairInitIndexes())
        val secondPsk = handler.sentPayloads("client/pair-finalize").single()["long_term_psk"]!!
            .jsonPrimitive.content
        assertFalse("the old attempt's secret must be discarded", firstPsk == secondPsk)

        // The ack completes the new attempt, with the new secret.
        handler.receive(serverPairFinalize)
        assertArrayEquals(
            Base64Url.decodeOrNull(secondPsk),
            handler.store.listRecords().single().psk,
        )
    }

    @Test
    fun `a refused pairing activation still supersedes the attempt in flight`() {
        val handler = handler(PskCategory.PAIRING)
        handler.receive(pairingActivateJson("pairing_psk"))
        handler.clearEvents()

        // The dynamic method is not admissible on a pairing-PSK-keyed session.
        handler.receive(pairingActivateJson(dynamic))
        assertEquals(listOf("method_not_supported"), handler.abortReasons())

        handler.receive(serverPairFinalize)
        assertEquals(
            "the superseded attempt must not persist a record",
            0,
            handler.store.listRecords().size,
        )
        // Nor does its timer outlive it.
        handler.clearEvents()
        handler.scope.advanceTimeBy(SendSpinProtocol.PAIR_ATTEMPT_TIMEOUT_MS * 2)
        handler.scope.runCurrent()
        assertEquals(emptyList<String>(), handler.events)
    }

    // ========== pairing_index ==========

    @Test
    fun `pairing_index counts an activation the client refused`() {
        // "The number of pairing server/activate messages received since the
        // last Noise handshake" - the server counted the refused one, so a
        // client that did not would send an index the server discards as a
        // leftover, and the attempt would never start.
        val handler = handler(PskCategory.SENTINEL)

        handler.receive(pairingActivateJson("static_pairing_code"))
        assertEquals(listOf("method_not_supported"), handler.abortReasons())
        assertEquals(emptyList<Int>(), handler.pairInitIndexes())

        handler.receive(pairingActivateJson(dynamic))
        assertEquals(listOf(2), handler.pairInitIndexes())
    }

    // ========== pairing.format ==========

    @Test
    fun `an emission format the client does not offer is method_not_supported`() {
        for (format in listOf("qr_code", null)) {
            val handler = handler(PskCategory.SENTINEL)

            handler.receive(pairingActivateJson(dynamic, format = format))

            assertEquals(
                "format $format",
                listOf("send:pair/abort"),
                handler.pairingEvents(),
            )
            assertEquals(listOf("method_not_supported"), handler.abortReasons())
            assertNull(handler.shownCode)

            // "Leaving the connection open": the server may activate again.
            handler.clearEvents()
            handler.receive(pairingActivateJson(dynamic, format = "digits"))
            assertEquals(listOf(2), handler.pairInitIndexes())
        }
    }

    // ========== After the client's own abort ==========

    @Test
    fun `pairing messages after our abort are discarded until the next activation`() {
        val handler = handler(PskCategory.SENTINEL)
        handler.receive(pairingActivateJson("static_pairing_code"))
        handler.clearEvents()

        // Still in flight from a server that had not yet seen the abort.
        for (message in listOf(serverPairInit(), serverPairAuth(), serverPairConfirm(), serverPairFinalize)) {
            handler.receive(message)
        }

        assertEquals(emptyList<String>(), handler.protocolFailures)
        assertEquals(emptyList<String>(), handler.events)
        assertEquals(0, handler.store.listRecords().size)
    }

    @Test
    fun `a dynamic pairing message with no attempt and no abort is a protocol error`() {
        // The silent discard covers only the window after our own abort.
        val handler = handler(PskCategory.SENTINEL)

        handler.receive(serverPairInit())

        assertEquals(1, handler.protocolFailures.size)
    }

    // ========== server_kc mismatch ==========

    @Test
    fun `a server_kc mismatch aborts, stops showing the code and cancels the attempt timer`() {
        val handler = mismatchedAttempt()

        assertEquals(listOf("send:pair/abort"), handler.pairingEvents())
        assertEquals(listOf("pairing_code_mismatch"), handler.abortReasons())
        assertNull("the code must leave the screen", handler.shownCode)
        assertEquals(emptyList<String>(), handler.protocolFailures)

        // Left running, the timer would fire attempt_timeout two minutes on.
        handler.clearEvents()
        handler.scope.advanceTimeBy(SendSpinProtocol.PAIR_ATTEMPT_TIMEOUT_MS * 2)
        handler.scope.runCurrent()
        assertEquals(emptyList<String>(), handler.events)
    }

    @Test
    fun `the connection survives a mismatch and pairs on the next activation`() {
        val handler = mismatchedAttempt()
        handler.clearEvents()

        // In flight from before the server saw the abort: discarded.
        handler.receive(serverPairInit())
        assertEquals(emptyList<String>(), handler.protocolFailures)
        assertNull(handler.shownCode)

        handler.receive(pairingActivateJson(dynamic))
        assertEquals(listOf(2), handler.pairInitIndexes())
        handler.receive(serverPairInit())
        assertNotNull("the new attempt shows its own code", handler.shownCode)
        assertFalse(handler.events.contains("close"))
    }

    // ========== Local cancel ==========

    @Test
    fun `cancelling a dynamic attempt sends user_cancelled and clears the code`() {
        val handler = handler(PskCategory.SENTINEL)
        handler.receive(pairingActivateJson(dynamic))
        handler.receive(serverPairInit())
        handler.clearEvents()

        handler.cancelPairing()
        handler.scope.runCurrent()

        assertEquals(listOf("user_cancelled"), handler.abortReasons())
        assertNull(handler.shownCode)
        assertTrue("the connection stays open", handler.events.none { it == "close" })

        handler.clearEvents()
        handler.scope.advanceTimeBy(SendSpinProtocol.PAIR_ATTEMPT_TIMEOUT_MS * 2)
        handler.scope.runCurrent()
        assertEquals(emptyList<String>(), handler.events)
    }

    private companion object {
        /** A valid, non-low-order CPace share (draft-irtf-cfrg-cpace B.1.10, u6). */
        val VALID_SHARE = ByteArray(32) { 0xff.toByte() }.also { it[0] = 0xda.toByte() }
    }
}
