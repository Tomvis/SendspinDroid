package com.sendspindroid.sendspin.protocol

/**
 * SendSpin Protocol constants and data classes.
 *
 * Protocol spec: https://www.sendspin-audio.com/spec/
 */
object SendSpinProtocol {
    const val VERSION = 1
    const val ENDPOINT_PATH = "/sendspin"

    /**
     * Binary message header: 1 byte type + 8 bytes big-endian int64 timestamp.
     */
    const val BINARY_HEADER_SIZE_BYTES = 9

    /**
     * Binary message type identifiers.
     */
    object BinaryType {
        const val AUDIO = 4
        const val ARTWORK_BASE = 8  // 8-11 for channels 0-3
        const val VISUALIZER = 16
        const val VISUALIZER_BEAT = 17
    }

    /**
     * Valid values for the `static_delay_ms` wire field. Per spec, this is an
     * unsigned millisecond integer in [0, 5000]. Negative offsets must be
     * applied client-side only.
     */
    object StaticDelay {
        const val MIN_MS = 0
        const val MAX_MS = 5000
    }

    /**
     * Valid values for the top-level `state` field on `client/state` messages.
     */
    object ClientState {
        const val SYNCHRONIZED = "synchronized"
        const val ERROR = "error"
        const val EXTERNAL_SOURCE = "external_source"
    }

    /**
     * Valid values for the `reason` field on `client/goodbye` messages.
     * The server validates this against an enum; any other value drops the
     * connection with a noisy exception trace.
     */
    object GoodbyeReason {
        const val ANOTHER_SERVER = "another_server"
        const val SHUTDOWN = "shutdown"
        const val RESTART = "restart"
        const val USER_REQUEST = "user_request"
    }

    /**
     * Audio format constants.
     */
    object AudioFormat {
        const val SAMPLE_RATE = 48000
        const val CHANNELS = 2
        const val CHANNELS_MONO = 1
        const val BIT_DEPTH = 16
        const val DEFAULT_CODEC = "pcm"
    }

    /**
     * Artwork request constants for client/hello handshake.
     */
    object Artwork {
        const val REQUEST_SIZE = 500  // Requested artwork width/height in pixels
    }

    /**
     * Time synchronization constants.
     *
     * Uses NTP-style best-of-N: send N packets, pick the one with lowest RTT.
     * This filters out network jitter by selecting the measurement with least congestion.
     */
    object TimeSync {
        const val INTERVAL_MS = 250L          // Send time sync 4x per second
        const val BURST_COUNT = 10            // Send 10 packets per burst
        const val BURST_DELAY_MS = 50L        // 50ms between burst packets
    }

    /**
     * Buffer duration targets (seconds).
     *
     * The server's BufferTracker paces delivery by wire bytes; we calculate
     * the byte cap from these durations using the highest-bitrate PCM format
     * we advertise. This keeps decoded-PCM memory bounded regardless of codec:
     * - PCM: ~DURATION seconds in memory
     * - FLAC (~50% compression): ~2x DURATION seconds, still reasonable
     */
    object Buffer {
        const val DURATION_NORMAL_SEC = 35    // 30s target + 5s sync headroom
        const val DURATION_LOW_MEM_SEC = 10
    }

    /**
     * Protocol message type identifiers.
     */
    object MessageType {
        const val CLIENT_HELLO = "client/hello"
        const val SERVER_HELLO = "server/hello"
        const val CLIENT_TIME = "client/time"
        const val SERVER_TIME = "server/time"
        const val CLIENT_STATE = "client/state"
        const val SERVER_STATE = "server/state"
        const val CLIENT_COMMAND = "client/command"
        const val SERVER_COMMAND = "server/command"
        const val CLIENT_GOODBYE = "client/goodbye"
        const val GROUP_UPDATE = "group/update"
        const val STREAM_START = "stream/start"
        const val STREAM_END = "stream/end"
        const val STREAM_CLEAR = "stream/clear"
        const val CLIENT_SYNC_OFFSET = "client/sync_offset"
    }

