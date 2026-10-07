package com.sendspindroid.sendspin.protocol

import com.sendspindroid.sendspin.SendspinTimeFilter
import com.sendspindroid.sendspin.crypto.PskCategory
import com.sendspindroid.sendspin.protocol.message.MessageBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for SendSpinProtocolHandler.
 *
 * Uses a concrete test subclass to exercise the abstract handler's
 * volume clamping, metadata dispatch, and sync state validation.
 */
class SendSpinProtocolHandlerTest {

    private lateinit var handler: TestProtocolHandler

    @Before
    fun setUp() {
        handler = TestProtocolHandler()
        // Mark handshake complete so sendPlayerStateUpdate doesn't short-circuit
        handler.setHandshakeCompleteForTest()
    }

    // ========== Volume Clamping Tests ==========

    @Test
    fun `setVolume clamps values above 1_0 to 100 percent`() {
        handler.setVolume(1.5)
        assertEquals(100, handler.exposedVolume())
    }

    @Test
    fun `setVolume clamps negative values to 0 percent`() {
        handler.setVolume(-0.1)
        assertEquals(0, handler.exposedVolume())
    }

    @Test
    fun `setVolume converts 0_5 to 50 percent`() {
        handler.setVolume(0.5)
        assertEquals(50, handler.exposedVolume())
    }

    @Test
    fun `setVolume converts 0_0 to 0 percent`() {
        handler.setVolume(0.0)
        assertEquals(0, handler.exposedVolume())
    }

    @Test
    fun `setVolume converts 1_0 to 100 percent`() {
        handler.setVolume(1.0)
        assertEquals(100, handler.exposedVolume())
    }

    // ========== Metadata Dispatch Tests ==========

    @Test
    fun `identical metadata fires onMetadataUpdate for every message`() {
        val metadata = buildServerStateJson(
            title = "Test Song",
            artist = "Test Artist",
            album = "Test Album"
        )

        // Send the same metadata twice - both should dispatch so that
        // playback_speed changes are never suppressed by position dedup
        handler.handleTextMessageForTest(metadata)
        handler.handleTextMessageForTest(metadata)

        assertEquals(
            "Every metadata message should fire callback regardless of content",
            2,
            handler.metadataUpdates.size
        )
    }

    @Test
    fun `different metadata fires onMetadataUpdate for each`() {
        val metadata1 = buildServerStateJson(
            title = "Song A",
            artist = "Artist A",
            album = "Album A"
        )
        val metadata2 = buildServerStateJson(
            title = "Song B",
            artist = "Artist B",
            album = "Album B"
        )

        handler.handleTextMessageForTest(metadata1)
        handler.handleTextMessageForTest(metadata2)

        assertEquals(
            "Different metadata should fire callback for each",
            2,
            handler.metadataUpdates.size
        )
        assertEquals("Song A", handler.metadataUpdates[0].title)
        assertEquals("Song B", handler.metadataUpdates[1].title)
    }

    // ========== External Source Tests ==========

    @Test
    fun `setExternalSource true reports available false`() {
        handler.sentMessages.clear()
        handler.setExternalSource(true)

        // The spec replaced the tri-state `state` string with a boolean (#115).
        // External-source takeover is now the ONLY thing available:false means,
        // so the wire assertion is on the boolean, not on a removed enum value.
        assertEquals("external_source", handler.exposedSyncState())
        assertEquals(1, handler.sentMessages.size)
        assertTrue(handler.sentMessages[0].contains("\"available\":false"))
        assertTrue(
            "the removed state string must not appear on the wire",
            !handler.sentMessages[0].contains("external_source"),
        )
    }

    @Test
    fun `evaluateAndPublishSyncState does not override external_source`() {
        handler.setExternalSource(true)
        handler.evaluateAndPublishSyncStateForTest()
        assertEquals("external_source", handler.exposedSyncState())
    }

