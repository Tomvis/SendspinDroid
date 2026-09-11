package com.sendspindroid.sendspin.protocol

import com.sendspindroid.sendspin.SendspinTimeFilter
import com.sendspindroid.sendspin.crypto.NoiseCipherSuite
import com.sendspindroid.sendspin.crypto.Psk
import com.sendspindroid.sendspin.crypto.PskCategory
import com.sendspindroid.sendspin.crypto.TrustStore
import com.sendspindroid.sendspin.pairing.PairingCounterStore
import com.sendspindroid.sendspin.protocol.message.MessageBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for two device-acceptance-only defects in the dynamic
 * pairing flow (found against a live server, invisible to unit tests):
 *
 * D1: `client/time` racing a pairing activation. The server rejects it as a
 * malformed `client/pair-auth` because it is sitting in `_receive_pairing`
 * waiting for that exact message.
 *
 * D2: `pairing_index` (pairing.md line 335: "the number of pairing
 * server/activate messages received since the last Noise handshake") was
 * wrong in two ways - not reset on a FRESH handshake, and only incremented
 * for the dynamic method rather than every pairing method.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DynamicPairingDeviceFixesTest {

    private fun activateJson(activities: String, method: String? = null) =
        if (method == null) {
            """{"type":"server/activate","payload":{"activities":[$activities]}}"""
        } else {
            """{"type":"server/activate","payload":{"activities":[$activities],"pairing":{"method":"$method"}}}"""
        }

    // ========== D1: time sync vs. pairing ==========

    @Test
    fun `a pairing activation stops time sync and sends no player state`() {
        val handler = DeviceFixTestHandler()
        handler.matchedCategory = PskCategory.SENTINEL

        // Non-pairing activation first: time sync running, state reported.
        handler.handleTextMessageForTest(activateJson("\"playback\""))
        assertTrue("time sync should be running before pairing", handler.timeSyncManagerForTest()!!.isRunning)
        assertTrue(
            "player state should have been sent",
            handler.sent.any { it.contains("\"type\":\"client/state\"") }
        )
        handler.sent.clear()

        // Pairing activation: server is waiting for client/pair-finalize and
        // nothing else - a client/time burst here is read as that message.
        handler.handleTextMessageForTest(activateJson("\"pairing\"", "dynamic_pairing_code"))

        assertFalse(
            "time sync must be stopped while pairing",
            handler.timeSyncManagerForTest()!!.isRunning
        )
        assertTrue(
            "no player state should be sent while pairing",
            handler.sent.none { it.contains("\"type\":\"client/state\"") }
        )
    }

    @Test
    fun `leaving pairing restores time sync and state even though it is not the first activation`() {
        val handler = DeviceFixTestHandler()
        handler.matchedCategory = PskCategory.SENTINEL

        // First activation: empty activities. Time sync starts.
        handler.handleTextMessageForTest(activateJson(""))
        assertTrue(handler.timeSyncManagerForTest()!!.isRunning)

        // Second activation: enters pairing. Time sync stops.
        handler.handleTextMessageForTest(activateJson("\"pairing\"", "dynamic_pairing_code"))
        assertFalse(handler.timeSyncManagerForTest()!!.isRunning)
        handler.sent.clear()

        // Third activation: leaves pairing again. This is NOT the first
        // activation on the connection - the bug under test is a `first &&`
        // condition that would leave time sync and state reporting dead here.
        handler.handleTextMessageForTest(activateJson("\"playback\""))

        assertTrue(
            "time sync must restart on a later non-pairing activation",
            handler.timeSyncManagerForTest()!!.isRunning
        )
        assertTrue(
            "player state must be reported again",
            handler.sent.any { it.contains("\"type\":\"client/state\"") }
        )
    }

    // ========== D2a: pairing_index reset on a FRESH handshake ==========

    @Test
    fun `pairing_index resets on a fresh handshake, not only a re-handshake`() {
        val handler = DeviceFixTestHandler()
        handler.matchedCategory = PskCategory.SENTINEL
        handler.offeredMethods = setOf("dynamic_pairing_code")

        // One dynamic pairing activation on the first connection - index 1.
        handler.handleTextMessageForTest(activateJson("\"pairing\"", "dynamic_pairing_code"))
        val firstIndex = handler.lastPairInitIndex()
        assertEquals(1, firstIndex)

        // A fresh Noise handshake completes on a reconnect - the SAME
        // SendSpinProtocolHandler instance is reused, as it is by SendSpin.kt
        // across reconnects. installEncryptedTransport() itself needs a real
        // NoiseTransport (internal to the shared module, unreachable from an
        // app-module test without driving an actual handshake), so this
        // calls the same reset it performs - resetPairingIndexForFreshHandshake()
        // - directly, exercising the exact code path installEncryptedTransport
        // delegates to.
        handler.resetPairingIndexForTest()

        // A dynamic pairing activation on the NEW connection must again be
        // index 1, matching the server's own fresh count - not 2, which is
        // what an unreset counter would produce.
        handler.handleTextMessageForTest(activateJson("\"pairing\"", "dynamic_pairing_code"))
        assertEquals(
            "pairing_index must restart at 1 after a fresh handshake",
            1,
            handler.lastPairInitIndex()
        )
    }

    // ========== D2b: pairing_index counts every pairing method ==========

    @Test
    fun `a pairing_psk activation advances the same counter a later dynamic activation reads`() {
        val handler = DeviceFixTestHandler()
        handler.offeredMethods = setOf("pairing_psk", "dynamic_pairing_code")

        // First activation: Pairing PSK. Does not touch the dynamic flow
        // directly, but must still advance the shared pairing_index tally.
        handler.matchedCategory = PskCategory.PAIRING
        handler.handleTextMessageForTest(activateJson("\"pairing\"", "pairing_psk"))

        // Second activation: server switches to the dynamic method (e.g.
        // after an in-band re-handshake promotes the connection).
        handler.matchedCategory = PskCategory.SENTINEL
        handler.handleTextMessageForTest(activateJson("\"pairing\"", "dynamic_pairing_code"))

        // The server counted BOTH activations, so its count is 2 - our
        // pairing_index must match, not fall behind at 1 (which the spec
        // says is silently discarded, meaning the attempt would never start).
        assertEquals(
            "pairing_index must count the earlier pairing_psk activation too",
            2,
            handler.lastPairInitIndex()
        )
    }
}

/**
 * Test handler covering the seams needed by D1/D2: a live [TimeSyncManager]
 * to observe start/stop, a switchable matched PSK category and offered
 * methods to admit either pairing method, and a way to simulate a fresh
 * Noise handshake completing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DeviceFixTestHandler : SendSpinProtocolHandler("DeviceFixTest") {

    var matchedCategory: PskCategory = PskCategory.SENTINEL
    var offeredMethods: Set<String> = setOf("pairing_psk", "dynamic_pairing_code")

    val scope = TestScope()
    val sent = mutableListOf<String>()

    private val timeFilter = SendspinTimeFilter()
    private val counterStore = InMemoryPairingCounterStore()
    private val store = RecordingTrustStore()
    private val handshakeHash = ByteArray(32) { 7 }

    init {
        handshakeComplete = true
        initTimeSyncManager(timeFilter)
    }

    fun handleTextMessageForTest(text: String) = handleTextMessage(text)

    fun timeSyncManagerForTest() = timeSyncManager

    /** Exposes the protected reset so the test can simulate a fresh handshake. */
    fun resetPairingIndexForTest() = resetPairingIndexForFreshHandshake()

    /** Last `pairing_index` sent on a `client/pair-init`. */
    fun lastPairInitIndex(): Int? = sent
        .map { Json.parseToJsonElement(it).jsonObject }
        .lastOrNull { it["type"]?.jsonPrimitive?.content == "client/pair-init" }
        ?.get("payload")?.jsonObject?.get("pairing_index")?.jsonPrimitive?.content?.toInt()

    override fun offeredPairMethods(): Set<String> = offeredMethods

    override fun matchedPskCategory(): PskCategory = matchedCategory

    override fun matchedPsk(): Psk? = Psk(ByteArray(32) { 3 }, matchedCategory)

    override fun trustStore(): TrustStore = store

    override fun currentServerId(): String = "srv1"

    override fun currentHandshakeHash(): ByteArray = handshakeHash

    override fun pairingCounterStore(): PairingCounterStore = counterStore

    override fun negotiatedCipherSuite(): NoiseCipherSuite = NoiseCipherSuite.CHACHA_POLY

    override fun closeConnectionAfterFlush() = Unit

    override fun sendTextMessage(text: String) {
        sent.add(text)
    }

    override fun sendBinaryFrame(bytes: ByteArray) = Unit

    override fun getCoroutineScope(): CoroutineScope = scope

    override fun getTimeFilter(): SendspinTimeFilter = timeFilter

    override fun isLowMemoryMode(): Boolean = false
    override fun getClientId(): String = "test-client"
    override fun getDeviceName(): String = "Test"
    override fun getManufacturer(): String = "Test"
    override fun getSoftwareVersion(): String = "0.0.0"
    override fun getSupportedFormats(): List<MessageBuilder.FormatEntry> = emptyList()

    override fun onHandshakeComplete(serverName: String, serverId: String) = Unit
    override fun onMetadataUpdate(metadata: TrackMetadata) = Unit
    override fun onPlaybackStateChanged(state: String) = Unit
    override fun onVolumeCommand(volume: Int) = Unit
    override fun onMuteCommand(muted: Boolean) = Unit
    override fun onGroupUpdate(info: GroupInfo) = Unit
    override fun onStreamStart(config: StreamConfig) = Unit
    override fun onStreamClear() = Unit
    override fun onStreamEnd() = Unit
    override fun onAudioChunk(timestampMicros: Long, audioData: ByteArray) = Unit
    override fun onArtwork(channel: Int, payload: ByteArray) = Unit
    override fun onSyncOffsetApplied(offsetMs: Double, source: String) = Unit
    override fun onSyncMuteChanged(muted: Boolean) = Unit
}
