package com.sendspindroid.sendspin.protocol.message

import com.sendspindroid.sendspin.protocol.ColorState
import com.sendspindroid.sendspin.protocol.ControllerState
import com.sendspindroid.sendspin.protocol.GroupInfo
import com.sendspindroid.sendspin.protocol.JsonOptional
import com.sendspindroid.sendspin.protocol.MoshiInstance
import com.sendspindroid.sendspin.protocol.orElse
import com.sendspindroid.sendspin.protocol.ServerCommandResult
import com.sendspindroid.sendspin.protocol.ServerHelloResult
import com.sendspindroid.sendspin.protocol.ServerStateResult
import com.sendspindroid.sendspin.protocol.StreamConfig
import com.sendspindroid.sendspin.protocol.SyncOffsetResult
import com.sendspindroid.sendspin.protocol.TimeMeasurement
import com.sendspindroid.sendspin.protocol.TrackMetadata
import com.sendspindroid.sendspin.protocol.TrackProgress
import com.sendspindroid.sendspin.protocol.wire.WireColor
import com.sendspindroid.sendspin.protocol.wire.WireController
import com.sendspindroid.sendspin.protocol.wire.WireGroupUpdatePayload
import com.sendspindroid.sendspin.protocol.wire.WireMetadata
import com.sendspindroid.sendspin.protocol.wire.WireProgress
import com.sendspindroid.sendspin.protocol.wire.WireProxyAuthResponse
import com.sendspindroid.sendspin.protocol.wire.WireRoles
import com.sendspindroid.sendspin.protocol.wire.WireServerCommandPayload
import com.sendspindroid.sendspin.protocol.wire.WireServerHelloPayload
import com.sendspindroid.sendspin.protocol.wire.WireServerStatePayload
import com.sendspindroid.sendspin.protocol.wire.WireServerTimePayload
import com.sendspindroid.sendspin.protocol.wire.WireStreamStartPayload
import com.sendspindroid.sendspin.protocol.wire.WireSyncOffsetPayload
import com.sendspindroid.shared.log.Log
import com.sendspindroid.shared.platform.Platform
import com.squareup.moshi.JsonAdapter

/**
 * Parses Sendspin server messages into domain types.
 *
 * Public methods take the message `payload` as a `Map<String, Any?>` (or any
 * Moshi raw JSON value: Map, List, String, Boolean, Number, null) — which is
 * exactly what the envelope adapter in [SendSpinProtocolHandler] hands back
 * after decoding the outer `{type, payload}` shape. Internally each method
 * defers to a KSP-generated Moshi adapter via [JsonAdapter.fromJsonValue],
 * so there is no string round-trip and no runtime reflection on the hot
 * path. Diff-merge and legacy-fallback logic for `server/state` lives below.
 *
 * Tests construct payloads directly with `mapOf("key" to value, ...)`; no
 * JSON library dependency is required at the call site.
 */
object MessageParser {
    private const val TAG = "MessageParser"

    private val helloAdapter by lazy {
        MoshiInstance.moshi.adapter(WireServerHelloPayload::class.java)
    }
    private val timeAdapter by lazy {
        MoshiInstance.moshi.adapter(WireServerTimePayload::class.java)
    }
    private val stateAdapter by lazy {
        MoshiInstance.moshi.adapter(WireServerStatePayload::class.java)
    }
    private val streamStartAdapter by lazy {
        MoshiInstance.moshi.adapter(WireStreamStartPayload::class.java)
    }
    private val serverCommandAdapter by lazy {
        MoshiInstance.moshi.adapter(WireServerCommandPayload::class.java)
    }
    private val groupUpdateAdapter by lazy {
        MoshiInstance.moshi.adapter(WireGroupUpdatePayload::class.java)
    }
    private val syncOffsetAdapter by lazy {
        MoshiInstance.moshi.adapter(WireSyncOffsetPayload::class.java)
    }
    private val proxyAuthResponseAdapter by lazy {
        MoshiInstance.moshi.adapter(WireProxyAuthResponse::class.java)
    }
    private val rolesAdapter by lazy {
        MoshiInstance.moshi.adapter(WireRoles::class.java)
    }

    private fun <T : Any> decode(payload: Any?, adapter: JsonAdapter<T>): T? {
        if (payload == null) return null
        return try {
            adapter.fromJsonValue(payload)
        } catch (e: Exception) {
            val preview = payload.toString().take(200)
            Log.w(TAG, "Failed to decode payload (${e.message}): $preview")
            null
        }
    }