    /**
     * Supported client roles (versioned role IDs sent in client/hello.supported_roles
     * and received in server/hello.active_roles).
     */
    object Roles {
        const val PLAYER = "player@v1"
        const val CONTROLLER = "controller@v1"
        const val METADATA = "metadata@v1"
        const val ARTWORK = "artwork@v1"
        const val COLOR = "color@v1"
    }

    /**
     * Unversioned role family names. The server emits these in role-scoped
     * payloads such as `stream/end.roles` and `stream/clear.roles`, where the
     * version is intentionally elided so a single message can target every
     * version of a role family. Do NOT use [Roles] (which carries `@vN`) when
     * comparing against those fields.
     */
    object RoleFamily {
        const val PLAYER = "player"
        const val CONTROLLER = "controller"
        const val METADATA = "metadata"
        const val ARTWORK = "artwork"
        const val VISUALIZER = "visualizer"
        const val COLOR = "color"
    }

    /**
     * Valid values for `controller.repeat` per spec.
     */
    object RepeatMode {
        const val OFF = "off"
        const val ONE = "one"
        const val ALL = "all"
    }
}

/**
 * A time sync measurement from NTP-style exchange.
 */
data class TimeMeasurement(
    val offset: Long,
    val rtt: Long,
    val clientReceived: Long
)

/**
 * Progress information from server/state metadata.
 * Per spec: nested progress object with track_progress, track_duration, playback_speed.
 *
 * @param trackProgress Current position in milliseconds
 * @param trackDuration Total track duration in milliseconds
 * @param playbackSpeed Speed multiplier (1000 = 1.0x normal speed)
 */
data class TrackProgress(
    val trackProgress: Long,
    val trackDuration: Long,
    val playbackSpeed: Int = 1000  // Default to normal speed
)

/**
 * Track metadata from server/state messages.
 * Per spec: includes timestamp, nested progress, and optional fields.
 *
 * Integer fields use 0 to indicate "not set" / absent.
 *
 * @param timestamp Server timestamp when metadata was captured (microseconds)
 * @param title Track title
 * @param artist Track artist
 * @param albumArtist Album artist (may differ from track artist for compilations)
 * @param album Album name
 * @param artworkUrl URL to album artwork
 * @param year Release year
 * @param albumTrack Track number within album (1-indexed)
 * @param queueTrack Position of the current track within the queue (1-indexed)
 * @param totalTracks Total number of tracks in the queue
 * @param progress Progress information (position, duration, speed)
 */
data class TrackMetadata(
    val timestamp: Long,
    val title: String,
    val artist: String,
    val albumArtist: String,
    val album: String,
    val artworkUrl: String,
    val year: Int,
    val albumTrack: Int,
    val queueTrack: Int,
    val totalTracks: Int,
    val progress: TrackProgress
) {
    // Convenience properties for backwards compatibility
    val durationMs: Long get() = progress.trackDuration
    val positionMs: Long get() = progress.trackProgress
}

/**
 * Audio stream configuration from stream/start messages.
 */
data class StreamConfig(
    val codec: String,
    val sampleRate: Int,
    val channels: Int,
    val bitDepth: Int,
    val codecHeader: ByteArray?
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is StreamConfig) return false

        if (codec != other.codec) return false
        if (sampleRate != other.sampleRate) return false
        if (channels != other.channels) return false
        if (bitDepth != other.bitDepth) return false
        if (codecHeader != null) {
            if (other.codecHeader == null) return false
            if (!codecHeader.contentEquals(other.codecHeader)) return false
        } else if (other.codecHeader != null) return false

        return true
    }

    override fun hashCode(): Int {
        var result = codec.hashCode()
        result = 31 * result + sampleRate
        result = 31 * result + channels
        result = 31 * result + bitDepth
        result = 31 * result + (codecHeader?.contentHashCode() ?: 0)
        return result
    }
}

/**
 * Group information from group/update messages.
 */
