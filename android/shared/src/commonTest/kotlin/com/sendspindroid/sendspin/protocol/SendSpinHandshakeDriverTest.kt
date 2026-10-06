package com.sendspindroid.sendspin.protocol

import com.sendspindroid.sendspin.crypto.Base64Url
import com.sendspindroid.sendspin.crypto.ClientIdentity
import com.sendspindroid.sendspin.crypto.NoiseHandshake
import com.sendspindroid.sendspin.crypto.NoiseHandshakeException
import com.sendspindroid.sendspin.crypto.Psk
import com.sendspindroid.sendspin.crypto.PskCandidateSet
import com.sendspindroid.sendspin.crypto.PskCategory
import com.sendspindroid.sendspin.crypto.SentinelPsk
import com.sendspindroid.sendspin.protocol.message.InitMessages
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * Drives the cleartext handshake against a transcript produced by
 * `noiseprotocol` acting as the server. See [WireTestVectors].
 *
 * The load-bearing assertion is [driverReproducesTheReferenceNoiseMessage2]: it
 * only passes if the prologue was built from the exact bytes of `client/init`
 * and `server/init`. The recorded `server/init` orders its fields differently
 * from the client's and carries an unknown key, so any implementation that
 * parses and re-encodes it produces a different prologue and fails.
 */
class SendSpinHandshakeDriverTest {

    private fun hex(s: String) = ByteArray(s.length / 2) {
        s.substring(it * 2, it * 2 + 2).toInt(16).toByte()
    }

    private fun ByteArray.hex() = joinToString("") {
        (it.toInt() and 0xFF).toString(16).padStart(2, '0')
    }

    private val psk = Psk(hex(WireTestVectors.psk), PskCategory.LONG_TERM, WireTestVectors.serverId)
    private val candidates = PskCandidateSet(listOf(psk))

    private class Recorder {
        val sent = mutableListOf<String>()
        val failures = mutableListOf<Pair<NoiseHandshakeException.Cause, String>>()
        var ready: SendSpinHandshakeDriver.Event.TransportReady? = null

        fun handle(e: SendSpinHandshakeDriver.Event) {
            when (e) {
                is SendSpinHandshakeDriver.Event.SendCleartext -> sent += e.text
                is SendSpinHandshakeDriver.Event.Fail -> failures += e.reason to e.detail
                is SendSpinHandshakeDriver.Event.TransportReady -> ready = e
            }
        }
    }

    /**
     * A driver whose ephemeral is pinned to the transcript's, so message 2 is
     * byte-reproducible. Mirrors the production path exactly apart from that.
     */
    private fun driverWith(recorder: Recorder): SendSpinHandshakeDriver =
        SendSpinHandshakeDriver(
            identity = ClientIdentity(hex(WireTestVectors.clientStaticPrivate)),
            candidates = candidates,
            onEvent = recorder::handle,
            ephemeralOverrideForTest = { hex(WireTestVectors.clientEphemeralPrivate) },
        )

    private fun runHandshake(recorder: Recorder): SendSpinHandshakeDriver {
        val driver = driverWith(recorder)
        driver.start()
        driver.onCleartextFrame(WireTestVectors.serverInitFrame.encodeToByteArray())
        driver.onCleartextFrame(WireTestVectors.noiseHandshake1Frame.encodeToByteArray())
        return driver
    }

    @Test
    fun builderReproducesTheReferenceClientInitByteForByte() {
        // If this fails, every prologue below is built from different bytes than
        // the transcript assumed - regenerate the vectors.
        assertEquals(
            WireTestVectors.clientInitFrame,
            InitMessages.buildClientInit(WireTestVectors.clientId, WireTestVectors.suiteWireName),
        )
    }

    @Test
    fun startEmitsClientInitFirst() {
        val r = Recorder()
        driverWith(r).start()
        assertEquals(1, r.sent.size)
        assertEquals(WireTestVectors.clientInitFrame, r.sent[0])
    }

    @Test
    fun driverReproducesTheReferenceNoiseMessage2() {
        // The prologue test. Passing means client/init and server/init were both
        // hashed as raw bytes.
        val r = Recorder()
        runHandshake(r)
        assertTrue(r.failures.isEmpty(), "unexpected failure: ${r.failures}")
        assertEquals(2, r.sent.size)
        val expected = InitMessages.buildNoiseHandshake(WireTestVectors.noiseMessage2B64u)
        assertEquals(expected, r.sent[1])
    }