    @Test
    fun `setExternalSource false recomputes filter-derived state`() {
        handler.setExternalSource(true)
        handler.setExternalSource(false)
        // Filter has no measurements in tests -> "error"
        assertEquals("error", handler.exposedSyncState())
    }

    @Test
    fun `setExternalSource is idempotent`() {
        handler.sentMessages.clear()
        handler.setExternalSource(true)
        handler.setExternalSource(true)
        assertEquals(1, handler.sentMessages.size)
    }

    // ========== Controller State Tests ==========

    @Test
    fun `controller state from server_state is published`() {
        handler.handleTextMessageForTest(
            """{"type":"server/state","payload":{"controller":{
                "supported_commands":["play","pause","volume"],
                "volume":60,"muted":false,"repeat":"off","shuffle":false}}}"""
        )

        val state = handler.controllerStateUpdates.single()
        assertEquals(60, state.volume)
        assertEquals(listOf("play", "pause", "volume"), state.supportedCommands)
        assertEquals("off", state.repeat)
    }

    @Test
    fun `unchanged controller state does not republish`() {
        val msg = """{"type":"server/state","payload":{"controller":{"volume":60}}}"""
        handler.handleTextMessageForTest(msg)
        handler.handleTextMessageForTest(msg)
        assertEquals(1, handler.controllerStateUpdates.size)
    }

    private fun activateRoles(roles: String) = handler.handleTextMessageForTest(
        """{"type":"server/activate","payload":{"activities":[],"active_roles":[$roles]}}"""
    )

    private fun controllerState(fields: String) = handler.handleTextMessageForTest(
        """{"type":"server/state","payload":{"controller":{$fields}}}"""
    )

    private fun sentCommands() = handler.sentMessages.filter { it.contains("client/command") }

    @Test
    fun `sendCommand drops commands outside server supported_commands`() {
        activateRoles("\"player@v1\",\"controller@v1\"")
        controllerState(
            """"supported_commands":["play","pause"],
                "volume":60,"muted":false,"repeat":"off","shuffle":false"""
        )

        handler.sendCommand("shuffle")
        assertEquals("Unsupported command must be dropped", 0, sentCommands().size)

        handler.sendCommand("play")
        assertEquals(1, sentCommands().size)
        assertTrue(sentCommands()[0].contains("\"command\":\"play\""))
    }

    @Test
    fun `sendCommand sends nothing before a controller state has arrived`() {
        activateRoles("\"player@v1\",\"controller@v1\"")
        handler.sendCommand("play")
        assertEquals(0, sentCommands().size)
    }

    @Test
    fun `sendCommand sends nothing while the controller role is not active`() {
        // "Only valid from clients whose `controller` role is active."
        activateRoles("\"player@v1\"")
        controllerState(""""supported_commands":["play","pause"]""")
        handler.sendCommand("play")
        assertEquals(0, sentCommands().size)
    }

    @Test
    fun `seek carries position_ms clamped to 0 through seek_max_ms`() {
        activateRoles("\"controller@v1\"")
        controllerState(""""supported_commands":["seek"],"seek_max_ms":200000""")

        handler.sendCommand("seek", positionMs = 42_000)
        handler.sendCommand("seek", positionMs = 999_000)
        handler.sendCommand("seek", positionMs = -5)

        assertEquals(3, sentCommands().size)
        assertTrue(sentCommands()[0].contains("\"position_ms\":42000"))
        assertTrue(sentCommands()[1].contains("\"position_ms\":200000"))
        assertTrue(sentCommands()[2].contains("\"position_ms\":0"))
    }

    @Test
    fun `seek is dropped without a seek_max_ms or a position`() {
        activateRoles("\"controller@v1\"")
        controllerState(""""supported_commands":["seek","seek_relative"]""")

        handler.sendCommand("seek", positionMs = 42_000)
        handler.sendCommand("seek_relative")
        assertEquals(0, sentCommands().size)

        controllerState(""""seek_max_ms":200000""")
        handler.sendCommand("seek")
        assertEquals(0, sentCommands().size)
    }

