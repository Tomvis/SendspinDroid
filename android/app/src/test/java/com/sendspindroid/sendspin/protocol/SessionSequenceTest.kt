package com.sendspindroid.sendspin.protocol

import com.sendspindroid.sendspin.crypto.PskCategory
import com.sendspindroid.sendspin.protocol.message.MessageBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The order of messages on a connection, as Sendspin 1.0.0-rc1 defines it.
 *
 * `messaging.md#communication`: server/hello, then client/hello, then
 * server/activate - and "the client MUST NOT send other Sendspin messages until
 * it receives that activation". `connection.md#re-handshake` then changes the
 * keys underneath all of that without repeating any of it.
 */
class SessionSequenceTest {

    private lateinit var handler: TestProtocolHandler

    private val serverHello = """{"type":"server/hello","payload":{"name":"Dev"}}"""
    private val noiseHandshake = """{"type":"noise/handshake","payload":{"data":"message-1"}}"""

    private fun activate(activities: String, roles: String? = null) =
        """{"type":"server/activate","payload":{"activities":[$activities]""" +
            (if (roles != null) ""","active_roles":[$roles]""" else "") + "}}"

    private val playerRoles = "\"player@v1\",\"controller@v1\",\"metadata@v1\""

    private fun sentTypes() = handler.events.filter { it.startsWith("send:") }.map { it.removePrefix("send:") }

    @Before
    fun setUp() {
        handler = TestProtocolHandler()
        handler.formats = listOf(
            MessageBuilder.FormatEntry("flac", 48000, 2, 16),
            MessageBuilder.FormatEntry("pcm", 48000, 2, 16),
        )
    }

    // ========== hello ==========

    @Test
    fun `nothing is sent before server hello arrives`() {
        // The channel is up (the Noise handshake completed) and that is all.
        assertEquals(emptyList<String>(), sentTypes())
    }

    @Test
    fun `client hello is the reply to server hello and nothing follows it`() {
        handler.handleTextMessageForTest(serverHello)

        // No client/state and no client/time: those wait for the activation.
        assertEquals(listOf("client/hello"), sentTypes())
    }

    @Test
    fun `client state and time sync start with the initial activation`() {
        handler.handleTextMessageForTest(serverHello)
        handler.handleTextMessageForTest(activate("", playerRoles))

        assertEquals(listOf("client/hello", "client/state"), sentTypes())
    }

    // ========== client/state role objects ==========

    @Test
    fun `client state carries an object for each active role that defines one`() {
        handler.handleTextMessageForTest(serverHello)
        handler.handleTextMessageForTest(activate("", "$playerRoles,\"artwork@v1\""))

        val state = handler.sentMessages.last()
        assertTrue(state.contains("\"available\":"))
        assertTrue(state.contains("\"player\":{"))
        // The server sends no artwork until the role's channels are declared.
        assertTrue(
            state.contains(
                "\"artwork\":{\"channels\":[{\"source\":\"album\",\"format\":\"jpeg\"," +
                    "\"width\":500,\"height\":500}]}"
            )
        )
    }

    @Test
    fun `client state omits the objects of roles that are not active`() {
        handler.handleTextMessageForTest(serverHello)
        handler.handleTextMessageForTest(activate(""))

        assertEquals(
            """{"type":"client/state","payload":{"available":false}}""",
            handler.sentMessages.last(),
        )
    }

    // ========== format preference ==========

    @Test
    fun `a codec preference is reported as the matching advertised format`() {
        handler.handleTextMessageForTest(serverHello)
        handler.handleTextMessageForTest(activate("", playerRoles))
        handler.sentMessages.clear()

        handler.setPreferredCodec("pcm")

        val state = handler.sentMessages.single()
        assertTrue(state.contains("\"type\":\"client/state\""))
        assertTrue(
            state.contains("\"format\":{\"codec\":\"pcm\",\"sample_rate\":48000,\"channels\":2,\"bit_depth\":16}")
        )
        assertFalse("stream/request-format no longer exists", state.contains("request-format"))
    }

    @Test
    fun `a codec this connection did not advertise is not reported`() {
        // "format ... MUST be one of the entries in supported_formats", and
        // client/hello is sent once per connection.
        handler.handleTextMessageForTest(serverHello)
        handler.handleTextMessageForTest(activate("", playerRoles))
        handler.sentMessages.clear()

        handler.setPreferredCodec("opus")

        assertEquals(emptyList<String>(), handler.sentMessages)
    }