    @Test
    fun handshakeHashAgreesWithTheReference() {
        val r = Recorder()
        runHandshake(r)
        val ready = assertNotNull(r.ready)
        assertEquals(WireTestVectors.handshakeHash, ready.transport.handshakeHash.hex())
        assertEquals(WireTestVectors.serverId, ready.serverInit.serverId)
        assertEquals(WireTestVectors.pskId, ready.matchedPsk.pskId)
        assertNull(ready.lookupMiss)
    }

    @Test
    fun reEncodingServerInitBreaksTheHandshake() {
        // Proves the transcript actually detects the mistake it was built to
        // detect: feed a semantically identical server/init whose bytes differ
        // (field order normalised, unknown key dropped) and message 2 diverges.
        val reEncoded = InitMessages.let {
            """{"type":"server/init","payload":{"server_id":"${WireTestVectors.serverId}","version":1}}"""
        }
        val r = Recorder()
        val driver = driverWith(r)
        driver.start()
        driver.onCleartextFrame(reEncoded.encodeToByteArray())
        driver.onCleartextFrame(WireTestVectors.noiseHandshake1Frame.encodeToByteArray())
        // message 1 no longer authenticates under the wrong prologue.
        assertTrue(r.failures.isNotEmpty(), "a re-encoded server/init must fail")
        assertEquals(NoiseHandshakeException.Cause.AeadFailure, r.failures[0].first)
        assertEquals(SendSpinHandshakeDriver.Phase.Failed, driver.phase)
    }

    @Test
    fun transportCarriesApplicationMessagesBothWays() = runTest {
        val r = Recorder()
        runHandshake(r)
        val transport = assertNotNull(r.ready).transport
        val codec = NoiseWireCodec(transport)

        val inbound = codec.decode(hex(WireTestVectors.appFrameServerToClient))
        val json = assertTrue(inbound is NoiseWireCodec.Decoded.Json).let { inbound as NoiseWireCodec.Decoded.Json }
        // The recorded plaintext includes the leading type byte; the codec strips it.
        assertEquals(
            WireTestVectors.appFrameServerToClientPlaintext.substring(1),
            json.text,
        )

        val outbound = codec.encodeJson(
            WireTestVectors.appFrameClientToServerPlaintext.substring(1)
        )
        assertEquals(1, outbound.size)
        assertEquals(WireTestVectors.appFrameClientToServer, outbound[0].hex())
    }

    /** Run the reference handshake holding [candidates] instead of the transcript's PSK. */
    private fun handshakeWith(
        candidates: PskCandidateSet,
        message1Frame: String = WireTestVectors.noiseHandshake1Frame,
    ): Recorder {
        val r = Recorder()
        val driver = SendSpinHandshakeDriver(
            identity = ClientIdentity(hex(WireTestVectors.clientStaticPrivate)),
            candidates = candidates,
            onEvent = r::handle,
            ephemeralOverrideForTest = { hex(WireTestVectors.clientEphemeralPrivate) },
        )
        driver.start()
        driver.onCleartextFrame(WireTestVectors.serverInitFrame.encodeToByteArray())
        driver.onCleartextFrame(message1Frame.encodeToByteArray())
        return r
    }

    @Test
    fun aLookupMissCompletesTheHandshakeWithTheSentinel() {
        // connection.md, Sentinel Fallback: "On a lookup miss in the initial
        // handshake the client completes the second handshake message with
        // the Sentinel PSK instead of failing."
        val lostRecord = handshakeWith(PskCandidateSet.sentinelOnly())

        assertTrue(lostRecord.failures.isEmpty(), "unexpected failure: ${lostRecord.failures}")
        val ready = assertNotNull(lostRecord.ready)
        assertEquals(PskCategory.SENTINEL, ready.matchedPsk.category)
        assertEquals(SentinelPsk.psk.pskId, ready.matchedPsk.pskId)
        assertTrue(WireTestVectors.pskId in assertNotNull(ready.lookupMiss))
        assertEquals(2, lostRecord.sent.size, "client/init, then Noise message 2")

        // Message 2 is keyed by the Sentinel whatever else the client holds:
        // a stale record for another PSK produces the very same bytes, and
        // they are not the bytes the referenced PSK would have produced.
        val staleRecord = handshakeWith(
            PskCandidateSet(
                listOf(
                    Psk(ByteArray(32) { 7 }, PskCategory.LONG_TERM, WireTestVectors.serverId),
                    SentinelPsk.psk,
                )
            )
        )
        assertEquals(lostRecord.sent[1], staleRecord.sent[1])
        assertTrue(
            lostRecord.sent[1] != InitMessages.buildNoiseHandshake(WireTestVectors.noiseMessage2B64u)
        )
    }

