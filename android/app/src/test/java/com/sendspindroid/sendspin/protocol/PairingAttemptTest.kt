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

    /** After `client/pair-retry`, `server/pair-init` "is sent without" a nonce. */
    private val serverPairInitNextRound = """{"type":"server/pair-init","payload":{}}"""

    private val serverPairFinalize = """{"type":"server/pair-finalize","payload":{}}"""

    /** One round whose code was entered wrong: the server's init, its share and a tag that fails. */
    private fun CrossTerminationTestHandler.failRound(first: Boolean) {
        receive(if (first) serverPairInit() else serverPairInitNextRound)
        receive(serverPairAuth())
        receive(serverPairConfirm())
    }

    /** A dynamic attempt driven up to a failed `server_kc` in its first round. */
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

    /** A dynamic attempt whose every round fails, through the last failure the round limit allows. */
    private fun attemptAtRoundLimit(): CrossTerminationTestHandler {
        val handler = handler(PskCategory.SENTINEL)
        handler.receive(pairingActivateJson(dynamic))
        repeat(ROUND_LIMIT - 1) { handler.failRound(first = it == 0) }
        handler.receive(serverPairInitNextRound)
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
        handler.receive("""{"type":"server/activate","payload":{"activities":[],"active_roles":[]}}""")

        handler.receive(serverPairInit())

        assertEquals(1, handler.protocolFailures.size)
    }

    // ========== Rounds ==========

    @Test
    fun `a wrong code asks for another round and keeps showing the code`() {
        // pairing.md "Rounds": "The client SHOULD retry", and "A retry keeps
        // the attempt, its pairing code, and its running attempt timeout in
        // place".
        val handler = handler(PskCategory.SENTINEL)
        handler.receive(pairingActivateJson(dynamic))
        handler.receive(serverPairInit())
        val code = handler.shownCode
        handler.receive(serverPairAuth())
        handler.clearEvents()

        handler.receive(serverPairConfirm())

        assertEquals(listOf("send:client/pair-retry"), handler.pairingEvents())
        assertEquals("{}", handler.sentPayloads("client/pair-retry").single().toString())
        assertEquals(code, handler.shownCode)
        assertEquals(emptyList<String>(), handler.protocolFailures)
    }

    @Test
    fun `the next round's server pair-init without a nonce shows the same code and runs the round`() {
        val handler = handler(PskCategory.SENTINEL)
        handler.receive(pairingActivateJson(dynamic))
        handler.receive(serverPairInit())
        val code = handler.shownCode
        handler.receive(serverPairAuth())
        handler.receive(serverPairConfirm())
        handler.clearEvents()

        handler.receive(serverPairInitNextRound)
        handler.receive(serverPairAuth())

        assertEquals(emptyList<String>(), handler.protocolFailures)
        assertEquals(code, handler.shownCode)
        assertEquals(listOf("send:client/pair-auth"), handler.pairingEvents())
    }

    @Test
    fun `a nonce in the next round's server pair-init is a protocol error`() {
        // nonce_A: "Present in the first round only."
        val handler = mismatchedAttempt()

        handler.receive(serverPairInit())

        assertEquals(1, handler.protocolFailures.size)
    }

    @Test
    fun `a retry keeps the attempt timer running from client pair-init`() {
        val handler = handler(PskCategory.SENTINEL)
        handler.receive(pairingActivateJson(dynamic))
        handler.scope.advanceTimeBy(SendSpinProtocol.PAIR_ATTEMPT_TIMEOUT_MS / 2)
        handler.failRound(first = true)
        handler.clearEvents()

        handler.scope.advanceTimeBy(SendSpinProtocol.PAIR_ATTEMPT_TIMEOUT_MS / 2 - 1)
        handler.scope.runCurrent()
        assertEquals("not restarted by the retry, nor yet due", emptyList<String>(), handler.abortReasons())

        handler.scope.advanceTimeBy(1)
        handler.scope.runCurrent()
        assertEquals(listOf("attempt_timeout"), handler.abortReasons())
        assertNull("the code must leave the screen", handler.shownCode)
    }

    @Test
    fun `at the round limit a wrong code aborts, stops showing the code and cancels the attempt timer`() {
        // "The client SHOULD retry, and MUST abort instead at the round limit."
        val handler = attemptAtRoundLimit()

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
    fun `after the round limit the next attempt is held back until Allow pairing, which resets the count`() {
        // "hold attempts back until a deliberate, manufacturer-defined
        // operator action, which also resets the count."
        val handler = attemptAtRoundLimit()
        handler.clearEvents()

        // In flight from before the server saw the abort: discarded.
        handler.receive(serverPairInitNextRound)
        assertEquals(emptyList<String>(), handler.protocolFailures)

        handler.receive(pairingActivateJson(dynamic))
        assertEquals(listOf("send:client/pair-pending"), handler.pairingEvents())
        assertNull(handler.shownCode)

        handler.clearEvents()
        handler.confirmDynamicPairingGesture()
        handler.scope.runCurrent()
        assertEquals(listOf(2), handler.pairInitIndexes())

        // The count starts again: a wrong code is retried, not aborted.
        handler.receive(serverPairInit())
        assertNotNull("the new attempt shows its own code", handler.shownCode)
        handler.receive(serverPairAuth())
        handler.clearEvents()
        handler.receive(serverPairConfirm())
        assertEquals(listOf("send:client/pair-retry"), handler.pairingEvents())
        assertFalse(handler.events.contains("close"))
    }

    // ========== Abandoned by the server ==========

    private val leavePairing = """{"type":"server/activate","payload":{"activities":[],"active_roles":[]}}"""

    @Test
    fun `an activation that ends a dynamic attempt abandons it and clears the code`() {
        val handler = handler(PskCategory.SENTINEL)
        handler.receive(pairingActivateJson(dynamic))
        handler.receive(serverPairInit())
        handler.clearEvents()

        handler.receive(leavePairing)

        assertEquals(1, handler.abandonments)
        assertNull("the code must leave the screen", handler.shownCode)
        // The server ended it: nothing is sent back.
        assertEquals(emptyList<String>(), handler.pairingEvents())
    }

    @Test
    fun `a pairing activation supersedes the attempt without abandoning pairing`() {
        val handler = handler(PskCategory.SENTINEL)
        handler.receive(pairingActivateJson(dynamic))
        handler.receive(serverPairInit())

        handler.receive(pairingActivateJson(dynamic))

        assertEquals(0, handler.abandonments)
    }

    @Test
    fun `an activation while waiting for the next round abandons the attempt`() {
        // The retry kept the attempt in progress.
        val handler = mismatchedAttempt()

        handler.receive(leavePairing)

        assertEquals(1, handler.abandonments)
        assertNull("the code must leave the screen", handler.shownCode)
    }

    @Test
    fun `an activation that ends a wait for the gesture abandons it`() {
        // At the round limit the next attempt waits for the operator's gesture.
        val handler = attemptAtRoundLimit()
        handler.clearEvents()
        handler.receive(pairingActivateJson(dynamic))
        assertEquals(listOf("send:client/pair-pending"), handler.pairingEvents())

        handler.receive(leavePairing)

        assertEquals(1, handler.abandonments)
    }

    @Test
    fun `an activation after our own abort of a dynamic attempt does not abandon it`() {
        // The abort already ended the attempt and said why: "Too many wrong
        // codes were entered on ..." must not be replaced.
        val handler = attemptAtRoundLimit()

        handler.receive(leavePairing)

        assertEquals(0, handler.abandonments)
    }

    @Test
    fun `an activation after the server's abort of a dynamic attempt does not abandon it`() {
        val handler = handler(PskCategory.SENTINEL)
        handler.receive(pairingActivateJson(dynamic))
        handler.receive(serverPairInit())
        handler.receive("""{"type":"pair/abort","payload":{"reason":"user_cancelled"}}""")

        handler.receive(leavePairing)

        assertEquals(0, handler.abandonments)
    }

    @Test
    fun `a dynamic attempt is abandoned once, not again by a later activation`() {
        val handler = handler(PskCategory.SENTINEL)
        handler.receive(pairingActivateJson(dynamic))
        handler.receive(serverPairInit())
        handler.receive(leavePairing)

        handler.receive(leavePairing)

        assertEquals(1, handler.abandonments)
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

        /** pairing.md "Rounds": "After 20 rounds since its last verified `server_kc` - the round limit". */
        const val ROUND_LIMIT = 20
    }
}