data class GroupInfo(
    val groupId: String,
    val groupName: String,
    val playbackState: String
)

/**
 * Result from parsing server/hello message.
 */
data class ServerHelloResult(
    val serverName: String,
    val serverId: String,
    val activeRoles: List<String>,
    val connectionReason: String
)

/**
 * Result from parsing server/command message.
 */
sealed class ServerCommandResult {
    data class Volume(val volume: Int) : ServerCommandResult()
    data class Mute(val muted: Boolean) : ServerCommandResult()
    data class Unknown(val command: String) : ServerCommandResult()
}

/**
 * Group-level controller state from `server/state.controller`. Sent to clients
 * that advertise the `controller@v1` role. Reports which media commands the
 * application backing the group supports plus the group's current volume and
 * mute. Distinct from per-player volume/mute (which arrives via
 * `server/command`).
 *
 * @param supportedCommands MediaCommand string values the application accepts
 *     (e.g. "play", "pause", "next", "repeat_all"). Subset of the
 *     spec-defined MediaCommand enum.
 * @param volume Group volume, 0-100.
 * @param muted Group mute state.
 * @param repeat Repeat mode ("off"/"one"/"all"). Null means the server did not
 *     report a value (older server, or absent from this update). The wire
 *     position is `controller.repeat`; older servers emit it on
 *     `metadata.repeat` as a fallback. A future migration to JsonOptional&lt;T&gt;
 *     will distinguish "absent" from "explicit null".
 * @param shuffle Shuffle state. Null means the server did not report a value.
 *     Wire position is `controller.shuffle`; older servers emit on
 *     `metadata.shuffle`.
 */
data class ControllerState(
    val supportedCommands: List<String>,
    val volume: Int,
    val muted: Boolean,
    val repeat: String? = null,
    val shuffle: Boolean? = null,
)

/**
 * Color palette extracted from the currently-playing artwork. Sent on
 * `server/state.color` to clients that advertise the `color@v1` role.
 *
 * Each color is a list of three integers in 0..255 (RGB). Fields are nullable
 * because the server may emit a partial palette (only the colors it could
 * extract for the current artwork). [timestamp] is the server-side capture
 * time in microseconds.
 *
 * Equality intentionally excludes [timestamp]: the server regenerates the
 * timestamp on every color emission (typically once per metadata or artwork
 * tick), but the palette itself rarely changes within a track. Consumers
 * compare ColorStates for change-detection dedup, and including timestamp
 * would defeat that dedup and trigger spurious downstream work (ShaderBrush
 * re-allocation, ambient repaint).
 */
class ColorState(
    val timestamp: Long = 0L,
    val backgroundDark: List<Int>? = null,
    val backgroundLight: List<Int>? = null,
    val primary: List<Int>? = null,
    val accent: List<Int>? = null,
    val onDark: List<Int>? = null,
    val onLight: List<Int>? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ColorState) return false
        return backgroundDark == other.backgroundDark &&
            backgroundLight == other.backgroundLight &&
            primary == other.primary &&
            accent == other.accent &&
            onDark == other.onDark &&
            onLight == other.onLight
    }

    override fun hashCode(): Int {
        var result = backgroundDark?.hashCode() ?: 0
        result = 31 * result + (backgroundLight?.hashCode() ?: 0)
        result = 31 * result + (primary?.hashCode() ?: 0)
        result = 31 * result + (accent?.hashCode() ?: 0)
        result = 31 * result + (onDark?.hashCode() ?: 0)
        result = 31 * result + (onLight?.hashCode() ?: 0)
        return result
    }
}

/**
 * Result from parsing server/state message.
 */
data class ServerStateResult(
    val metadata: TrackMetadata?,
    val state: String?,
    val controllerState: ControllerState?,
    val colorState: ColorState? = null,
)

/**
 * Result from parsing client/sync_offset message.
 */
data class SyncOffsetResult(
    val playerId: String,
    val offsetMs: Double,
    val source: String
)