    /**
     * Parses a Sendspin text-message envelope `{type, payload}` from JSON.
     * Returns `(type, payloadValue)` or null when the JSON is malformed or
     * the envelope is missing a `type` field. `payloadValue` is the raw
     * Moshi JSON value — typically `Map<String, Any?>` for object payloads,
     * suitable to feed straight into the parseXxx methods below.
     */
    fun parseEnvelope(text: String): Pair<String, Any?>? {
        return try {
            val map = MoshiInstance.envelopeAdapter.fromJson(text) ?: return null
            val type = map["type"] as? String ?: return null
            type to map["payload"]
        } catch (e: Exception) {
            Log.w(TAG, "Failed to decode envelope: ${e.message}")
            null
        }
    }

    fun parseServerHello(payload: Any?, defaultName: String): ServerHelloResult? {
        if (payload == null) {
            Log.e(TAG, "server/hello missing payload")
            return null
        }
        val wire = decode(payload, helloAdapter) ?: return null
        return ServerHelloResult(
            serverName = wire.name?.ifEmpty { defaultName } ?: defaultName,
            serverId = wire.serverId ?: "",
            activeRoles = wire.activeRoles ?: emptyList(),
            connectionReason = wire.connectionReason ?: "discovery",
        )
    }

    fun parseServerTime(payload: Any?, clientReceivedMicros: Long): TimeMeasurement? {
        val wire = decode(payload, timeAdapter) ?: return null
        val t1 = wire.clientTransmitted
        val t2 = wire.serverReceived
        val t3 = wire.serverTransmitted
        if (t1 == null || t2 == null || t3 == null) {
            Log.w(TAG, "Invalid server/time payload")
            return null
        }
        val offset = ((t2 - t1) + (t3 - clientReceivedMicros)) / 2
        val rtt = (clientReceivedMicros - t1) - (t3 - t2)
        return TimeMeasurement(offset, rtt, clientReceivedMicros)
    }

    fun parseServerState(
        payload: Any?,
        previous: TrackMetadata? = null,
        previousController: ControllerState? = null,
    ): ServerStateResult {
        val wire = decode(payload, stateAdapter)
            ?: return ServerStateResult(null, null, null, null)

        val metadata = wire.metadata?.let { it.toTrackMetadata(previous) }
        val state = wire.state?.takeIf { it.isNotEmpty() }
        val legacyRepeat = wire.metadata?.legacyRepeat
        val legacyShuffle = wire.metadata?.legacyShuffle
        // Prefer a valid controller object; but if one is present yet fails
        // validation (toControllerState returns null, e.g. out-of-range volume
        // or missing-required-fields with no previous), still fall through to
        // the legacy repeat/shuffle path so a legacy update riding alongside an
        // unusable controller object is not silently dropped.
        val fromController = wire.controller
            ?.toControllerState(legacyRepeat, legacyShuffle, previousController)
        val controllerState = fromController ?: when {
            // Legacy-only path: no usable controller object, but metadata carries
            // legacy repeat/shuffle. Surface those as a partial update on top of
            // the previous controller state (we don't have volume / muted /
            // commands to construct a fresh one from scratch).
            previousController != null &&
                (legacyRepeat is JsonOptional.Present || legacyShuffle is JsonOptional.Present) -> {
                val r: String? = legacyRepeat.orElse(previousController.repeat)
                val s: Boolean? = legacyShuffle.orElse(previousController.shuffle)
                previousController.copy(repeat = r, shuffle = s)
            }
            else -> null
        }
        val colorState = wire.color?.toColorState()
        return ServerStateResult(metadata, state, controllerState, colorState)
    }

    fun parseServerCommand(payload: Any?): ServerCommandResult? {
        val wire = decode(payload, serverCommandAdapter) ?: return null
        val player = wire.player ?: return null
        return when (val cmd = player.command) {
            "volume" -> {
                val v = player.volume
                if (v != null && v in 0..100) {
                    ServerCommandResult.Volume(v)
                } else {
                    Log.w(TAG, "server/command.volume missing or out of range: $v -- ignoring")
                    null
                }
            }
            "mute" -> {
                // `mute` carries the desired state. Missing field is malformed;
                // defaulting to `false` (unmute) would silently invert the
                // user's intent on a "set mute on, drop the value" send.
                val muted = player.mute
                if (muted == null) {
                    Log.w(TAG, "server/command.mute missing 'mute' field -- ignoring as malformed")
                    null
                } else {
                    ServerCommandResult.Mute(muted)
                }
            }
            null, "" -> null
            else -> ServerCommandResult.Unknown(cmd)
        }
    }

    fun parseGroupUpdate(payload: Any?): GroupInfo? {
        val wire = decode(payload, groupUpdateAdapter) ?: return null
        return GroupInfo(
            wire.groupId ?: "",
            wire.groupName ?: "",
            wire.playbackState ?: "",
        )
    }

