package com.sendspindroid.sendspin.protocol.message

import com.sendspindroid.sendspin.crypto.Base64Url
import com.sendspindroid.sendspin.protocol.ControllerState
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonArray
import com.sendspindroid.sendspin.protocol.GroupInfo
import com.sendspindroid.sendspin.protocol.SendSpinProtocol
import com.sendspindroid.sendspin.protocol.ServerCommandResult
import com.sendspindroid.sendspin.protocol.ServerStateResult
import com.sendspindroid.sendspin.protocol.StreamConfig
import com.sendspindroid.sendspin.protocol.SyncOffsetResult
import com.sendspindroid.sendspin.protocol.TimeMeasurement
import com.sendspindroid.sendspin.protocol.TrackMetadata
import com.sendspindroid.sendspin.protocol.TrackProgress
import com.sendspindroid.shared.log.Log
import com.sendspindroid.shared.platform.Platform
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull

object MessageParser {
    private const val TAG = "MessageParser"

    /**
     * Parse `server/hello`, returning the server's friendly name.
     *
     * `name` is the only field this client reads; `server_id` comes from
     * `server/init` and the active roles from `server/activate`.
     */
    fun parseServerHello(payload: JsonObject?, defaultName: String): String? {
        if (payload == null) {
            Log.e(TAG, "server/hello missing payload")
            return null
        }
        return payload.stringOrDefault("name", defaultName)
    }

    fun parseServerTime(payload: JsonObject?, clientReceivedMicros: Long): TimeMeasurement? {
        if (payload == null) return null

        // Use nullable accessors so an explicit zero is distinguishable from
        // an absent field. Zero is a valid timestamp value; only an absent
        // field is grounds for rejection.
        val clientTransmitted = payload["client_transmitted"]?.jsonPrimitive?.longOrNull
        val serverReceived = payload["server_received"]?.jsonPrimitive?.longOrNull
        val serverTransmitted = payload["server_transmitted"]?.jsonPrimitive?.longOrNull

        if (clientTransmitted == null || serverReceived == null || serverTransmitted == null) {
            Log.w(TAG, "Invalid server/time payload")
            return null
        }

        val offset = ((serverReceived - clientTransmitted) + (serverTransmitted - clientReceivedMicros)) / 2
        val rtt = (clientReceivedMicros - clientTransmitted) - (serverTransmitted - serverReceived)

        return TimeMeasurement(offset, rtt, clientReceivedMicros)
    }

    /**
     * Parse `server/pair-init` for the Dynamic Pairing Code flow.
     *
     * @return `nonce_A`, or null if the field is missing or not a 32-byte
     *   base64url value. Both are protocol errors for the caller to raise.
     */
    fun parseServerPairInit(payload: JsonObject?): ByteArray? =
        decodeFixed(payload, "nonce_A", 32)

    /**
     * Parse `server/pair-auth` for the Dynamic Pairing Code flow.
     *
     * @return `Ya`, the server's CPace public share, or null if `pake_msg_1`
     *   is missing or not a 32-byte base64url value.
     */
    fun parseServerPairAuth(payload: JsonObject?): ByteArray? =
        decodeFixed(payload, "pake_msg_1", 32)

    /**
     * Parse `server/pair-confirm` for the Dynamic Pairing Code flow.
     *
     * @return the MCF key-confirmation tag `Ta`, or null if `server_kc` is
     *   missing or not a 64-byte base64url value.
     */
    fun parseServerPairConfirm(payload: JsonObject?): ByteArray? =
        decodeFixed(payload, "server_kc", 64)

    /** Decode a fixed-length base64url field, or null if absent/wrong length. */
    private fun decodeFixed(payload: JsonObject?, key: String, byteLength: Int): ByteArray? {
        val value = payload?.get(key)?.jsonPrimitive?.contentOrNull ?: return null
        val bytes = Base64Url.decodeOrNull(value) ?: return null
        return if (bytes.size == byteLength) bytes else null
    }

    /**
     * `server/state`: "Every message MUST carry the full state of each role
     * object it includes. Omitting a role object leaves that role's state
     * unchanged."
     *
     * So each role object present is that role's whole new state, and a null
     * role in the result means the message did not include it. Inside an
     * object, a field that is absent, JSON `null` or of the wrong type has no
     * value - which for `progress` is what "omitting it clears the client's
     * position" asks for.
     */
    fun parseServerState(payload: JsonObject?): ServerStateResult = ServerStateResult(
        metadata = (payload?.get("metadata") as? JsonObject)?.let(::parseMetadata),
        playbackState = payload?.stringOrDefault("state", "")?.takeIf { it.isNotEmpty() },
        controller = (payload?.get("controller") as? JsonObject)?.let(::parseController),
    )

    private fun parseMetadata(obj: JsonObject): TrackMetadata = TrackMetadata(
        timestamp = obj["timestamp"]?.longOrNull(),
        title = obj["title"]?.let(::cleanString),
        artist = obj["artist"]?.let(::cleanString),
        albumArtist = obj["album_artist"]?.let(::cleanString),
        album = obj["album"]?.let(::cleanString),
        artworkUrl = obj["artwork_url"]?.let(::cleanString),
        year = obj["year"]?.intOrNull(),
        track = obj["track"]?.intOrNull(),
        progress = (obj["progress"] as? JsonObject)?.let {
            TrackProgress(
                trackProgress = it.longOrDefault("track_progress", 0),
                trackDuration = it.longOrDefault("track_duration", 0),
                playbackSpeed = it.intOrDefault("playback_speed", 1000),
            )
        },
    )

