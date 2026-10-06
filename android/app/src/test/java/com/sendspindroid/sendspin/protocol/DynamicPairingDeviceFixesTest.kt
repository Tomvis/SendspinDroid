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
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pairing alongside the rest of the connection, and the `pairing_index` tally.
 *
 * D1: pairing does not quiesce the connection. pairing.md, "Entering and
 * leaving pairing": "Pairing can run alongside playback. A server/activate
 * that adds 'pairing' to activities does not by itself affect active_roles,
 * streams, or group membership." So the clock stays synchronized and
 * client/state keeps being reported through a pairing activation. (Before
 * 1.0.0-rc1 the two were mutually exclusive and this client stopped both.)
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
            // `format` is required for the dynamic method and absent otherwise.
            """{"type":"server/activate","payload":{"activities":[$activities],"pairing":{"method":"$method"""" +
                (if (method == "dynamic_pairing_code") ""","format":"digits"""" else "") + "}}}"
        }

    // ========== D1: pairing runs alongside playback ==========

    private val playbackRoles = "\"active_roles\":[\"player@v1\"]"

    @Test
    fun `a pairing activation keeps time sync running and still reports state`() {
        val handler = DeviceFixTestHandler()
        handler.matchedCategory = PskCategory.SENTINEL

        handler.handleTextMessageForTest(
            """{"type":"server/activate","payload":{"activities":["playback"],$playbackRoles}}"""
        )
        assertTrue("time sync should be running", handler.timeSyncManagerForTest()!!.isRunning)
        assertTrue(
            "player state should have been sent",
            handler.sent.any { it.contains("\"type\":\"client/state\"") }
        )
        handler.sent.clear()

        // Pairing joins playback; active_roles is omitted and so persists.
        handler.handleTextMessageForTest(activateJson("\"playback\",\"pairing\"", "dynamic_pairing_code"))

        assertTrue(
            "time sync must keep running while pairing",
            handler.timeSyncManagerForTest()!!.isRunning
        )
        val state = handler.sent.single { it.contains("\"type\":\"client/state\"") }
        assertTrue(
            "the player role is still active, so its state object is still reported",
            state.contains("\"player\":{")
        )
        assertTrue(
            "the pairing attempt starts alongside it",
            handler.sent.any { it.contains("\"type\":\"client/pair-init\"") }
        )
    }

    @Test
    fun `a pairing-only activation on an idle connection reports state too`() {
        val handler = DeviceFixTestHandler()
        handler.matchedCategory = PskCategory.SENTINEL

        // No roles were ever granted, so the state carries availability alone.
        handler.handleTextMessageForTest(activateJson("\"pairing\"", "dynamic_pairing_code"))

        assertTrue(handler.timeSyncManagerForTest()!!.isRunning)
        val state = handler.sent.single { it.contains("\"type\":\"client/state\"") }
        assertFalse("no player object for an inactive role", state.contains("\"player\""))
    }

    @Test
    fun `leaving pairing keeps time sync and state going`() {
        val handler = DeviceFixTestHandler()
        handler.matchedCategory = PskCategory.SENTINEL

        handler.handleTextMessageForTest(activateJson(""))
        handler.handleTextMessageForTest(activateJson("\"pairing\"", "dynamic_pairing_code"))
        handler.sent.clear()

        handler.handleTextMessageForTest(activateJson("\"playback\""))

        assertTrue(handler.timeSyncManagerForTest()!!.isRunning)
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

    val scope = CoroutineScope(UnconfinedTestDispatcher())
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

    init {
        installEncryptedChannel(PlaintextCrypto)
    }

    override fun sendBinaryFrame(bytes: ByteArray) {
        bytes.jsonFrameText()?.let { sent.add(it) }
    }

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