    @Test
    fun `seek_relative carries a signed offset_ms`() {
        activateRoles("\"controller@v1\"")
        controllerState(""""supported_commands":["seek_relative"]""")

        handler.sendCommand("seek_relative", offsetMs = -10_000)

        assertEquals(1, sentCommands().size)
        assertTrue(sentCommands()[0].contains("\"command\":\"seek_relative\""))
        assertTrue(sentCommands()[0].contains("\"offset_ms\":-10000"))
    }

    // ========== Sync State Validation Tests ==========

    @Test
    fun `default sync state is error before any sync measurement`() {
        // Per spec: a client that has not yet synchronized to the server
        // timeline must report state="error", not "synchronized".
        val fresh = TestProtocolHandler()
        assertEquals("error", fresh.exposedSyncState())
    }

    @Test
    fun `setSyncState accepts synchronized`() {
        handler.setSyncState("synchronized")
        assertEquals("synchronized", handler.exposedSyncState())
    }

    @Test
    fun `setSyncState accepts error`() {
        handler.setSyncState("error")
        assertEquals("error", handler.exposedSyncState())
    }

    @Test
    fun `setSyncState rejects invalid value and keeps previous state`() {
        handler.setSyncState("synchronized")
        assertEquals("synchronized", handler.exposedSyncState())

        handler.setSyncState("invalid_state")
        assertEquals(
            "Invalid sync state should be rejected, keeping previous value",
            "synchronized",
            handler.exposedSyncState()
        )
    }

    @Test
    fun `setSyncState rejects empty string`() {
        handler.setSyncState("error")
        handler.setSyncState("")
        assertEquals("error", handler.exposedSyncState())
    }

    @Test
    fun `setSyncState rejects close misspellings`() {
        handler.setSyncState("synchronized")
        handler.setSyncState("Synchronized")
        assertEquals(
            "Case-sensitive: 'Synchronized' should be rejected",
            "synchronized",
            handler.exposedSyncState()
        )
    }

    // ========== Filter-driven sync state evaluation ==========

    @Test
    fun `evaluateAndPublishSyncState reports synchronized once filter converges`() {
        // Drive the filter to convergence by feeding consistent measurements.
        val filter = handler.exposedTimeFilter()
        for (i in 1..30) {
            filter.addMeasurement(10_000L, 3000L, i * 1_000_000L)
        }
        assertTrue("Sanity: filter should be converged", filter.isConverged)

        handler.evaluateAndPublishSyncStateForTest()

        assertEquals("synchronized", handler.exposedSyncState())
    }

    @Test
    fun `evaluateAndPublishSyncState stays error while filter not converged`() {
        // Two measurements -> isReady but not isConverged.
        val filter = handler.exposedTimeFilter()
        filter.addMeasurement(10_000L, 3000L, 1_000_000L)
        filter.addMeasurement(10_000L, 3000L, 2_000_000L)

        handler.evaluateAndPublishSyncStateForTest()

        assertEquals("error", handler.exposedSyncState())
    }

    @Test
    fun `mute is requested only after first convergence is lost`() {
        val filter = handler.exposedTimeFilter()

        // Initial pre-convergence period: state="error" but mute is NOT
        // requested (we have not yet established a sync to drop).
        filter.addMeasurement(10_000L, 3000L, 1_000_000L)
        filter.addMeasurement(10_000L, 3000L, 2_000_000L)
        handler.evaluateAndPublishSyncStateForTest()
        assertEquals("error", handler.exposedSyncState())
        assertFalse(
            "Initial pre-sync window must not silence audio",
            handler.lastMuteDecision()
        )

        // Converge -> "synchronized", mute released.
        for (i in 3..30) {
            filter.addMeasurement(10_000L, 3000L, i * 1_000_000L)
        }
        handler.evaluateAndPublishSyncStateForTest()
        assertEquals("synchronized", handler.exposedSyncState())
        assertFalse(handler.lastMuteDecision())

        // Simulate sync loss (reset filter so isConverged drops).
        filter.reset()
        handler.evaluateAndPublishSyncStateForTest()
        assertEquals("error", handler.exposedSyncState())
        assertTrue(
            "After convergence has been established and lost, mute must engage",
            handler.lastMuteDecision()
        )
    }