    private fun parseController(obj: JsonObject): ControllerState = ControllerState(
        supportedCommands = (obj["supported_commands"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull },
        volume = obj["volume"]?.intOrNull(),
        muted = obj["muted"]?.booleanOrNull(),
        repeat = obj["repeat"]?.let(::cleanString),
        shuffle = obj["shuffle"]?.booleanOrNull(),
        seekMaxMs = obj["seek_max_ms"]?.longOrNull(),
    )

    /**
     * Music Assistant sends the four-character string "null" for an absent
     * title on some tracks. Treated as no value rather than a title.
     */
    private fun cleanString(element: JsonElement): String? =
        (element as? JsonPrimitive)?.contentOrNull?.takeUnless { it == "null" }

    private fun JsonElement.longOrNull(): Long? = (this as? JsonPrimitive)?.longOrNull

    private fun JsonElement.intOrNull(): Int? = (this as? JsonPrimitive)?.intOrNull

    private fun JsonElement.booleanOrNull(): Boolean? = (this as? JsonPrimitive)?.booleanOrNull

    fun parseServerCommand(payload: JsonObject?): ServerCommandResult? {
        if (payload == null) return null

        val player = payload["player"]?.jsonObject ?: return null
        val command = player.stringOrDefault("command", "")

        return when (command) {
            "volume" -> {
                val volume = player.intOrDefault("volume", -1)
                if (volume in 0..100) {
                    ServerCommandResult.Volume(volume)
                } else {
                    null
                }
            }
            "mute" -> {
                // `mute` is "required if command is mute". Defaulting a
                // missing field to false would unmute a muted player.
                val muted = player["mute"]?.booleanOrNull() ?: return null
                ServerCommandResult.Mute(muted)
            }
            "set_output_delay" -> {
                // roles/player/v1.md: integer, 0-5000 ms. The command and its
                // field were both named static_delay here, which matches no
                // spec revision - so a conforming server's command fell through
                // to Unknown and was silently dropped.
                // "Clients MUST clamp output_delay_ms to the range 0-5000."
                val delayMs = player["output_delay_ms"]?.longOrNull() ?: return null
                ServerCommandResult.SetOutputDelay(delayMs.coerceIn(0, 5000).toInt())
            }
            else -> {
                if (command.isNotEmpty()) {
                    ServerCommandResult.Unknown(command)
                } else {
                    null
                }
            }
        }
    }

    fun parseGroupUpdate(payload: JsonObject?): GroupInfo? {
        if (payload == null) return null

        val groupId = payload.stringOrDefault("group_id", "")
        val groupName = payload.stringOrDefault("group_name", "")
        val playbackState = payload.stringOrDefault("playback_state", "")

        return GroupInfo(groupId, groupName, playbackState)
    }

    fun parseStreamStart(payload: JsonObject?): StreamConfig? {
        if (payload == null) return null

        val player = payload["player"]?.jsonObject ?: return null

        val codec = player.stringOrDefault("codec", SendSpinProtocol.AudioFormat.DEFAULT_CODEC)
        val sampleRate = player.intOrDefault("sample_rate", SendSpinProtocol.AudioFormat.SAMPLE_RATE)
        val channels = player.intOrDefault("channels", SendSpinProtocol.AudioFormat.CHANNELS)
        val bitDepth = player.intOrDefault("bit_depth", SendSpinProtocol.AudioFormat.BIT_DEPTH)

        val codecHeader = player["codec_header"]?.jsonPrimitive?.contentOrNull?.let { base64 ->
            try {
                Platform.base64Decode(base64)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to decode codec_header")
                null
            }
        }

        return StreamConfig(codec, sampleRate, channels, bitDepth, codecHeader)
    }

    /**
     * Parse client/sync_offset. NOTE: this is a Music Assistant extension
     * (GroupSync), not part of the Sendspin spec.
     */
    fun parseSyncOffset(payload: JsonObject?): SyncOffsetResult? {
        if (payload == null) return null

        val playerId = payload.stringOrDefault("player_id", "")
        val offsetMs = payload.doubleOrDefault("offset_ms", 0.0)
        val source = payload.stringOrDefault("source", "unknown")

        return SyncOffsetResult(playerId, offsetMs, source)
    }

    // Helper extensions for safe JSON access with defaults

    private fun JsonObject.stringOrDefault(key: String, default: String): String =
        this[key]?.jsonPrimitive?.contentOrNull ?: default

    private fun JsonObject.longOrDefault(key: String, default: Long): Long =
        this[key]?.jsonPrimitive?.longOrNull ?: default

    private fun JsonObject.intOrDefault(key: String, default: Int): Int =
        this[key]?.jsonPrimitive?.intOrNull ?: default

    private fun JsonObject.doubleOrDefault(key: String, default: Double): Double =
        this[key]?.jsonPrimitive?.doubleOrNull ?: default

    private fun JsonObject.booleanOrDefault(key: String, default: Boolean): Boolean =
        this[key]?.jsonPrimitive?.booleanOrNull ?: default
}