    /** Run the handshake with [message1Frame] in place of the reference message 1. */
    private fun failureFor(
        message1Frame: String,
        candidates: PskCandidateSet = this.candidates,
    ): Recorder {
        val r = Recorder()
        val driver = SendSpinHandshakeDriver(
            identity = ClientIdentity(hex(WireTestVectors.clientStaticPrivate)),
            candidates = candidates,
            onEvent = r::handle,
            ephemeralOverrideForTest = { hex(WireTestVectors.clientEphemeralPrivate) },
        )
        driver.start()
        driver.onCleartextFrame(WireTestVectors.serverInitFrame.encodeToByteArray())
        driver.onCleartextFrame(message1Frame.encodeToByteArray())
        assertEquals(SendSpinHandshakeDriver.Phase.Failed, driver.phase)
        // A silent failure: nothing after our own client/init was sent.
        assertEquals(1, r.sent.size, "must not reply to a refused message 1")
        assertNull(r.ready)
        return r
    }

    @Test
    fun aMalformedInnerPayloadIsASilentFailure() {
        // messaging.md: "A malformed inner handshake payload (not valid UTF-8
        // JSON of the shape above, including a psk_category outside the three
        // defined codes) is a silent failure and closes the WebSocket."
        val malformed = mapOf(
            "no psk_category" to WireTestVectors.noiseHandshake1NoCategoryFrame,
            "psk_category outside lt/pr/sn" to WireTestVectors.noiseHandshake1UnknownCategoryFrame,
            "no psk_id" to WireTestVectors.noiseHandshake1NoPskIdFrame,
            "not JSON" to WireTestVectors.noiseHandshake1NotJsonFrame,
            "invalid UTF-8" to WireTestVectors.noiseHandshake1InvalidUtf8Frame,
        )
        for ((what, frame) in malformed) {
            val r = failureFor(frame)
            assertEquals(
                NoiseHandshakeException.Cause.PayloadNotJson,
                r.failures.single().first,
                "inner payload with $what",
            )
        }
    }

    @Test
    fun aPskHeldUnderAnotherCategoryIsALookupMiss() {
        // "The client holds the referenced PSK under a different category
        // than the declared psk_category" is a miss, so it must never be
        // mixed in: the session falls back to the Sentinel. The transcript's
        // PSK is a long-term record here, declared as the pairing PSK.
        val r = handshakeWith(candidates, WireTestVectors.noiseHandshake1PairingCategoryFrame)
        assertEquals(PskCategory.SENTINEL, assertNotNull(r.ready).matchedPsk.category)

        // And the reverse: held as the pairing PSK, declared long-term.
        val asPairing = PskCandidateSet(listOf(Psk(hex(WireTestVectors.psk), PskCategory.PAIRING)))
        val reverse = handshakeWith(asPairing)
        assertEquals(PskCategory.SENTINEL, assertNotNull(reverse.ready).matchedPsk.category)

        // Held under the declared category, that same frame matches it.
        val accepted = handshakeWith(asPairing, WireTestVectors.noiseHandshake1PairingCategoryFrame)
        assertEquals(PskCategory.PAIRING, assertNotNull(accepted.ready).matchedPsk.category)
    }

    @Test
    fun aRecordBoundToAnotherServerIsRejected() {
        val wrongBinding = Psk(hex(WireTestVectors.psk), PskCategory.LONG_TERM, "some-other-server")
        val r = Recorder()
        val driver = SendSpinHandshakeDriver(
            identity = ClientIdentity(hex(WireTestVectors.clientStaticPrivate)),
            candidates = PskCandidateSet(listOf(wrongBinding)),
            onEvent = r::handle,
            ephemeralOverrideForTest = { hex(WireTestVectors.clientEphemeralPrivate) },
        )
        driver.start()
        driver.onCleartextFrame(WireTestVectors.serverInitFrame.encodeToByteArray())
        driver.onCleartextFrame(WireTestVectors.noiseHandshake1Frame.encodeToByteArray())
        assertEquals(NoiseHandshakeException.Cause.PskLookupMiss, r.failures.single().first)
    }