    @Test
    fun `no format is reported until a preference overrides the hello order`() {
        handler.handleTextMessageForTest(serverHello)
        handler.handleTextMessageForTest(activate("", playerRoles))

        assertFalse(handler.sentMessages.last().contains("\"format\""))
    }

    // ========== activation rejection ==========

    @Test
    fun `an inadmissible activation is answered with goodbye and then closed`() {
        handler.matchedCategory = PskCategory.LONG_TERM
        handler.handleTextMessageForTest(serverHello)
        handler.events.clear()

        // A paired session may not be moved into pairing.
        handler.handleTextMessageForTest(
            """{"type":"server/activate","payload":{"activities":["pairing"],""" +
                """"pairing":{"method":"dynamic_pairing_code"}}}"""
        )

        // The reason has to be on the wire before the socket closes.
        assertEquals(listOf("send:client/goodbye", "close"), handler.events)
        assertTrue(handler.sentMessages.last().contains("\"reason\":\"unauthorized\""))
    }

    @Test
    fun `unpaired playback without unpaired access is refused as pairing_required`() {
        handler.unpairedAccess = false
        handler.handleTextMessageForTest(serverHello)
        handler.events.clear()

        handler.handleTextMessageForTest(activate("\"playback\"", playerRoles))

        assertEquals(listOf("send:client/goodbye", "close"), handler.events)
        assertTrue(handler.sentMessages.last().contains("\"reason\":\"pairing_required\""))
    }

    // ========== re-handshake ==========

    @Test
    fun `a re-handshake sends no hello and keeps the session`() {
        handler.handleTextMessageForTest(serverHello)
        handler.handleTextMessageForTest(activate("\"playback\"", playerRoles))
        handler.events.clear()
        handler.sentMessages.clear()

        // Promotion to a long-term PSK after pairing.
        handler.matchedCategory = PskCategory.LONG_TERM
        handler.handleTextMessageForTest(noiseHandshake)

        // Only Noise message 2. "Neither server/hello nor client/hello is
        // re-sent."
        assertEquals(listOf("noise/handshake"), sentTypes())
    }

    @Test
    fun `the activation after a re-handshake is a subsequent one so omitted roles persist`() {
        handler.handleTextMessageForTest(serverHello)
        handler.handleTextMessageForTest(activate("\"playback\"", playerRoles))

        handler.matchedCategory = PskCategory.LONG_TERM
        handler.handleTextMessageForTest(noiseHandshake)
        handler.sentMessages.clear()

        // active_roles omitted: a first activation would read that as empty.
        handler.handleTextMessageForTest(activate("\"playback\""))

        val state = handler.sentMessages.single()
        assertTrue(
            "the player role survived the re-handshake, so its object is still reported",
            state.contains("\"type\":\"client/state\"") && state.contains("\"player\":{"),
        )
    }

    @Test
    fun `handshakeComplete survives a re-handshake so state and goodbye still work`() {
        handler.handleTextMessageForTest(serverHello)
        handler.handleTextMessageForTest(activate("\"playback\"", playerRoles))
        handler.handleTextMessageForTest(noiseHandshake)
        handler.handleTextMessageForTest(activate("\"playback\""))
        handler.sentMessages.clear()

        // Both are gated on handshakeComplete, which used to be cleared here
        // and never set again, since no second server/hello arrives.
        handler.sendClientStateSnapshot()
        assertEquals(1, handler.sentMessages.count { it.contains("client/state") })

        handler.sendGoodbyeForTest(GoodbyeReason.USER_REQUEST)
        assertEquals(1, handler.sentMessages.count { it.contains("client/goodbye") })
    }

    @Test
    fun `nothing is sent between noise message 1 and the activation that follows`() {
        handler.handleTextMessageForTest(serverHello)
        handler.handleTextMessageForTest(activate("\"playback\"", playerRoles))
        handler.handleTextMessageForTest(noiseHandshake)
        handler.events.clear()
        handler.sentMessages.clear()

        // "The server MUST NOT start new application messages after sending
        // Noise message 1, nor the client after receiving it, except for the
        // handshake and server/activate."
        handler.setVolume(0.5)
        handler.sendCommand("play")
        handler.sendClientStateSnapshot()
        assertEquals(emptyList<String>(), sentTypes())

        // "This restriction ends when ... the client receives the new
        // server/activate" - and that activation re-sends the state the quiet
        // period swallowed.
        handler.handleTextMessageForTest(activate("\"playback\""))
        assertEquals(listOf("client/state"), sentTypes())
        assertTrue(handler.sentMessages.single().contains("\"volume\":50"))

        handler.handleTextMessageForTest(
            """{"type":"server/state","payload":{"controller":{"supported_commands":["play"]}}}"""
        )
        handler.sendCommand("play")
        assertEquals(listOf("client/state", "client/command"), sentTypes())
    }