    fun parseStreamStart(payload: Any?): StreamConfig? {
        val wire = decode(payload, streamStartAdapter) ?: return null
        val player = wire.player ?: return null
        val codecHeader = player.codecHeader?.let {
            try {
                Platform.base64Decode(it)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to decode codec_header")
                null
            }
        }
        return StreamConfig(
            codec = player.codec ?: "pcm",
            sampleRate = player.sampleRate ?: 48000,
            channels = player.channels ?: 2,
            bitDepth = player.bitDepth ?: 16,
            codecHeader = codecHeader,
        )
    }

    fun parseSyncOffset(payload: Any?): SyncOffsetResult? {
        val wire = decode(payload, syncOffsetAdapter) ?: return null
        // offset_ms is the meaningful payload of this message. A missing /
        // explicit-null offset_ms is malformed: silently defaulting to 0.0
        // would wipe any prior applied offset and produce an audible sync
        // transient. Reject the message and let the handler log/skip.
        val offset = wire.offsetMs ?: return null
        return SyncOffsetResult(
            wire.playerId ?: "",
            offset,
            wire.source ?: "unknown",
        )
    }

    /**
     * Parses the `roles` array shared by stream/end and stream/clear payloads.
     * Returns null when the payload is not an object, the `roles` key is absent,
     * or `roles` is not an array -- the caller treats null as "all roles". A
     * present array yields its string entries (non-string entries are dropped;
     * an empty array stays an empty list).
     */
    fun parseRoles(payload: Any?): List<String>? {
        val wire = decode(payload, rolesAdapter) ?: return null
        val raw = wire.roles ?: return null
        return raw.mapNotNull { it as? String }
    }

    /**
     * Parses a proxy-auth server response. Returns `(type, message)`; either
     * may be null when the field is absent. The full text is fed in (not just
     * a payload) because the response is a flat object — no envelope split.
     */
    fun parseProxyAuthResponse(text: String): Pair<String?, String?> {
        val wire = try {
            proxyAuthResponseAdapter.fromJson(text)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse proxy auth response (${e.message}): ${text.take(200)}")
            null
        }
        return (wire?.type to wire?.message)
    }

    // ── Wire → domain conversions ────────────────────────────────────────────

    /**
     * Maps the diff-style wire metadata to a [TrackMetadata], merging with the
     * previous accumulated value. The three [JsonOptional] cases per field:
     *  - Absent      → inherit from [previous] (or type default if first message)
     *  - Present(null) → clear to type default
     *  - Present(v)  → use v (but also coerce the literal string "null" to clear,
     *                   to handle legacy servers that emit the string sentinel)
     */
    private fun WireMetadata.toTrackMetadata(previous: TrackMetadata?): TrackMetadata {
        val title = title.mergeString(previous?.title)
        val artist = artist.mergeString(previous?.artist)
        val albumArtist = albumArtist.mergeString(previous?.albumArtist)
        val album = album.mergeString(previous?.album)
        val artworkUrl = artworkUrl.mergeString(previous?.artworkUrl)
        val year = year.mergeInt(previous?.year)
        val queueTrack = queueTrack.mergeInt(previous?.queueTrack)
        val totalTracks = totalTracks.mergeInt(previous?.totalTracks)
        val ts = timestamp
        val timestampValue =
            if (ts != null && ts != 0L) ts
            else previous?.timestamp ?: 0L

        // album_track ↔ legacy `track` resolution. Spec says "0 = not set",
        // so a present-but-zero album_track still falls back to legacy `track`
        // before finally inheriting from the previous metadata.
        val albumTrackValue: Int = when {
            albumTrack is JsonOptional.Present -> {
                val v = albumTrack.value ?: 0
                when {
                    v > 0 -> v
                    legacyTrack is JsonOptional.Present -> legacyTrack.value ?: 0
                    else -> previous?.albumTrack ?: 0
                }
            }
            legacyTrack is JsonOptional.Present -> legacyTrack.value ?: 0
            else -> previous?.albumTrack ?: 0
        }

        // Progress:
        //  - "progress" present as object → update from object fields, missing
        //    sub-fields inherit from previous (servers send partial updates)
        //  - "progress" present but null  → clear to defaults (server cleared_update path)
        //  - "progress" absent + legacy flat keys → legacy parse (missing legacy
        //    sub-fields inherit from previous)
        //  - "progress" absent, no legacy → inherit from previous
        val progressValue: TrackProgress = when (val p = progress) {
            is JsonOptional.Present -> {
                p.value?.toTrackProgress(previous?.progress) ?: TrackProgress(0L, 0L, 1000)
            }
            JsonOptional.Absent -> {
                if (legacyPositionMs is JsonOptional.Present ||
                    legacyDurationMs is JsonOptional.Present ||
                    legacyPlaybackSpeed is JsonOptional.Present
                ) {
                    TrackProgress(
                        trackProgress = (legacyPositionMs as? JsonOptional.Present)?.value
                            ?: previous?.progress?.trackProgress ?: 0L,
                        trackDuration = (legacyDurationMs as? JsonOptional.Present)?.value
                            ?: previous?.progress?.trackDuration ?: 0L,
                        playbackSpeed = (legacyPlaybackSpeed as? JsonOptional.Present)?.value
                            ?: previous?.progress?.playbackSpeed ?: 1000,
                    )
                } else {
                    previous?.progress ?: TrackProgress(0L, 0L, 1000)
                }
            }
        }

        return TrackMetadata(
            timestamp = timestampValue,
            title = title,
            artist = artist,
            albumArtist = albumArtist,
            album = album,
            artworkUrl = artworkUrl,
            year = year,
            albumTrack = albumTrackValue,
            queueTrack = queueTrack,
            totalTracks = totalTracks,
            progress = progressValue,
        )
    }

