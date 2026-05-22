package com.sendspindroid.sendspin.protocol.message

import com.sendspindroid.sendspin.protocol.ControllerState
import com.sendspindroid.sendspin.protocol.GroupInfo
import com.sendspindroid.sendspin.protocol.SendSpinProtocol
import com.sendspindroid.sendspin.protocol.ServerCommandResult
import com.sendspindroid.sendspin.protocol.ServerHelloResult
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

    fun parseServerHello(payload: JsonObject?, defaultName: String): ServerHelloResult? {
        if (payload == null) {
            Log.e(TAG, "server/hello missing payload")
            return null
        }

        val serverName = payload.stringOrDefault("name", defaultName)
        val serverId = payload.stringOrDefault("server_id", "")
        val connectionReason = payload.stringOrDefault("connection_reason", "discovery")

        val activeRoles = payload["active_roles"]?.jsonArray?.map {
            it.jsonPrimitive.content
        } ?: emptyList()

        return ServerHelloResult(
            serverName = serverName,
            serverId = serverId,
            activeRoles = activeRoles,
            connectionReason = connectionReason
        )
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

    fun parseServerState(
        payload: JsonObject?,
        previous: TrackMetadata? = null
    ): ServerStateResult {
        if (payload == null) return ServerStateResult(null, null, null)

        // The server emits diff-style updates (see aiosendspin
        // server/roles/metadata/state.py::diff_update): only fields that
        // changed appear in the message. A field that is absent from JSON
        // means "unchanged" and must inherit from [previous] rather than
        // reset to a default. A field present with `null` means "clear" and
        // resets to the type default ("" / 0). Without this distinction, a
        // title-only update would wipe artist/album/artwork/progress.
        val metadata = (payload["metadata"] as? JsonObject)?.let { metadataObj ->
            fun stringField(key: String, fallback: String): String {
                if (key !in metadataObj) return fallback
                return metadataObj[key]?.jsonPrimitive?.contentOrNull
                    ?.takeUnless { it == "null" } ?: ""
            }
            fun longField(key: String, fallback: Long): Long {
                if (key !in metadataObj) return fallback
                return metadataObj[key]?.jsonPrimitive?.longOrNull ?: 0L
            }
            fun intField(key: String, fallback: Int): Int {
                if (key !in metadataObj) return fallback
                return metadataObj[key]?.jsonPrimitive?.intOrNull ?: 0
            }

            val timestamp = longField("timestamp", previous?.timestamp ?: 0L)
            val title = stringField("title", previous?.title ?: "")
            val artist = stringField("artist", previous?.artist ?: "")
            val albumArtist = stringField("album_artist", previous?.albumArtist ?: "")
            val album = stringField("album", previous?.album ?: "")
            val artworkUrl = stringField("artwork_url", previous?.artworkUrl ?: "")
            val year = intField("year", previous?.year ?: 0)

            // album_track / legacy `track` resolution. Spec says "0 = not set",
            // so a present-but-zero album_track still falls back to legacy
            // `track` before finally inheriting from the previous metadata.
            val albumTrack: Int = when {
                "album_track" in metadataObj -> {
                    val v = metadataObj.intOrDefault("album_track", 0)
                    when {
                        v > 0 -> v
                        "track" in metadataObj -> metadataObj.intOrDefault("track", 0)
                        else -> previous?.albumTrack ?: 0
                    }
                }
                "track" in metadataObj -> metadataObj.intOrDefault("track", 0)
                else -> previous?.albumTrack ?: 0
            }

            val queueTrack = intField("queue_track", previous?.queueTrack ?: 0)
            val totalTracks = intField("total_tracks", previous?.totalTracks ?: 0)

            // Progress:
            // - "progress" present as object  -> update from object fields
            // - "progress" present but null   -> clear to defaults (server's
            //                                    cleared_update path)
            // - "progress" absent + legacy flat keys present -> legacy parse
            // - "progress" absent, no legacy  -> inherit from previous
            // Using `as? JsonObject` rather than `?.jsonObject` so JsonNull
            // (idle metadata) does not throw IllegalArgumentException.
            val progress: TrackProgress = when {
                "progress" in metadataObj -> {
                    (metadataObj["progress"] as? JsonObject)?.let { progressObj ->
                        TrackProgress(
                            trackProgress = progressObj.longOrDefault("track_progress", 0),
                            trackDuration = progressObj.longOrDefault("track_duration", 0),
                            playbackSpeed = progressObj.intOrDefault("playback_speed", 1000)
                        )
                    } ?: TrackProgress(0, 0, 1000)
                }
                "position_ms" in metadataObj || "duration_ms" in metadataObj -> {
                    TrackProgress(
                        trackProgress = metadataObj.longOrDefault("position_ms", 0),
                        trackDuration = metadataObj.longOrDefault("duration_ms", 0),
                        playbackSpeed = metadataObj.intOrDefault("playback_speed", 1000)
                    )
                }
                else -> previous?.progress ?: TrackProgress(0, 0, 1000)
            }

            TrackMetadata(
                timestamp = timestamp,
                title = title,
                artist = artist,
                albumArtist = albumArtist,
                album = album,
                artworkUrl = artworkUrl,
                year = year,
                albumTrack = albumTrack,
                queueTrack = queueTrack,
                totalTracks = totalTracks,
                progress = progress
            )
        }

        val state = payload.stringOrDefault("state", "").takeIf { it.isNotEmpty() }

        // server/state may carry a `controller` object for clients that
        // advertise the controller@v1 role. It reports group-level volume/mute
        // (distinct from per-player volume/mute, which arrives via
        // server/command) and the subset of MediaCommand values the
        // application backing the group accepts.
        val controllerState = (payload["controller"] as? JsonObject)?.let { controllerObj ->
            val volume = controllerObj["volume"]?.jsonPrimitive?.intOrNull
            val muted = controllerObj["muted"]?.jsonPrimitive?.booleanOrNull
            val supportedArray = controllerObj["supported_commands"]?.jsonArray
            if (volume == null || muted == null || supportedArray == null) {
                Log.w(TAG, "server/state.controller missing required fields")
                null
            } else if (volume !in 0..100) {
                Log.w(TAG, "server/state.controller.volume out of range: $volume")
                null
            } else {
                ControllerState(
                    supportedCommands = supportedArray.mapNotNull {
                        it.jsonPrimitive.contentOrNull
                    },
                    volume = volume,
                    muted = muted,
                )
            }
        }

        return ServerStateResult(metadata, state, controllerState)
    }

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
                val muted = player.booleanOrDefault("mute", false)
                ServerCommandResult.Mute(muted)
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