    @Test
    fun `a re-handshake that lands on a PSK without playback clears the persisted roles`() {
        handler.unpairedAccess = false
        handler.matchedCategory = PskCategory.LONG_TERM
        handler.handleTextMessageForTest(serverHello)
        handler.handleTextMessageForTest(activate("\"playback\"", playerRoles))

        // The server re-handshakes to the pairing PSK to offer pairing_psk.
        // With unpaired access off that session is not playback-capable, and
        // "the persisted roles are treated as empty rather than the message
        // rejected".
        handler.matchedCategory = PskCategory.PAIRING
        handler.handleTextMessageForTest(noiseHandshake)
        handler.events.clear()
        handler.sentMessages.clear()
        handler.handleTextMessageForTest(
            """{"type":"server/activate","payload":{"activities":["pairing"],""" +
                """"pairing":{"method":"pairing_psk"}}}"""
        )

        assertFalse("the activation is accepted, not rejected", handler.events.contains("close"))
        val state = handler.sentMessages.first { it.contains("client/state") }
        assertFalse("no player object once the role is gone", state.contains("\"player\""))
    }

    // ========== binary messages ==========

    private fun frame(vararg bytes: Int) = ByteArray(bytes.size) { bytes[it].toByte() }

    @Test
    fun `audio chunks are read with the 13 byte header`() {
        handler.handleTextMessageForTest(serverHello)
        handler.handleTextMessageForTest(activate("\"playback\"", playerRoles))
        handler.handleTextMessageForTest(
            """{"type":"stream/start","payload":{"player":{"codec":"pcm","sample_rate":48000,""" +
                """"channels":2,"bit_depth":16}}}"""
        )

        // type 4, timestamp 0x0102, send_ahead 0x0A0B0C0D, then one stereo frame.
        handler.handleBinaryMessageForTest(
            frame(4, 0, 0, 0, 0, 0, 0, 1, 2, 0x0A, 0x0B, 0x0C, 0x0D, 0x11, 0x22, 0x33, 0x44)
        )

        val (timestamp, pcm) = handler.audioChunks.single()
        assertEquals(0x0102L, timestamp)
        assertEquals(listOf(0x11, 0x22, 0x33, 0x44), pcm.map { it.toInt() })
    }

    @Test
    fun `artwork outside a stream and unknown binary messages are ignored without closing`() {
        handler.handleTextMessageForTest(serverHello)
        handler.handleTextMessageForTest(activate("\"playback\"", "$playerRoles,\"artwork@v1\""))

        // An artwork announce, part and cancel on channel 0 with no artwork
        // stream started; the reserved IDs 2 and 3; a visualizer message.
        handler.handleBinaryMessageForTest(frame(8, 2, 0, 0, 0, 0, 0, 0, 0, 1, 0, 0, 0, 3))
        handler.handleBinaryMessageForTest(frame(8, 0, 0xFF, 0xD8, 0xFF))
        handler.handleBinaryMessageForTest(frame(8, 1))
        handler.handleBinaryMessageForTest(frame(2, 0, 9))
        handler.handleBinaryMessageForTest(frame(3, 9))
        handler.handleBinaryMessageForTest(frame(16, 1, 2, 3))

        assertEquals(emptyList<Int>(), handler.artworkDeliveries)
        assertEquals(emptyList<String>(), handler.protocolFailures)
    }

    @Test
    fun `a malformed fragment sequence is a protocol failure`() {
        handler.handleTextMessageForTest(serverHello)

        // A last fragment with none in flight.
        handler.handleBinaryMessageForTest(frame(1, 1, 0xAA))

        assertEquals(1, handler.protocolFailures.size)
    }

    @Test
    fun `a fragmented json message is dispatched once its last fragment arrives`() {
        handler.handleTextMessageForTest(serverHello)
        val json = activate("", playerRoles).encodeToByteArray()
        val half = json.size / 2

        // [1][first][orig_type 0][data], then [1][last][data].
        handler.handleBinaryMessageForTest(byteArrayOf(1, 2, 0) + json.copyOfRange(0, half))
        assertFalse("nothing dispatched mid-message", sentTypes().contains("client/state"))
        handler.handleBinaryMessageForTest(byteArrayOf(1, 1) + json.copyOfRange(half, json.size))

        assertEquals(listOf("client/hello", "client/state"), sentTypes())
    }
}