    private fun WireProgress.toTrackProgress(previous: TrackProgress?) =
        TrackProgress(
            trackProgress = trackProgress ?: previous?.trackProgress ?: 0L,
            trackDuration = trackDuration ?: previous?.trackDuration ?: 0L,
            playbackSpeed = playbackSpeed ?: previous?.playbackSpeed ?: 1000,
        )

    private fun WireController.toControllerState(
        legacyRepeat: JsonOptional<String>?,
        legacyShuffle: JsonOptional<Boolean>?,
        previous: ControllerState?,
    ): ControllerState? {
        // Required fields inherit from previous on partial updates: spec-PR50
        // servers may send `{"controller": {"volume": 70}}` to communicate that
        // only the group volume changed.
        val v = volume ?: previous?.volume
        val m = muted ?: previous?.muted
        val cmds = supportedCommands ?: previous?.supportedCommands
        if (v == null || m == null || cmds == null) {
            Log.w(TAG, "server/state.controller missing required fields and no previous to merge from")
            return null
        }
        if (v !in 0..100) {
            Log.w(TAG, "server/state.controller.volume out of range: $v")
            return null
        }
        // controller wins; metadata.{repeat,shuffle} is the legacy fallback.
        // Tri-state per field:
        //  - Present(v):    use v
        //  - Present(null): explicit clear from server (→ null)
        //  - Absent:        try the legacy field; otherwise inherit from previous
        val repeatValue: String? = when (val r = repeat) {
            is JsonOptional.Present -> r.value
            JsonOptional.Absent -> legacyRepeat.orElse(previous?.repeat)
        }
        val shuffleValue: Boolean? = when (val s = shuffle) {
            is JsonOptional.Present -> s.value
            JsonOptional.Absent -> legacyShuffle.orElse(previous?.shuffle)
        }
        return ControllerState(
            supportedCommands = cmds,
            volume = v,
            muted = m,
            repeat = repeatValue,
            shuffle = shuffleValue,
        )
    }

    private fun WireColor.toColorState(): ColorState =
        ColorState(
            timestamp = timestamp ?: 0L,
            backgroundDark = backgroundDark?.let(::validRgbTriple),
            backgroundLight = backgroundLight?.let(::validRgbTriple),
            primary = primary?.let(::validRgbTriple),
            accent = accent?.let(::validRgbTriple),
            onDark = onDark?.let(::validRgbTriple),
            onLight = onLight?.let(::validRgbTriple),
        )

    private fun validRgbTriple(list: List<Int>): List<Int>? {
        if (list.size != 3) return null
        if (list.any { it !in 0..255 }) return null
        return list
    }

    /**
     * String field merge: Absent inherits from previous; Present-null and the
     * literal string "null" both clear to "". Present-with-value uses the value.
     */
    private fun JsonOptional<String>.mergeString(prev: String?): String = when (this) {
        is JsonOptional.Absent -> prev ?: ""
        is JsonOptional.Present -> value?.takeUnless { it == "null" } ?: ""
    }

    /**
     * Int field merge: Absent inherits from previous; Present-null clears to 0;
     * Present-with-value uses the value.
     */
    private fun JsonOptional<Int>.mergeInt(prev: Int?): Int = when (this) {
        is JsonOptional.Absent -> prev ?: 0
        is JsonOptional.Present -> value ?: 0
    }
}