    @Test
    fun `available stays true when sync is lost after first convergence`() {
        val filter = handler.exposedTimeFilter()
        assertFalse("Not available before the filter converges", handler.exposedIsAvailable())

        for (i in 1..30) {
            filter.addMeasurement(10_000L, 3000L, i * 1_000_000L)
        }
        handler.evaluateAndPublishSyncStateForTest()
        assertTrue(handler.exposedIsAvailable())

        // "available: false" ends our streams, so losing sync mid-stream must
        // mute locally instead of reporting it.
        filter.reset()
        handler.evaluateAndPublishSyncStateForTest()
        assertEquals("error", handler.exposedSyncState())
        assertTrue("Sync loss must not report unavailable", handler.exposedIsAvailable())

        // A new connection starts from scratch.
        handler.resetSyncStateTrackingForTest()
        assertFalse(handler.exposedIsAvailable())
    }

    @Test
    fun `resetSyncStateTracking clears mute and returns state to error`() {
        val filter = handler.exposedTimeFilter()
        for (i in 1..30) {
            filter.addMeasurement(10_000L, 3000L, i * 1_000_000L)
        }
        handler.evaluateAndPublishSyncStateForTest()
        filter.reset()
        handler.evaluateAndPublishSyncStateForTest()
        assertTrue("Sanity: should be muted after sync loss", handler.lastMuteDecision())
        val mutesBefore = handler.muteEvents.size

        handler.resetSyncStateTrackingForTest()

        assertEquals("error", handler.exposedSyncState())
        assertEquals(
            "Reset must release any active mute via onSyncMuteChanged(false)",
            mutesBefore + 1,
            handler.muteEvents.size
        )
        assertEquals(false, handler.muteEvents.last())
    }

    @Test
    fun `evaluateAndPublishSyncState fires onSyncMuteChanged only on transitions`() {
        val filter = handler.exposedTimeFilter()
        for (i in 1..30) {
            filter.addMeasurement(10_000L, 3000L, i * 1_000_000L)
        }
        handler.evaluateAndPublishSyncStateForTest()
        val initialMuteEvents = handler.muteEvents.size

        // Re-evaluate with no state change -> no new mute event.
        handler.evaluateAndPublishSyncStateForTest()
        assertEquals(initialMuteEvents, handler.muteEvents.size)

        filter.reset()
        handler.evaluateAndPublishSyncStateForTest()
        assertEquals(
            "Sync loss after convergence must fire one mute=true event",
            initialMuteEvents + 1,
            handler.muteEvents.size
        )
        assertEquals(true, handler.muteEvents.last())
    }

    // ========== Stream Start Dispatch Tests ==========

    @Test
    fun `stream start with same format dispatches every time`() {
        val streamStart = buildStreamStartJson(codec = "pcm", sampleRate = 48000, channels = 2, bitDepth = 16)

        handler.handleTextMessageForTest(streamStart)
        handler.handleTextMessageForTest(streamStart)

        assertEquals(
            "Every stream/start should dispatch to onStreamStart regardless of format match",
            2,
            handler.streamStarts.size
        )
    }

    @Test
    fun `stream start with different format dispatches`() {
        val start1 = buildStreamStartJson(codec = "pcm", sampleRate = 48000, channels = 2, bitDepth = 16)
        val start2 = buildStreamStartJson(codec = "pcm", sampleRate = 44100, channels = 2, bitDepth = 24)

        handler.handleTextMessageForTest(start1)
        handler.handleTextMessageForTest(start2)

        assertEquals(2, handler.streamStarts.size)
        assertEquals(48000, handler.streamStarts[0].sampleRate)
        assertEquals(44100, handler.streamStarts[1].sampleRate)
    }

