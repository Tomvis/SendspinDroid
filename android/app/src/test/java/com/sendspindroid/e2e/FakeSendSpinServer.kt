package com.sendspindroid.e2e

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Fake SendSpin server that drives a FakeTransport.
 *
 * Simulates server-side behavior for E2E testing:
 * - Sends server/hello and the initial server/activate
 * - Sends server/state and group/update messages
 * - Generates binary audio chunks with proper header format
 * - Handles time sync messages
 *
 * Usage:
 * ```
 * val transport = FakeTransport()
 * val server = FakeSendSpinServer(transport)
 *
 * // In test setup, inject transport into SendSpin via reflection
 * // then:
 * server.completeHandshake()
 * server.sendPlayingState()
 * server.sendAudioChunk(timestampMicros, pcmData)
 * ```
 */
class FakeSendSpinServer(
    private val transport: FakeTransport,
    val serverName: String = "TestServer",
    val serverId: String = "test-server-id"
) {

    companion object {
        // Binary message types (from protocol spec)
        const val MSG_TYPE_AUDIO = 4
        const val MSG_TYPE_ARTWORK_0 = 8
        const val MSG_TYPE_VISUALIZER = 16

        // Artwork flags byte: bit 1 on an announce, bit 0 on a cancel.
        const val ARTWORK_FLAG_ANNOUNCE: Byte = 0x02
        const val ARTWORK_FLAG_CANCEL: Byte = 0x01

        // Default audio format
        const val DEFAULT_SAMPLE_RATE = 48000
        const val DEFAULT_CHANNELS = 2
        const val DEFAULT_BIT_DEPTH = 16
        const val DEFAULT_CODEC = "pcm"
    }

    /** Track whether handshake was completed. */
    var handshakeCompleted = false
        private set

    /** Messages received from the client (parsed from transport.sentTextMessages). */
    val receivedMessages: List<String> get() = transport.sentTextMessages.toList()

    /**
     * Simulate server connection and the message sequence that follows the
     * Noise handshake (which E2ETestBase stands in for):
     * 1. Transport becomes connected (the client sends client/init)
     * 2. server/hello (the client answers with client/hello)
     * 3. server/activate granting playback and the given roles
     */
    fun completeHandshake(
        activeRoles: List<String> = listOf("player@v1", "controller@v1", "metadata@v1")
    ) {
        transport.simulateConnected()
        sendServerHello()
        sendServerActivate(activities = listOf("playback"), activeRoles = activeRoles)
        handshakeCompleted = true
    }

    /**
     * Send a server/hello message.
     */
    fun sendServerHello() {
        val msg = buildJsonObject {
            put("type", "server/hello")
            put("payload", buildJsonObject {
                put("name", serverName)
            })
        }
        transport.simulateTextMessage(msg.toString())
    }

    /**
     * Send a server/activate message. [activeRoles] null omits the field.
     */
    fun sendServerActivate(
        activities: List<String>,
        activeRoles: List<String>? = null,
    ) {
        val msg = buildJsonObject {
            put("type", "server/activate")
            put("payload", buildJsonObject {
                put("activities", buildJsonArray {
                    activities.forEach { add(JsonPrimitive(it)) }
                })
                if (activeRoles != null) {
                    put("active_roles", buildJsonArray {
                        activeRoles.forEach { add(JsonPrimitive(it)) }
                    })
                }
            })
        }
        transport.simulateTextMessage(msg.toString())
    }

    /**
     * Send a server/state message with track metadata and playback state.
     *
     * Protocol format: payload contains "state" (string) and "metadata" (object).
     * The metadata object contains track info and a "progress" sub-object.
     * See MessageParser.parseServerState() for the expected structure.
     */
    fun sendServerState(
        playbackState: String = "playing",
        title: String = "Test Track",
        artist: String = "Test Artist",
        album: String = "Test Album",
        durationMs: Long = 240000,
        positionMs: Long = 0,
        artworkUrl: String = ""
    ) {
        val msg = buildJsonObject {
            put("type", "server/state")
            put("payload", buildJsonObject {
                put("state", playbackState)
                put("metadata", buildJsonObject {
                    put("title", title)
                    put("artist", artist)
                    put("album", album)
                    put("artwork_url", artworkUrl)
                    put("timestamp", 0)
                    put("progress", buildJsonObject {
                        put("track_progress", positionMs)
                        put("track_duration", durationMs)
                        put("playback_speed", 1000)
                    })
                })
            })
        }
        transport.simulateTextMessage(msg.toString())
    }

    /**
     * Send a group/update message.
     */
    fun sendGroupUpdate(
        groupId: String = "group-1",
        groupName: String = "Living Room",
        playbackState: String = "playing"
    ) {
        val msg = buildJsonObject {
            put("type", "group/update")
            put("payload", buildJsonObject {
                put("group_id", groupId)
                put("group_name", groupName)
                put("playback_state", playbackState)
            })
        }
        transport.simulateTextMessage(msg.toString())
    }

    /**
     * Send a stream/start message to configure the audio stream.
     *
     * Protocol format: payload contains a "player" sub-object with audio format.
     * See MessageParser.parseStreamStart() for the expected structure.
     */
    fun sendStreamStart(
        codec: String = DEFAULT_CODEC,
        sampleRate: Int = DEFAULT_SAMPLE_RATE,
        channels: Int = DEFAULT_CHANNELS,
        bitDepth: Int = DEFAULT_BIT_DEPTH
    ) {
        val msg = buildJsonObject {
            put("type", "stream/start")
            put("payload", buildJsonObject {
                put("player", buildJsonObject {
                    put("codec", codec)
                    put("sample_rate", sampleRate)
                    put("channels", channels)
                    put("bit_depth", bitDepth)
                })
            })
        }
        transport.simulateTextMessage(msg.toString())
    }

    /**
     * Send a stream/start for the artwork role alone, with the one channel the
     * client declares in client/state.
     */
    fun sendArtworkStreamStart() {
        transport.simulateTextMessage(
            """{"type":"stream/start","payload":{"artwork":{"channels":[""" +
                """{"source":"album","format":"jpeg","width":500,"height":500}]}}}"""
        )
    }

    /**
     * Send a stream/stop message.
     */
    fun sendStreamEnd() {
        val msg = buildJsonObject {
            put("type", "stream/end")
        }
        transport.simulateTextMessage(msg.toString())
    }

    /**
     * Send a binary audio chunk with proper protocol header.
     *
     * Header format: 1 byte type + 8 byte big-endian int64 timestamp + 4 byte
     * big-endian uint32 send_ahead. Followed by PCM audio data.
     */
    fun sendAudioChunk(timestampMicros: Long, audioData: ByteArray, sendAheadMicros: Int = 500_000) {
        val message = ByteBuffer.allocate(13 + audioData.size)
        message.order(ByteOrder.BIG_ENDIAN)
        message.put(MSG_TYPE_AUDIO.toByte())
        message.putLong(timestampMicros)
        message.putInt(sendAheadMicros)
        message.put(audioData)

        transport.simulateBinaryMessage(message.array())
    }

    /**
     * Transfer an image on an artwork channel the way roles/artwork/v1.md
     * defines it: an announce `[type][flags][timestamp][total_size]` followed
     * by one part `[type][flags][data]`. An empty image is an announce alone,
     * which is how a channel is cleared.
     */
    fun sendArtwork(channel: Int, imageData: ByteArray) {
        val type = (MSG_TYPE_ARTWORK_0 + channel).toByte()
        val announce = ByteBuffer.allocate(14)
        announce.order(ByteOrder.BIG_ENDIAN)
        announce.put(type)
        announce.put(ARTWORK_FLAG_ANNOUNCE)
        announce.putLong(0L)
        announce.putInt(imageData.size)
        transport.simulateBinaryMessage(announce.array())

        if (imageData.isNotEmpty()) {
            transport.simulateBinaryMessage(byteArrayOf(type, 0) + imageData)
        }
    }

    /**
     * Cancel the pending image on an artwork channel: `[type][flags]`.
     */
    fun cancelArtwork(channel: Int) {
        transport.simulateBinaryMessage(
            byteArrayOf((MSG_TYPE_ARTWORK_0 + channel).toByte(), ARTWORK_FLAG_CANCEL)
        )
    }

    /**
     * Send a server/time response for clock synchronization.
     *
     * Responds to the client's client/time message with a server timestamp.
     */
    fun sendTimeResponse(clientTimestamp: Long, serverTimestamp: Long) {
        val msg = buildJsonObject {
            put("type", "server/time")
            put("payload", buildJsonObject {
                put("client_time", clientTimestamp)
                put("server_time", serverTimestamp)
            })
        }
        transport.simulateTextMessage(msg.toString())
    }

    /**
     * Send a proxy auth_ok response.
     */
    fun sendAuthOk() {
        val msg = buildJsonObject {
            put("type", "auth_ok")
            put("message", "Authenticated")
        }
        transport.simulateTextMessage(msg.toString())
    }

    /**
     * Send a proxy auth_failed response.
     */
    fun sendAuthFailed(message: String = "Invalid token") {
        val msg = buildJsonObject {
            put("type", "auth_failed")
            put("message", message)
        }
        transport.simulateTextMessage(msg.toString())
    }

    /**
     * Generate a block of silent PCM audio data.
     *
     * @param durationMs Duration in milliseconds
     * @param sampleRate Sample rate (default 48000)
     * @param channels Number of channels (default 2)
     * @param bitDepth Bit depth (default 16)
     * @return ByteArray of silence
     */
    fun generateSilence(
        durationMs: Int = 100,
        sampleRate: Int = DEFAULT_SAMPLE_RATE,
        channels: Int = DEFAULT_CHANNELS,
        bitDepth: Int = DEFAULT_BIT_DEPTH
    ): ByteArray {
        val bytesPerSample = bitDepth / 8
        val totalSamples = (sampleRate * durationMs / 1000) * channels
        return ByteArray(totalSamples * bytesPerSample)
    }

    /**
     * Check if the client sent a client/hello message.
     */
    fun clientSentHello(): Boolean {
        return transport.hasSentMessageContaining("client/hello")
    }

    /**
     * Check if the client sent a client/state message.
     */
    fun clientSentState(): Boolean {
        return transport.hasSentMessageContaining("client/state")
    }

    /**
     * Check if the client sent a specific command.
     */
    fun clientSentCommand(command: String): Boolean {
        return transport.findSentMessages { msg ->
            try {
                val json = Json.parseToJsonElement(msg).jsonObject
                val type = json["type"]?.jsonPrimitive?.contentOrNull
                type == "client/command" &&
                    json["payload"]?.jsonObject?.get("command")?.jsonPrimitive?.contentOrNull == command
            } catch (e: Exception) {
                false
            }
        }.isNotEmpty()
    }

    /**
     * Check if the client sent a goodbye message.
     */
    fun clientSentGoodbye(): Boolean {
        return transport.hasSentMessageContaining("client/goodbye")
    }
}