    @Test
    fun aTextFrameAfterTransportModeIsRejected() {
        // "all messages are sent as WebSocket binary frames" once the handshake
        // completes; a text frame afterwards is a downgrade attempt or a bug.
        val r = Recorder()
        val driver = runHandshake(r)
        assertEquals(SendSpinHandshakeDriver.Phase.Transport, driver.phase)
        driver.onCleartextFrame("""{"type":"server/hello"}""".encodeToByteArray())
        assertEquals(NoiseHandshakeException.Cause.MalformedMessage, r.failures.single().first)
    }

    @Test
    fun aBinaryFrameDuringTheHandshakeIsRejected() {
        val r = Recorder()
        val driver = driverWith(r)
        driver.start()
        driver.onBinaryFrameBeforeTransport()
        assertEquals(NoiseHandshakeException.Cause.MalformedMessage, r.failures.single().first)
        assertEquals(SendSpinHandshakeDriver.Phase.Failed, driver.phase)
    }

    @Test
    fun outOfOrderAndMalformedFramesFailWithoutSendingAnything() {
        val cases = listOf(
            "not json at all",
            """{"payload":{}}""",
            """{"type":"server/hello","payload":{}}""",
            """{"type":"server/init","payload":{"version":2,"server_id":"${WireTestVectors.serverId}"}}""",
            """{"type":"server/init","payload":{"version":1,"server_id":"too-short"}}""",
        )
        for (frame in cases) {
            val r = Recorder()
            val driver = driverWith(r)
            driver.start()
            driver.onCleartextFrame(frame.encodeToByteArray())
            assertTrue(r.failures.isNotEmpty(), "should have failed: $frame")
            // Exactly one frame was ever sent: our own client/init.
            assertEquals(1, r.sent.size, "must not reply to a bad frame: $frame")
            assertEquals(SendSpinHandshakeDriver.Phase.Failed, driver.phase)
        }
    }

    @Test
    fun aServerHelloReplyIsReportedAsLackingEncryption() {
        // A server predating mandatory encryption (spec #84) answers client/init
        // with a server/hello. Nothing is malformed - it is a well-formed
        // message in an older dialect - and it is the only handshake failure the
        // user can act on, so it must be distinguishable from the crypto
        // failures for the app to say "upgrade your server" rather than
        // "could not connect".
        val r = Recorder()
        val driver = driverWith(r)
        driver.start()
        driver.onCleartextFrame(
            """{"type":"server/hello","payload":{"name":"Music Assistant"}}""".encodeToByteArray()
        )
        assertEquals(
            NoiseHandshakeException.Cause.ServerLacksEncryption,
            r.failures.single().first
        )
        assertEquals(SendSpinHandshakeDriver.Phase.Failed, driver.phase)
        // Never reply to it: only our own client/init was ever sent.
        assertEquals(1, r.sent.size)
    }

    @Test
    fun aServerErrorIsReportedWithItsReason() {
        // messaging.md, server/error: "Sent by the server in place of
        // server/init when it cannot accept the client's client/init." It is
        // a server that does speak the encrypted protocol, so it must not be
        // reported as one that lacks it.
        for (reason in listOf("unsupported_version", "unsupported_suite", "malformed")) {
            val r = Recorder()
            val driver = driverWith(r)
            driver.start()
            driver.onCleartextFrame(
                """{"type":"server/error","payload":{"reason":"$reason"}}""".encodeToByteArray()
            )
            val (cause, detail) = r.failures.single()
            assertEquals(NoiseHandshakeException.Cause.InitRejected, cause)
            assertTrue(reason in detail, "the reason must reach the log: $detail")
            assertEquals(SendSpinHandshakeDriver.Phase.Failed, driver.phase)
            assertEquals(1, r.sent.size, "must not reply to server/error")
        }
    }

    @Test
    fun timeoutFailsBeforeTransportAndIsIgnoredAfter() {
        val r = Recorder()
        val driver = driverWith(r)
        driver.start()
        driver.onTimeout()
        assertEquals(NoiseHandshakeException.Cause.Timeout, r.failures.single().first)

        val r2 = Recorder()
        val done = runHandshake(r2)
        assertEquals(SendSpinHandshakeDriver.Phase.Transport, done.phase)
        done.onTimeout()
        assertTrue(r2.failures.isEmpty(), "a completed handshake must ignore the watchdog")
    }
}