    @Test
    fun `required lead time is the cold value until the first stream starts, then the warm one`() {
        activateRoles("\"${SendSpinProtocol.Roles.PLAYER}\"")
        val cold = "\"required_lead_time_ms\":${SendSpinProtocol.PlayerTiming.REQUIRED_LEAD_TIME_MS},"
        val warm = "\"required_lead_time_ms\":${SendSpinProtocol.PlayerTiming.REQUIRED_LEAD_TIME_WARM_MS},"

        handler.sentMessages.clear()
        handler.setExternalSource(true)
        assertTrue(handler.sentMessages.last().contains(cold))

        // The first stream/start reports the lower value by itself...
        val streamStart = buildStreamStartJson(codec = "pcm", sampleRate = 48000, channels = 2, bitDepth = 16)
        handler.sentMessages.clear()
        handler.handleTextMessageForTest(streamStart)
        assertEquals(1, handler.sentMessages.size)
        assertTrue(handler.sentMessages[0].contains(warm))

        // ...and a later one has nothing new to say.
        handler.sentMessages.clear()
        handler.handleTextMessageForTest(streamStart)
        assertTrue(handler.sentMessages.isEmpty())
    }

    // ========== Helpers ==========

    private fun buildServerStateJson(
        title: String,
        artist: String,
        album: String
    ): String {
        return """
            {
                "type": "server/state",
                "payload": {
                    "metadata": {
                        "timestamp": 1000000,
                        "title": "$title",
                        "artist": "$artist",
                        "album_artist": "$artist",
                        "album": "$album",
                        "artwork_url": "",
                        "year": 2024,
                        "track": 1,
                        "progress": {
                            "track_progress": 0,
                            "track_duration": 180000,
                            "playback_speed": 1000
                        }
                    },
                    "state": "playing"
                }
            }
        """.trimIndent()
    }

    private fun buildStreamStartJson(
        codec: String,
        sampleRate: Int,
        channels: Int,
        bitDepth: Int
    ): String {
        return """
            {
                "type": "stream/start",
                "payload": {
                    "player": {
                        "codec": "$codec",
                        "sample_rate": $sampleRate,
                        "channels": $channels,
                        "bit_depth": $bitDepth
                    }
                }
            }
        """.trimIndent()
    }
}

/**
 * Concrete test implementation of SendSpinProtocolHandler.
 * Records all callback invocations for assertion.
 */
class TestProtocolHandler : SendSpinProtocolHandler("TestHandler") {

    // Unconfined, so a send the handler launches has reached sendBinaryFrame
    // by the time the call that triggered it returns.
    private val testDispatcher = UnconfinedTestDispatcher()
    private val testScope = CoroutineScope(testDispatcher)

    /** Virtual time for whatever the handler has scheduled with `delay`. */
    val testScheduler get() = testDispatcher.scheduler
    private val timeFilter = SendspinTimeFilter()
    val sentMessages = mutableListOf<String>()
    val metadataUpdates = mutableListOf<TrackMetadata>()
    val controllerStateUpdates = mutableListOf<ControllerState>()
    val playbackStateChanges = mutableListOf<String>()
    val groupUpdates = mutableListOf<GroupInfo>()
    val streamStarts = mutableListOf<StreamConfig>()
    val muteEvents = mutableListOf<Boolean>()

    fun setHandshakeCompleteForTest() {
        handshakeComplete = true
    }

    fun exposedVolume(): Int = currentVolume
    fun exposedSyncState(): String = currentSyncState
    fun exposedIsAvailable(): Boolean = isAvailable()
    fun exposedTimeFilter(): SendspinTimeFilter = timeFilter
    fun lastMuteDecision(): Boolean = muteEvents.lastOrNull() ?: false
    fun evaluateAndPublishSyncStateForTest() = evaluateAndPublishSyncState()
    fun sendGoodbyeForTest(reason: GoodbyeReason) = encodeGoodbye(reason).forEach { sendBinaryFrame(it) }
    fun resetServerStateForTest() = resetServerState()
    fun resetSyncStateTrackingForTest() = resetSyncStateTracking()

    fun handleTextMessageForTest(text: String) {
        handleTextMessage(text)
    }

    /** Deliver a frame the way the transport does: `[type][body]`, since the channel is plaintext. */
    fun handleBinaryMessageForTest(frame: ByteArray) = handleBinaryMessage(frame)

    var matchedCategory: PskCategory = PskCategory.SENTINEL
    var unpairedAccess = true
    var formats: List<MessageBuilder.FormatEntry> = emptyList()

    /** What reached the wire and when the connection was closed, in order. */
    val events = mutableListOf<String>()
    val audioChunks = mutableListOf<Pair<Long, ByteArray>>()
    val protocolFailures = mutableListOf<String>()

    override fun matchedPskCategory(): PskCategory = matchedCategory

    override fun isUnpairedAccessEnabled(): Boolean = unpairedAccess

    override fun currentServerId(): String = "srv1"

    override fun closeConnectionAfterFlush() {
        events.add("close")
    }

    override fun onProtocolFailure(reason: String) {
        protocolFailures.add(reason)
    }

    /**
     * Stands in for the connection's re-handshake: reply under the current
     * keys, swap, then reset what a re-handshake invalidates. [matchedCategory]
     * is whatever the test set before delivering the `noise/handshake`.
     */
    override fun onRehandshakeMessage(payload: JsonObject?) {
        sendAndSwapKeys("""{"type":"noise/handshake","payload":{"data":"reply"}}""", PlaintextCrypto) {
            resetForRehandshake()
        }
    }

    init {
        installEncryptedChannel(PlaintextCrypto)
    }

    override fun sendBinaryFrame(bytes: ByteArray) {
        val text = bytes.jsonFrameText() ?: return
        sentMessages.add(text)
        events.add("send:" + Json.parseToJsonElement(text).jsonObject["type"]?.jsonPrimitive?.content)
    }

    override fun getCoroutineScope(): CoroutineScope = testScope

    override fun getTimeFilter(): SendspinTimeFilter = timeFilter

    var lowMemoryMode = false

    override fun isLowMemoryMode(): Boolean = lowMemoryMode


    override fun getDeviceName(): String = "Test Device"

    override fun getManufacturer(): String = "TestManufacturer"

    override fun getSupportedFormats(): List<MessageBuilder.FormatEntry> = formats

    override fun getSoftwareVersion(): String = "test"

    override fun onHandshakeComplete(serverName: String, serverId: String) {}

    override fun onMetadataUpdate(metadata: TrackMetadata) {
        metadataUpdates.add(metadata)
    }

    override fun onControllerStateUpdate(state: ControllerState) {
        controllerStateUpdates.add(state)
    }

    override fun onPlaybackStateChanged(state: String) {
        playbackStateChanges.add(state)
    }

    override fun onVolumeCommand(volume: Int) {}

    override fun onMuteCommand(muted: Boolean) {}

    override fun onGroupUpdate(info: GroupInfo) {
        groupUpdates.add(info)
    }

    override fun onStreamStart(config: StreamConfig) {
        streamStarts.add(config)
    }

    override fun onStreamClear() {}

    var streamEnds = 0

    override fun onStreamEnd() {
        streamEnds++
    }

    override fun onAudioChunk(timestampMicros: Long, audioData: ByteArray) {
        audioChunks.add(timestampMicros to audioData)
    }

    val artworkDeliveries = mutableListOf<Int>()

    /** Every image made current, in order; an empty one is a clear. */
    val artworkImages = mutableListOf<ByteArray>()

    override fun onArtwork(channel: Int, payload: ByteArray) {
        artworkDeliveries.add(channel)
        artworkImages.add(payload)
    }

    override fun onSyncOffsetApplied(offsetMs: Double, source: String) {}

    override fun onSyncMuteChanged(muted: Boolean) {
        muteEvents.add(muted)
    }
}
