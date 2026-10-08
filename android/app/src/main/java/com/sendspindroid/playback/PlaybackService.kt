package com.sendspindroid.playback

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.database.ContentObserver
import android.media.AudioAttributes as AndroidAudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.provider.Settings
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import coil.ImageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import android.net.Uri
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import com.sendspindroid.MainActivity

import com.sendspindroid.SyncOffsetPreference
import com.sendspindroid.ui.settings.SettingsViewModel
import com.sendspindroid.coordinator.ConnectionCoordinator
import com.sendspindroid.coordinator.FailureReason
import com.sendspindroid.coordinator.ReconnectStatus
import com.sendspindroid.coordinator.TransportState
import com.sendspindroid.diagnostics.HandoffEpisodeRecorder
import com.sendspindroid.diagnostics.Telemetry
import com.sendspindroid.logging.AppLog
import com.sendspindroid.logging.LogLevel
import com.sendspindroid.model.PlaybackState
import com.sendspindroid.model.PlaybackStateType
import com.sendspindroid.model.SyncStats
import com.sendspindroid.model.UnifiedServer
import com.sendspindroid.sendspin.SendSpin
import com.sendspindroid.sendspin.protocol.AdmissionState
import com.sendspindroid.sendspin.SendSpinEndpoint
import com.sendspindroid.discovery.NsdDiscoveryManager
import com.sendspindroid.UnifiedServerRepository
import com.sendspindroid.UserSettings
import com.sendspindroid.sendspin.SyncAudioPlayer
import com.sendspindroid.sendspin.SyncAudioPlayerCallback
import com.sendspindroid.sendspin.PlaybackState as SyncPlaybackState
import com.sendspindroid.sendspin.decoder.AudioDecoder
import com.sendspindroid.sendspin.protocol.SendSpinProtocol
import com.sendspindroid.sendspin.protocol.TrackMetadata
import com.sendspindroid.sendspin.protocol.StreamConfig
import com.sendspindroid.sendspin.decoder.AudioDecoderFactory
import com.sendspindroid.network.ConnectionSelector
import com.sendspindroid.network.NetworkEvaluator
import com.sendspindroid.network.NetworkState
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.DefaultMediaNotificationProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToInt

/**
 * Background playback service for SendSpinDroid.
 *
 * Extends MediaLibraryService to provide:
 * - Background audio playback (screen off, app minimized)
 * - System media integration (notifications, lock screen controls)
 * - Audio focus handling (pause the group for calls, mute for other audio)
 * - Bluetooth/headset button support
 * - Android Auto browse tree support
 *
 * ## Architecture
 * ```
 * MainActivity --MediaController--> PlaybackService
 *                                        |
 *                                   +----+----+
 *                                   | SendSpin        |
 *                                   | SyncAudioPlayer |
 *                                   | MediaSession    |
 *                                   +-----------------+
 * ```
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaLibraryService() {

    private var mediaSession: MediaLibrarySession? = null
    private var sendSpinPlayer: SendSpinPlayer? = null
    private var forwardingPlayer: MetadataForwardingPlayer? = null

    // Fingerprint of the last bundle pushed via setSessionExtras. Used to
    // skip redundant broadcasts (which trigger AvrcpMediaPlayerWrapper "tried
    // to update with no new data" warnings from the system Bluetooth stack
    // every time we re-push an identical bundle). The bundle is rebuilt and
    // pushed from many call sites -- metadata updates, volume changes, sync
    // offset, periodic server/time messages -- and many of those produce
    // identical contents.
    private var lastSessionExtrasFingerprint: Long = Long.MIN_VALUE
    private var sendSpinClient: SendSpin? = null
    @Volatile private var syncAudioPlayer: SyncAudioPlayer? = null
    // Owned exclusively by the decode worker coroutine (serialized on
    // decodeDispatcher). Single-writer invariant: all mutations happen
    // inside handleDecodeStartStream / handleDecodeRelease, both of which
    // run on decodeDispatcher. No volatile / lock needed because no other
    // thread reads or writes this field.
    private var audioDecoder: AudioDecoder? = null

    private var currentCodec: String = "pcm"  // Track current stream codec for stats
    private var currentSampleRate: Int = 0
    private var currentChannels: Int = 0
    private var currentBitDepth: Int = 0

    /**
     * Reset the audio stream spec to "no active stream". Called on disconnect
     * so the Now Playing spec chips disappear; not called on stream/end (pause)
     * since the chips should remain visible while paused.
     */
    private fun clearAudioStreamSpec() {
        currentCodec = "pcm"
        currentSampleRate = 0
        currentChannels = 0
        currentBitDepth = 0
    }

    // Active server as a flow, consumed by ConnectionCoordinator.
    private val _currentServerFlow = MutableStateFlow<UnifiedServer?>(null)

    private lateinit var coordinator: ConnectionCoordinator

    // Records network-handoff episodes (drop + reconnect attempts + outcome) from
    // the coordinator's reconnect status, surfaced in getStats() / bug reports and
    // submitted to opt-in telemetry as each episode closes.
    private val handoffRecorder = HandoffEpisodeRecorder().apply {
        onEpisodeClosed = { episode -> Telemetry.submit(episode) }
    }

    // mDNS discovery for Android Auto browse tree
    private var browseDiscoveryManager: NsdDiscoveryManager? = null

    // Generation counter for fetchArtwork() / onArtwork() so an in-flight
    // bitmap decode/fetch that finishes after a disconnect or track change
    // doesn't resurrect the prior track's artwork on lock screen / Android
    // Auto. Bumped whenever urlArtwork / binaryArtwork are cleared; the
    // async completion captures the value at launch and drops late writes
    // when the field has moved on.
    //
    // @Volatile because onArtwork captures this on the WebSocket thread
    // while writes happen on the main thread; without it the WS read isn't
    // guaranteed to observe the latest write, and long-typed access isn't
    // atomic on 32-bit ARM.
    @Volatile
    private var artworkGeneration = 0L
    // mDNS discovery while the reconnect loop runs: a server that announces
    // itself again is retried at once instead of at the end of a backoff.
    private var reconnectDiscoveryManager: NsdDiscoveryManager? = null

    /** Bridges a suspend function into a ListenableFuture for MediaLibrarySession callbacks. */
    private fun <T> suspendToFuture(block: suspend () -> T): ListenableFuture<T> {
        val future = SettableFuture.create<T>()
        serviceScope.launch {
            try {
                future.set(block())
            } catch (e: Exception) {
                future.setException(e)
            }
        }
        return future
    }

    // Handler for posting callbacks to main thread
    private val mainHandler = Handler(Looper.getMainLooper())

    // BroadcastReceiver for sync offset changes from settings
    private val syncOffsetReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val offsetMs = intent.getIntExtra(SyncOffsetPreference.EXTRA_OFFSET_MS, 0)
            sendSpinClient?.getTimeFilter()?.let { timeFilter ->
                timeFilter.setUserSyncOffsetMs(offsetMs.toDouble())
                Log.i(TAG, "Applied sync offset from settings change: ${offsetMs}ms")
            }
        }
    }

    // BroadcastReceiver for log level changes from settings
    private val logLevelReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val levelStr = intent.getStringExtra(SettingsViewModel.EXTRA_LOG_LEVEL) ?: return
            val level = runCatching { LogLevel.valueOf(levelStr) }.getOrDefault(LogLevel.OFF)
            Log.i(TAG, "Log level changed: $level")

            if (level != LogLevel.OFF && isConnected()) {
                startDebugLogging()
            } else {
                stopDebugLogging()
            }
        }
    }

    // BroadcastReceiver for High Power Mode toggle changes from settings
    private val highPowerModeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val enabled = intent.getBooleanExtra(
                SettingsViewModel.EXTRA_HIGH_POWER_MODE_ENABLED, false
            )
            Log.i(TAG, "High Power Mode changed: $enabled")
            onHighPowerModeChanged(enabled)
        }
    }

    // BroadcastReceiver for preferred codec changes from settings: report the
    // new preference as `format` in client/state instead of waiting for the
    // next connect. The server replies with stream/start, which flows through
    // the normal format-change reconfiguration path.
    private val preferredCodecReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val codec = intent.getStringExtra(SettingsViewModel.EXTRA_PREFERRED_CODEC) ?: return
            if (!AudioDecoderFactory.isCodecSupported(codec)) {
                Log.w(TAG, "Preferred codec changed to unsupported '$codec' - not requesting")
                return
            }
            Log.i(TAG, "Preferred codec changed: $codec - reporting the new format preference")
            sendSpinClient?.setPreferredCodec(codec)
        }
    }

    // Flag to prevent callbacks from executing after service is destroyed
    @Volatile
    private var isDestroyed = false

    // Tracks whether any of our activities is currently visible (Lifecycle
    // STARTED or above). Updated by an observer on ProcessLifecycleOwner.
    // Read from onMediaButtonEvent on the session callback thread, so
    // @Volatile to publish writes across threads.
    @Volatile
    private var appInForeground: Boolean = false

    // Held so onDestroy can removeObserver it. ProcessLifecycleOwner is a
    // process-singleton: without explicit removal each service create/destroy
    // cycle leaks one observer plus the service instance it captures via the
    // appInForeground closure.
    private var appLifecycleObserver: androidx.lifecycle.DefaultLifecycleObserver? = null

    // Guards against race condition: onAudioChunk runs on WebSocket thread but
    // decoder creation is posted to mainHandler. Chunks arriving before the new
    // decoder is ready would hit the old (released) decoder and throw.
    @Volatile
    private var decoderReady = false

    // Identifies the stream a chunk belongs to. onAudioChunk snapshots
    // it on WS-IO at enqueue time and the decode worker re-checks it before
    // decoding, so chunks from a superseded stream are discarded no matter how
    // long they sat on the main dispatch queue or in decodeChannel. Ordering
    // and drains can both be defeated by in-flight work; a tag cannot.
    // Single-writer: only the WS-IO stream callbacks mutate it.
    @Volatile
    private var decodeGeneration = 0

    // Configuration of the active player stream, null when none is active.
    // Tells a stream/start that begins a stream from one that reconfigures
    // the stream already playing, which must keep its buffered audio.
    @Volatile
    private var activeStreamConfig: StreamConfig? = null

    // Playback state exposed as StateFlow (like Python CLI's AppState)
    private val _playbackState = MutableStateFlow(PlaybackState())
    val playbackState: StateFlow<PlaybackState> = _playbackState.asStateFlow()

    // Sync offset state (included in broadcastSessionExtras to avoid bare-bundle overwrites)
    private var lastSyncOffsetMs: Double = 0.0
    private var lastSyncOffsetSource: String = ""

    // Artwork state. Two independent sources are maintained so the lock-screen
    // widget and app UI don't diverge when the SendSpin server sends a binary
    // artwork payload whose contents don't match the `artwork_url` in the
    // accompanying `server/state` metadata (observed with MA in playlist
    // contexts; see docs/architecture/sendspin-ma-metadata-flow.md §7 Q4/Q5).
    //
    // Policy: while the track's metadata carries an `artwork_url`, only the
    // image fetched from that URL is shown, matching what the app's own
    // mini-player already does via Coil on the `artworkUrl` state. Binary
    // artwork is shown only while the metadata carries no URL.
    //
    // The two are never mixed, and each has one owner. urlArtwork belongs to
    // lastArtworkUrl and is dropped when that changes. binaryArtwork is the
    // artwork stream's current image (roles/artwork/v1.md): the server sends
    // an image once and clears it explicitly, so it is replaced or cleared
    // only by onArtwork/onArtworkCleared, never by a metadata update.
    //
    // effectiveArtwork is recomputed on every write and passed to MediaSession.
    private var lastArtworkUrl: String? = null
    private var lastTrackTitle: String? = null
    private var urlArtwork: Bitmap? = null
    private var binaryArtwork: Bitmap? = null
        set(value) {
            field = value
            // Fork: the unscaled bytes go with the bitmap made from them.
            if (value == null) binaryArtworkFullRes = null
        }
    // Bumped whenever binaryArtwork is superseded, so a decode still running
    // for an older image cannot bring it back. Main thread only.
    private var binaryArtworkGeneration = 0
    private val effectiveArtwork: Bitmap?
        get() = if (lastArtworkUrl != null) urlArtwork else binaryArtwork
    // ImageLoader is null when low memory mode is enabled
    private var imageLoader: ImageLoader? = null

    // Coroutine scope for background tasks (artwork loading)
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // Decode worker: single-thread-equivalent coroutine that owns the
    // decoder for its lifetime. See
    // docs/superpowers/specs/2026-04-23-codec-safe-decoder-redesign-design.md
    // for the H-4 + M-8 rationale.
    @kotlin.OptIn(ExperimentalCoroutinesApi::class)
    private val decodeDispatcher = Dispatchers.IO.limitedParallelism(1)

    // Capacity = 1000 ~= 20 seconds at the expected 50 chunks/sec. Sized to
    // absorb cold-start + pre-buffered bursts (~100 entries) with 10x headroom.
    private val decodeChannel = Channel<DecodeTask>(capacity = 1000)

    private var decodeJob: Job? = null

    // Wake lock to prevent CPU sleep during playback
    private var wakeLock: PowerManager.WakeLock? = null

    // WiFi lock to prevent WiFi from going to sleep during playback
    private var wifiLock: WifiManager.WifiLock? = null

    // High Power Mode locks - separate from streaming locks to avoid lifecycle interference
    // These span the entire connection (not just streaming) when High Power Mode is enabled
    private var highPowerWakeLock: PowerManager.WakeLock? = null
    private var highPowerWifiLock: WifiManager.WifiLock? = null

    // Handler for periodic wake lock refresh
    private val wakeLockHandler = Handler(Looper.getMainLooper())

    // Wake lock refresh runnable - refreshes wake lock periodically during active playback
    private val wakeLockRefreshRunnable = object : Runnable {
        override fun run() {
            // Prevent callback execution after service destruction
            if (isDestroyed) return
            refreshWakeLock()
            // Schedule next refresh if still playing and not destroyed
            if (!isDestroyed && isActivelyPlaying()) {
                wakeLockHandler.postDelayed(this, WAKE_LOCK_REFRESH_INTERVAL_MS)
            }
        }
    }

    // High Power wake lock refresh runnable - keeps wake lock alive for entire connection
    // Unlike streaming refresh, this runs as long as connected (not just while playing)
    private val highPowerWakeLockRefreshRunnable = object : Runnable {
        override fun run() {
            if (isDestroyed) return
            if (highPowerWakeLock?.isHeld == true) {
                highPowerWakeLock?.release()
                highPowerWakeLock?.acquire(WAKE_LOCK_TIMEOUT_MS)
                Log.d(TAG, "High Power wake lock refreshed with ${WAKE_LOCK_TIMEOUT_MS / 60000}min timeout")
            }
            if (!isDestroyed && highPowerWakeLock?.isHeld == true) {
                wakeLockHandler.postDelayed(this, WAKE_LOCK_REFRESH_INTERVAL_MS)
            }
        }
    }

    // Debug logging handler - logs stats periodically when debug mode is enabled
    private val debugLogHandler = Handler(Looper.getMainLooper())
    private val debugLogRunnable = object : Runnable {
        override fun run() {
            // Prevent callback execution after service destruction
            if (isDestroyed) return
            if (AppLog.level != LogLevel.OFF && isConnected()) {
                logCurrentStats()
                debugLogHandler.postDelayed(this, DEBUG_LOG_INTERVAL_MS)
            }
        }
    }

    private var networkEvaluator: NetworkEvaluator? = null

    // AudioManager for device volume control (Spotify-style hybrid approach)
    private var audioManager: AudioManager? = null
    private var volumeObserver: ContentObserver? = null
    private var volumeObserverRegistered: Boolean = false  // Track registration state to prevent leaks
    private var lastKnownVolume: Int = -1  // Track to detect external volume changes

    // Audio focus management - required for Android Auto to hand over audio output.
    // requestAudioFocus() / abandonAudioFocus() can run on the Media3 session
    // callback thread (via onPlayerCommandRequest) as well as the Main thread
    // (via the focus-change listener that posts back to mainHandler), so the
    // fields are @Volatile and the request/abandon paths synchronize on
    // audioFocusLock to keep lazy init and state writes atomic.
    private val audioFocusLock = Any()
    @Volatile
    private var audioFocusRequest: AudioFocusRequest? = null
    @Volatile
    private var hasAudioFocus: Boolean = false
    // True between requestAudioFocus() and abandonAudioFocus(), independent of
    // whether the system has currently granted focus. AUDIOFOCUS_LOSS_TRANSIENT
    // flips hasAudioFocus to false (so we suppress media-button presses) but
    // the listener is still registered with AudioManager -- we must still
    // abandon on teardown or the listener (capturing this service) leaks.
    @Volatile
    private var audioFocusRegistered: Boolean = false

    // What another app taking the output has done to us (main thread only).
    // See AudioInterruptionPolicy.
    private var interruptionMuted = false   // output muted for a focus loss or output disconnect
    private var pausedForCall = false       // we sent 'pause' for a call and nobody has changed playback since
    private var transientFocusLoss: AudioInterruption? = null  // transient loss still in force

    // A call can take focus slightly before the audio mode says it is a call
    // (and a VoIP app may only switch mode when answered), so while a transient
    // loss is being handled as plain audio the mode is polled, and the loss is
    // decided again once the mode says it is a call.
    private val callModeRecheckRunnable = object : Runnable {
        override fun run() {
            val loss = transientFocusLoss ?: return
            if (AudioInterruptionPolicy.isCallMode(audioManager?.mode ?: AudioManager.MODE_NORMAL)) {
                handleAudioInterruption(loss)
            } else {
                mainHandler.postDelayed(this, CALL_MODE_RECHECK_MS)
            }
        }
    }

    // Receiver for ACTION_AUDIO_BECOMING_NOISY: fired when the audio output is
    // rerouting to the built-in speaker because an external output disconnected
    // (wired headphones unplugged, Bluetooth/Android Auto disconnected). We pause
    // the group so we don't abruptly blast the phone speaker.
    private var becomingNoisyReceiver: BroadcastReceiver? = null

    companion object {
        // The artist image the server sends on its own artwork channel, for
        // the second page of the album art card. In-process: it is a few
        // tens of kilobytes that only the activity wants, not session
        // metadata.
        private val _artistArtwork = MutableStateFlow<ByteArray?>(null)
        val artistArtwork: StateFlow<ByteArray?> = _artistArtwork.asStateFlow()

        private const val TAG = "PlaybackService"

        // Every focus change, the audio mode at that moment and the action
        // taken are logged at INFO under this tag: adb logcat -s AudioFocus
        private const val FOCUS_TAG = "AudioFocus"
        private const val CALL_MODE_RECHECK_MS = 1000L

        // Wake lock refresh strategy:
        // - Use a 30-minute timeout so if release fails (crash, etc.), max battery drain is 30 min
        // - Refresh every 20 minutes during active playback to keep it alive
        // - This is safer than a 10-hour timeout with no refresh
        private const val WAKE_LOCK_TIMEOUT_MS = 30 * 60 * 1000L  // 30 minutes
        private const val WAKE_LOCK_REFRESH_INTERVAL_MS = 20 * 60 * 1000L  // 20 minutes

        // Debug logging interval (1 sample per second)
        private const val DEBUG_LOG_INTERVAL_MS = 1000L

        // Timeout for awaiting a terminal connection state in connectViaSelectedConnection.
        private const val CONNECT_TIMEOUT_MS = 15_000L

        // How long the playback locks (CPU, Wi-Fi, audio focus) held when a
        // connection dropped are kept for the reconnect loop. Long enough for
        // a server restart or a network blip with the screen off; after it the
        // loop carries on whenever the device is awake.
        private const val RECONNECT_LOCK_HOLD_MS = 10 * 60 * 1000L

        // How long boot auto-connect waits for mDNS to re-resolve a saved local
        // server's current address before falling back to the stored one (#158).
        private const val MDNS_AUTOCONNECT_TIMEOUT_MS = 5_000L

        // Poll interval while an output format switch waits for the audio
        // buffered in the previous format to play out.
        private const val FORMAT_SWITCH_POLL_MS = 20L

        // Custom session commands
        const val COMMAND_CONNECT = "com.sendspindroid.CONNECT"
        const val COMMAND_DISCONNECT = "com.sendspindroid.DISCONNECT"
        const val COMMAND_SET_VOLUME = "com.sendspindroid.SET_VOLUME"
        const val COMMAND_NEXT = "com.sendspindroid.NEXT"
        const val COMMAND_PREVIOUS = "com.sendspindroid.PREVIOUS"
        const val COMMAND_SWITCH_GROUP = "com.sendspindroid.SWITCH_GROUP"
        const val COMMAND_GET_STATS = "com.sendspindroid.GET_STATS"
        const val COMMAND_ALLOW_PAIRING = "com.sendspindroid.ALLOW_PAIRING"

        // Intent actions for service start (used by BootReceiver)
        const val ACTION_AUTO_CONNECT = "com.sendspindroid.ACTION_AUTO_CONNECT"
        const val EXTRA_SERVER_ID = "server_id_auto_connect"

        // Command arguments
        const val ARG_SERVER_ADDRESS = "server_address"
        const val ARG_SERVER_PATH = "server_path"
        const val ARG_VOLUME = "volume"
        const val ARG_SERVER_ID = "server_id"

        /**
         * Fork: unscaled artwork-stream image behind the session's <=300px
         * bitmap, or null. The MediaSession only carries the small copy, so
         * the in-process now-playing UI reads the full-size one from here.
         */
        @Volatile
        var binaryArtworkFullRes: ByteArray? = null
            private set

        // Session extras keys for metadata (service → controller)
        const val EXTRA_TITLE = "title"
        const val EXTRA_ARTIST = "artist"
        const val EXTRA_ALBUM_ARTIST = "album_artist"
        const val EXTRA_ALBUM = "album"
        const val EXTRA_ARTWORK_URL = "artwork_url"
        const val EXTRA_YEAR = "year"
        const val EXTRA_ALBUM_TRACK = "album_track"
        const val EXTRA_QUEUE_TRACK = "queue_track"
        const val EXTRA_TOTAL_TRACKS = "total_tracks"
        const val EXTRA_DURATION_MS = "duration_ms"
        const val EXTRA_POSITION_MS = "position_ms"
        const val EXTRA_POSITION_UPDATED_AT = "position_updated_at"
        const val EXTRA_ARTWORK_DATA = "artwork_data"

        // Session extras keys for connection state
        const val EXTRA_CONNECTION_STATE = "connection_state"
        const val EXTRA_SERVER_NAME = "server_name"
        const val EXTRA_ERROR_MESSAGE = "error_message"

        /**
         * Why a connected session cannot play, as an [AdmissionState] name.
         * Only present while EXTRA_CONNECTION_STATE is STATE_CONNECTED - it
         * describes an accepted activation, which no other state has.
         */
        const val EXTRA_ADMISSION_STATE = "admission_state"

        /**
         * The current dynamic pairing code, present only while one is being
         * shown to the operator. Rides the same STATE_CONNECTED branch as
         * EXTRA_ADMISSION_STATE, for the same reason.
         */
        const val EXTRA_PAIRING_CODE = "pairing_code"

        /**
         * Whether a dynamic pairing attempt is gesture-gated and waiting on
         * the "Allow pairing" gesture. Absent value reads as false.
         */
        const val EXTRA_PAIRING_GESTURE_REQUESTED = "pairing_gesture_requested"

        // Session extras keys for volume (server → controller)
        const val EXTRA_VOLUME = "volume"

        // Audio stream spec (populated on stream/start, cleared on stream/end)
        const val EXTRA_AUDIO_CODEC = "audio_codec"
        const val EXTRA_AUDIO_SAMPLE_RATE = "audio_sample_rate"
        const val EXTRA_AUDIO_CHANNELS = "audio_channels"
        const val EXTRA_AUDIO_BIT_DEPTH = "audio_bit_depth"

        // Session extras keys for group info
        const val EXTRA_GROUP_NAME = "group_name"

        // Which attempt the reconnect loop is on (with STATE_RECONNECTING)
        const val EXTRA_RECONNECT_ATTEMPT = "reconnect_attempt"

        // Connection state values
        const val STATE_DISCONNECTED = "disconnected"
        const val STATE_CONNECTING = "connecting"
        const val STATE_CONNECTED = "connected"
        const val STATE_RECONNECTING = "reconnecting"
        const val STATE_ERROR = "error"

        // Android Auto browse tree media IDs
        // Max artwork bitmap dimension (px) for MediaMetadata / notifications.
        // Notifications only display small thumbnails; 300px is more than
        // sufficient and avoids wasting memory on full-resolution bitmaps.
        // The UI loads artwork independently via URL so this does not affect
        // in-app display quality.
        private const val MAX_ARTWORK_SIZE = 300

        // Browse tree media IDs and content style hints live in AutoBrowseTree
        // (the testable list builder); aliased here so the rest of the service
        // reads unchanged.
        private const val MEDIA_ID_ROOT = AutoBrowseTree.MEDIA_ID_ROOT
        private const val MEDIA_ID_DISCOVERED = AutoBrowseTree.MEDIA_ID_DISCOVERED
        private const val MEDIA_ID_SERVER_PREFIX = AutoBrowseTree.MEDIA_ID_SERVER_PREFIX
        private const val MEDIA_ID_SAVED_SERVER_PREFIX = AutoBrowseTree.MEDIA_ID_SAVED_SERVER_PREFIX

        // Android Auto content style hint keys
        private const val CONTENT_STYLE_BROWSABLE = AutoBrowseTree.CONTENT_STYLE_BROWSABLE
        private const val CONTENT_STYLE_PLAYABLE = AutoBrowseTree.CONTENT_STYLE_PLAYABLE
        private const val CONTENT_STYLE_LIST = AutoBrowseTree.CONTENT_STYLE_LIST

        // How long the "Connect" browse node waits for the first mDNS result
        // before showing the "No servers found" guidance row. Keeps the first
        // browse on Android Auto from racing discovery and rendering empty.
        private const val BROWSE_DISCOVERY_WAIT_MS = 3_000L

        /**
         * Whether an mDNS announcement is for [server]: by its friendly name
         * (which is what a saved server is named after), by the raw service
         * name, or by the address it was last reached at.
         */
        internal fun isSameServer(server: UnifiedServer, name: String, friendlyName: String, address: String): Boolean =
            friendlyName == server.name || name == server.name || address == server.local?.address

        // Exposes the coordinator's network state for in-process observers (e.g. MainActivity).
        // Updated by the service's existing coordinator.networkState collector.
        private val _networkState = MutableStateFlow(NetworkState())
        val networkState: StateFlow<NetworkState> = _networkState.asStateFlow()

        // Exposes the coordinator's reconnect status for in-process observers (e.g. MainActivity),
        // which show it and never act on it.
        // Updated by the service's existing coordinator.reconnectStatus collector.
        private val _reconnectStatusRelay = MutableStateFlow<ReconnectStatus>(ReconnectStatus.Idle)
        val reconnectStatus: StateFlow<ReconnectStatus> = _reconnectStatusRelay.asStateFlow()
    }


    /**
     * Tasks sent through [decodeChannel] to the single-owner decode worker.
     *
     * All decoder lifecycle transitions (StartStream, Flush, Release) travel on
     * the same channel as Chunk so they respect FIFO ordering with pending
     * decodes. See
     * docs/superpowers/specs/2026-04-23-codec-safe-decoder-redesign-design.md
     * for the H-4 + M-8 rationale.
     */
    private sealed class DecodeTask {
        data class Chunk(
            val serverTimeMicros: Long,
            val audioData: ByteArray,
            val generation: Int,
        ) : DecodeTask() {
            // Override equals/hashCode because data classes with ByteArray use
            // reference equality by default, which is surprising. In practice
            // DecodeTask instances are only compared in tests.
            override fun equals(other: Any?): Boolean {
                if (this === other) return true
                if (other !is Chunk) return false
                return serverTimeMicros == other.serverTimeMicros &&
                    generation == other.generation &&
                    audioData.contentEquals(other.audioData)
            }
            override fun hashCode(): Int {
                var result = serverTimeMicros.hashCode()
                result = 31 * result + generation
                result = 31 * result + audioData.contentHashCode()
                return result
            }
        }
        data class StartStream(
            val codec: String,
            val sampleRate: Int,
            val channels: Int,
            val bitDepth: Int,
            val codecHeader: ByteArray?,
            // True when this reconfigures an active stream: audio queued
            // before it is in the previous format and must still play.
            val keepBuffered: Boolean,
        ) : DecodeTask() {
            override fun equals(other: Any?): Boolean {
                if (this === other) return true
                if (other !is StartStream) return false
                return codec == other.codec && sampleRate == other.sampleRate &&
                    channels == other.channels && bitDepth == other.bitDepth &&
                    keepBuffered == other.keepBuffered &&
                    (codecHeader?.contentEquals(other.codecHeader) ?: (other.codecHeader == null))
            }
            override fun hashCode(): Int {
                var result = codec.hashCode()
                result = 31 * result + sampleRate
                result = 31 * result + channels
                result = 31 * result + bitDepth
                result = 31 * result + keepBuffered.hashCode()
                result = 31 * result + (codecHeader?.contentHashCode() ?: 0)
                return result
            }
        }
        object Flush : DecodeTask()
        object Release : DecodeTask()
    }

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "PlaybackService.onCreate() started")

        // Reset companion-object relays so a recreated service doesn't inherit
        // the previous instance's last broadcast network/reconnect state. The
        // service's own collectors will overwrite with live values shortly.
        _networkState.value = NetworkState()
        _reconnectStatusRelay.value = ReconnectStatus.Idle

        // Track app foreground/background via ProcessLifecycleOwner. Used by
        // onMediaButtonEvent to drop hardware media-key events while none of
        // our activities is visible -- otherwise SendSpinDroid (running in
        // the background with audio focus) and whatever foreground app the
        // user is interacting with both react to the same remote Play press.
        // Initialize the field synchronously before installing the observer
        // so the gate behaves correctly even before the first state
        // transition is reported.
        appInForeground = androidx.lifecycle.ProcessLifecycleOwner.get().lifecycle.currentState
            .isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)
        appLifecycleObserver = object : androidx.lifecycle.DefaultLifecycleObserver {
            override fun onStart(owner: androidx.lifecycle.LifecycleOwner) {
                appInForeground = true
            }
            override fun onStop(owner: androidx.lifecycle.LifecycleOwner) {
                appInForeground = false
            }
        }.also { androidx.lifecycle.ProcessLifecycleOwner.get().lifecycle.addObserver(it) }

        // Create notification channel for foreground service
        NotificationHelper.createNotificationChannel(this)

        // Configure media notification provider to use our channel
        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this)
                .setChannelId(NotificationHelper.CHANNEL_ID)
                .build()
        )

        // Initialize UserSettings for player name preference (must be before lowMemoryMode check)
        com.sendspindroid.UserSettings.initialize(this)

        // Initialize UnifiedServerRepository for server lookups
        UnifiedServerRepository.initialize(this)

        // Register receiver for sync offset changes from settings
        LocalBroadcastManager.getInstance(this).registerReceiver(
            syncOffsetReceiver,
            IntentFilter(SyncOffsetPreference.ACTION_SYNC_OFFSET_CHANGED)
        )

        // Register receiver for log level changes from settings
        LocalBroadcastManager.getInstance(this).registerReceiver(
            logLevelReceiver,
            IntentFilter(SettingsViewModel.ACTION_LOG_LEVEL_CHANGED)
        )

        // Register receiver for High Power Mode toggle changes from settings
        LocalBroadcastManager.getInstance(this).registerReceiver(
            highPowerModeReceiver,
            IntentFilter(SettingsViewModel.ACTION_HIGH_POWER_MODE_CHANGED)
        )

        // Register receiver for preferred codec changes from settings
        LocalBroadcastManager.getInstance(this).registerReceiver(
            preferredCodecReceiver,
            IntentFilter(SettingsViewModel.ACTION_PREFERRED_CODEC_CHANGED)
        )

        // Initialize Coil ImageLoader for artwork fetching (skip in low memory mode)
        if (!com.sendspindroid.UserSettings.lowMemoryMode) {
            imageLoader = ImageLoader.Builder(this)
                .crossfade(true)
                .build()
        } else {
            Log.i(TAG, "Low Memory Mode: Skipping ImageLoader initialization")
        }

        // Initialize SendSpinPlayer
        initializePlayer()

        // Create MediaSession wrapping SendSpinPlayer
        initializeMediaSession()
        Log.i(TAG, "PlaybackService.onCreate() session ready: mediaSession=${mediaSession != null}")

        // Initialize native Kotlin SendSpin client
        initializeSendSpinClient()

        coordinator = ConnectionCoordinator(
            currentServerFlow = _currentServerFlow,
            sendSpinStateFlow = sendSpinClient?.connectionState ?: flowOf(TransportState.Idle),
            scope = serviceScope,
            connectAttempt = { lost, method ->
                // Read again for each attempt: mDNS may have found the server
                // at a new address since the loop started.
                val server = UnifiedServerRepository.getServer(lost.id) ?: lost
                // Local is the only connection method left; other ConnectionType
                // values can no longer be satisfied (no remote/proxy transport).
                val selected = when (method) {
                    com.sendspindroid.model.ConnectionType.LOCAL -> server.local?.let {
                        ConnectionSelector.SelectedConnection.Local(it.address, it.path)
                    }
                    else -> null
                } ?: return@ConnectionCoordinator false
                connectViaSelectedConnection(server, selected)
            },
            context = applicationContext,
        )

        // Mirrors the Coordinator's NetworkState into the companion flow for
        // in-process observers (MainActivity).
        serviceScope.launch {
            coordinator.networkState.collect { state -> _networkState.value = state }
        }

        // Reconnect status: mirrored into the companion flow for in-process
        // observers (MainActivity) and broadcast to MediaController consumers.
        serviceScope.launch {
            var wasAttempting = false
            coordinator.reconnectStatus.collect { status ->
                _reconnectStatusRelay.value = status
                recordHandoff(status)
                broadcastSessionExtras()
                val attempting = status is ReconnectStatus.Attempting
                if (wasAttempting && !attempting) {
                    // The loop is over, one way or the other.
                    stopReconnectDiscovery()
                    mainHandler.removeCallbacks(reconnectLockRelease)
                    releaseIfIdle()
                }
                wasAttempting = attempting
            }
        }

        // NetworkEvent observer: dispatches Android-specific events (identity/link-address changes)
        // that cannot be encoded in the platform-neutral NetworkState.
        serviceScope.launch {
            coordinator.networkEvents.collect { event ->
                when (event) {
                    is com.sendspindroid.coordinator.NetworkEvent.IdentityChanged -> {
                        Log.i(TAG, "networkEvent: IdentityChanged handle=${event.networkHandle}")
                        sendSpinClient?.onNetworkChanged()
                        val connState = sendSpinClient?.connectionState?.value
                        val shouldReselect =
                            connState is TransportState.Ready ||
                            connState is TransportState.Connecting
                        if (shouldReselect) {
                            Log.i(TAG, "Triggering connection reselection for new network")
                            sendSpinClient?.disconnectForReselection()
                        }
                    }
                    is com.sendspindroid.coordinator.NetworkEvent.LinkAddressesChanged -> {
                        Log.i(TAG, "networkEvent: LinkAddressesChanged")
                        sendSpinClient?.onNetworkChanged()
                        browseDiscoveryManager?.refreshMulticastLockIfActive()
                    }
                }
            }
        }

        // The media session offers only what the server's controller state allows.
        serviceScope.launch {
            sendSpinClient?.controllerState?.collect { state ->
                sendSpinPlayer?.updateControllerState(state)
            }
        }

        // Reacts to the client's connection state.
        var prevSendSpinState: TransportState = TransportState.Idle
        serviceScope.launch {
            sendSpinClient?.connectionState?.collect { state ->
                // No stream survives the connection it was started on.
                if (state !is TransportState.Ready) activeStreamConfig = null
                when {
                    state is TransportState.Ready && prevSendSpinState !is TransportState.Ready -> {
                        val serverName = sendSpinClient?.getServerName() ?: ""
                        Log.d(TAG, "Connected to: $serverName")
                        sendSpinPlayer?.updateConnectionState(true, serverName)
                        sendSpinPlayer?.clearError()

                        // Refresh browse tree root so "Connect" disappears
                        mediaSession?.notifyChildrenChanged(MEDIA_ID_ROOT, 0, null)

                        // Apply saved sync offset from settings
                        applySyncOffsetFromSettings()

                        // Start foreground service to prevent process from being killed
                        // but DON'T acquire wake/WiFi locks yet - those drain battery and are
                        // only needed during active audio streaming (acquired in onStreamStart)
                        startForegroundServiceWithNotification(serverName)

                        // In High Power Mode, acquire WiFi + CPU locks immediately on connect
                        // to prevent Android from sleeping the connection between streams
                        if (com.sendspindroid.UserSettings.highPowerMode) {
                            acquireHighPowerLocks()
                        }

                        // Start debug logging session if enabled
                        val serverAddr = sendSpinClient?.getServerName() ?: ""
                        AppLog.session.start(serverName, serverAddr)
                        startDebugLogging()

                        // Broadcast connection state to controllers (MainActivity)
                        broadcastSessionExtras()
                    }
                    state is TransportState.Idle && prevSendSpinState !is TransportState.Idle -> {
                        Log.d(TAG, "Disconnected from server")
                        tearDownConnection(error = null, preserveVolume = true)
                    }
                    state is TransportState.Failed -> {
                        val message = failureReasonToMessage(state.reason)
                        Log.e(TAG, "SendSpin error: $message")

                        // Show error on Android Auto
                        sendSpinPlayer?.setError(message)
                        releaseIfIdle()

                        // Broadcast error to controllers (MainActivity). Fork: force
                        // STATE_ERROR + message when no reconnect loop is running; the
                        // derived coordinator.sessionState can lag the Failed we hold
                        // here, and the extras dedup would then suppress the
                        // correction for good. A running loop reports RECONNECTING.
                        if (coordinator.reconnectStatus.value is ReconnectStatus.Attempting) {
                            broadcastSessionExtras()
                        } else {
                            broadcastSessionExtras(
                                forceState = STATE_ERROR,
                                forceErrorMessage = message,
                            )
                        }
                    }
                    state is TransportState.Connecting && prevSendSpinState !is TransportState.Connecting -> {
                        // Fork: clear a stale error so the prior failure string
                        // is not shown on Android Auto / lock screen during the attempt.
                        sendSpinPlayer?.clearError()
                        // Announce the attempt from here, where the transition
                        // has already happened. The connect* methods used to do
                        // it on their first line, before anything had left Idle,
                        // and the publisher reports observed state rather than
                        // the argument it is handed - so that call announced
                        // DISCONNECTED at the very moment a connection began.
                        // Fork: forced (unless the reconnect loop owns the state), so
                        // it does not depend on which flow has settled.
                        broadcastSessionExtras(
                            forceState = STATE_CONNECTING.takeUnless {
                                coordinator.reconnectStatus.value is ReconnectStatus.Attempting
                            }
                        )
                    }
                }
                prevSendSpinState = state
            }
        }

        // Launch the single-owner decode worker. Task 3 scaffolding: no
        // callback currently sends into decodeChannel. Task 4 flips the
        // existing onAudioChunk / onStreamStart / onStreamClear / onDestroy
        // paths over to this channel.
        startDecodeWorker()

        // Initialize network evaluator for passive network monitoring
        networkEvaluator = NetworkEvaluator(this)

        // Perform initial network evaluation
        networkEvaluator?.evaluateCurrentNetwork()

        // Initialize AudioManager for device volume control
        initializeVolumeControl()

        // Stop the sound when the audio output device disconnects.
        registerBecomingNoisyReceiver()
    }

    /**
     * Initializes device volume control (Spotify-style hybrid approach).
     *
     * This sets up:
     * - AudioManager for reading/writing device STREAM_MUSIC volume
     * - ContentObserver to detect hardware volume button presses and sync to server
     *
     * Hardware volume buttons now control playback volume, and changes are
     * synced to the SendSpin server for multi-client coordination.
     */
    private fun initializeVolumeControl() {
        // Prevent double registration - unregister existing observer first
        if (volumeObserverRegistered) {
            Log.w(TAG, "Volume observer already registered, skipping initialization")
            return
        }

        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        // Track current volume to detect external changes
        lastKnownVolume = audioManager?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: 0

        // Initialize playback state with actual device volume (not default 100%)
        val maxVolume = audioManager?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: 15
        val volumePercent = ((lastKnownVolume.toFloat() / maxVolume) * 100).toInt()
        _playbackState.value = _playbackState.value.copy(volume = volumePercent)

        // Create observer to detect volume changes from hardware buttons
        volumeObserver = object : ContentObserver(mainHandler) {
            override fun onChange(selfChange: Boolean) {
                val currentVolume = audioManager?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: return

                // Only sync to server if volume actually changed (not our own change)
                if (currentVolume != lastKnownVolume) {
                    lastKnownVolume = currentVolume

                    // Convert to normalized 0.0-1.0 and sync to server
                    val maxVolume = audioManager?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: 15
                    val normalizedVolume = currentVolume.toFloat() / maxVolume
                    Log.d(TAG, "Device volume changed via hardware buttons: $currentVolume/$maxVolume ($normalizedVolume)")

                    // Sync to server (for multi-client coordination)
                    sendSpinClient?.setVolume(normalizedVolume.toDouble())

                    // Update playback state for UI sync
                    val volumePercent = (normalizedVolume * 100).toInt()
                    _playbackState.value = _playbackState.value.copy(volume = volumePercent)
                    broadcastSessionExtras()
                }
            }
        }

        // Register the volume observer
        contentResolver.registerContentObserver(
            Settings.System.CONTENT_URI,
            true,
            volumeObserver!!
        )
        volumeObserverRegistered = true

        Log.d(TAG, "Volume control initialized - using device STREAM_MUSIC")
    }

    /**
     * Initializes the native Kotlin SendSpin client.
     */
    @OptIn(UnstableApi::class)
    /**
     * Latest admission state for the live connection.
     *
     * Reset per connection: a stale value from a previous session would
     * otherwise be published in the window between reconnecting and the first
     * `server/activate` of the new session.
     */
    @Volatile
    private var admissionState: AdmissionState = AdmissionState.READY

    /** The dynamic pairing code currently shown to the operator, or null. */
    @Volatile
    private var pairingCode: String? = null

    /** Whether a dynamic pairing attempt is waiting on the "Allow pairing" gesture. */
    @Volatile
    private var pairingGestureRequested: Boolean = false

    private fun initializeSendSpinClient() {
        try {
            admissionState = AdmissionState.READY
            pairingCode = null
            pairingGestureRequested = false
            // Use user-configured player name, falls back to device model
            val playerName = com.sendspindroid.UserSettings.getPlayerName()
            sendSpinClient = SendSpin(
                deviceName = playerName,
                callback = SendSpinClientCallback()
            )
            sendSpinPlayer?.setSendSpinClient(sendSpinClient)
            Log.d(TAG, "SendSpin initialized with name: $playerName")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize SendSpin", e)
        }
    }

    /**
     * Launches the single-owner decode worker. The worker consumes
     * [decodeChannel] sequentially on [decodeDispatcher]; all decoder
     * lifecycle events (StartStream, Flush, Release) flow through the same
     * channel as Chunk so they preserve FIFO ordering with pending decodes.
     *
     * Task 3 scaffolding: the worker is running but no callback currently
     * sends into [decodeChannel]. Task 4 flips the callback sites over.
     */
    private fun startDecodeWorker() {
        decodeJob = serviceScope.launch(decodeDispatcher) {
            try {
                for (task in decodeChannel) {
                    try {
                        when (task) {
                            is DecodeTask.Chunk -> handleDecodeChunk(task)
                            is DecodeTask.StartStream -> handleDecodeStartStream(task)
                            DecodeTask.Flush -> handleDecodeFlush()
                            DecodeTask.Release -> handleDecodeRelease()
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Decode worker error on task ${task::class.simpleName}", e)
                    }
                }
            } finally {
                // Channel-closed path. Releases the decoder unconditionally so
                // a shutdown that couldn't enqueue an explicit Release task
                // (trySend Failed because the channel was full at onDestroy)
                // still doesn't leak the MediaCodec / codec native handles.
                // The handleDecodeRelease path (when it runs successfully)
                // leaves audioDecoder == null already, so this is a no-op in
                // the normal case.
                if (audioDecoder != null) {
                    try {
                        audioDecoder?.release()
                    } catch (e: Exception) {
                        Log.e(TAG, "Error releasing decoder on worker exit", e)
                    }
                    audioDecoder = null
                    decoderReady = false
                }
            }
        }
    }

    /**
     * Decode a single audio chunk. Runs on [decodeDispatcher]; [audioDecoder]
     * is read from the single-owner thread so no TOCTOU local-ref capture is
     * needed.
     */
    private suspend fun handleDecodeChunk(t: DecodeTask.Chunk) {
        // Drop chunks belonging to a stream that has since ended or been
        // replaced. Popping and discarding costs microseconds, so a ~30-second
        // look-ahead backlog clears near-instantly instead of grinding through
        // syncAudioPlayer.queueChunk back-pressure at real-time rate -- that
        // stall is what delayed decoder reconfiguration in issue #114.
        if (t.generation != decodeGeneration) return

        // No decoder means the stream's codec could not be set up: drop the
        // chunk. PCM has a decoder of its own, so nothing passes through raw.
        val decoder = audioDecoder ?: return
        val decoded = try {
            decoder.decode(t.audioData, t.serverTimeMicros)
        } catch (e: Exception) {
            Log.e(TAG, "Decode error, dropping chunk", e)
            return
        }
        val player = syncAudioPlayer ?: return
        // Checked again, by the player under the lock its clear takes: a
        // stream/clear or stream/end that landed while this chunk was
        // decoding has cleared the player or is about to, and a chunk queued
        // after that would sit at the head of the queue with the old stream's
        // timestamp. The new stream's audio is then discarded as overlap
        // until it catches up - seconds of silence after a skip. The
        // generation is bumped before the clear is posted, so a chunk that
        // passes here is queued before the clear and removed by it.
        //
        // Stamped by the decoder, not with this chunk's time: what comes out
        // can be the previous chunk's audio.
        for (audio in decoded) {
            player.queueChunk(audio.timestampUs, audio.pcm) { t.generation == decodeGeneration }
        }
    }

    /**
     * Release any prior decoder and create+configure a new one for the new
     * stream. Runs on [decodeDispatcher] so we're the single owner of
     * [audioDecoder]. If the decoder cannot be created or configured there is
     * no decoder and the stream's chunks are dropped: silence, never the
     * compressed bytes played as PCM.
     */
    private suspend fun handleDecodeStartStream(t: DecodeTask.StartStream) {
        Log.d(
            TAG,
            "Stream started: codec=${t.codec}, rate=${t.sampleRate}, " +
                "channels=${t.channels}, bits=${t.bitDepth}, " +
                "header=${t.codecHeader?.size ?: 0} bytes"
        )

        // Release existing decoder and create new one for this stream.
        audioDecoder?.release()
        audioDecoder = null
        try {
            val decoder = AudioDecoderFactory.create(t.codec)
            decoder.configure(t.sampleRate, t.channels, t.bitDepth, t.codecHeader)
            audioDecoder = decoder
            Log.i(TAG, "Audio decoder created: ${t.codec}")
            decoderReady = true
        } catch (e: Exception) {
            Log.e(TAG, "No decoder for ${t.codec}; dropping this stream's audio", e)
            // Subsequent chunks are rejected at the WS-IO fast-path gate.
            decoderReady = false
        }

        if (t.keepBuffered) switchOutputFormat(t)
    }

    /**
     * Format change on an active stream. Everything already queued in
     * [syncAudioPlayer] was decoded in the previous format and must still be
     * played, so when the PCM format differs the player is only replaced once
     * its queue has drained. Suspending here holds back the chunks behind this
     * task, which are in the new format. A stream/clear or stream/end bumps
     * [decodeGeneration] and ends the wait early.
     */
    private suspend fun switchOutputFormat(t: DecodeTask.StartStream) {
        val old = syncAudioPlayer
        if (old != null && old.matchesFormat(t.sampleRate, t.channels, t.bitDepth)) return

        Log.i(TAG, "Output format change on active stream - playing out ${old?.getBufferedDurationMs() ?: 0}ms of buffered audio first")
        val generation = decodeGeneration
        while (old != null && old === syncAudioPlayer && generation == decodeGeneration &&
            old.getBufferedDurationMs() > 0
        ) {
            delay(FORMAT_SWITCH_POLL_MS)
        }

        withContext(Dispatchers.Main) {
            // Replaced or torn down while we waited (disconnect, new stream).
            if (syncAudioPlayer !== old) return@withContext
            old?.release()
            createSyncAudioPlayer(t.sampleRate, t.channels, t.bitDepth)
        }
    }

    /** Creates and starts a [SyncAudioPlayer] for the given PCM format. Main thread. */
    private fun createSyncAudioPlayer(sampleRate: Int, channels: Int, bitDepth: Int) {
        val timeFilter = sendSpinClient?.getTimeFilter()
        if (timeFilter == null) {
            Log.e(TAG, "Cannot start audio: time filter not available")
            return
        }
        // In low memory mode, cap the chunk queue to ~10 seconds of audio
        val maxSamples = if (com.sendspindroid.UserSettings.lowMemoryMode) {
            sampleRate.toLong() * SendSpinProtocol.Buffer.DURATION_LOW_MEM_SEC
        } else {
            0L  // Unlimited
        }
        syncAudioPlayer = SyncAudioPlayer(
            timeFilter = timeFilter,
            sampleRate = sampleRate,
            channels = channels,
            bitDepth = bitDepth,
            maxQueueSamples = maxSamples,
        ).apply {
            // Set callback to update SendSpinPlayer when playback state changes
            setStateCallback(SyncAudioPlayerStateCallback())
            // From settings, not _playbackState: that is reset on disconnect.
            setMuted(SyncAudioPlayer.MuteReason.PLAYER, com.sendspindroid.UserSettings.getPlayerMuted())
            setMuted(SyncAudioPlayer.MuteReason.INTERRUPTION, interruptionMuted)
            initialize()
            start()
        }
        sendSpinPlayer?.setSyncAudioPlayer(syncAudioPlayer)
        Log.i(TAG, "SyncAudioPlayer created: ${sampleRate}Hz, ${channels}ch, ${bitDepth}bit")
    }

    private suspend fun handleDecodeFlush() {
        audioDecoder?.flush()
    }

    private suspend fun handleDecodeRelease() {
        audioDecoder?.release()
        audioDecoder = null
        decoderReady = false
    }

    /**
     * Callback for SyncAudioPlayer state changes.
     * Updates SendSpinPlayer when playback state changes so MediaSession notifies controllers.
     */
    private inner class SyncAudioPlayerStateCallback : SyncAudioPlayerCallback {
        @OptIn(UnstableApi::class)
        override fun onPlaybackStateChanged(state: SyncPlaybackState) {
            mainHandler.post {
                Log.d(TAG, "SyncAudioPlayer state changed: $state")
                // Update SendSpinPlayer so it notifies MediaSession listeners
                sendSpinPlayer?.updateStateFromPlayer()
            }
        }

        /**
         * Fork: SyncAudioPlayer gave up recreating a faulted AudioTrack. Tear
         * the player down completely so the next stream/start builds a fresh
         * one; stop() alone would leave a non-null player whose loop is gone,
         * and the reuse path would then produce no audio.
         */
        @OptIn(UnstableApi::class)
        override fun onBufferExhausted() {
            Log.e(TAG, "AudioTrack unrecoverable - stopping playback")
            mainHandler.post {
                syncAudioPlayer?.stop()
                syncAudioPlayer?.release()
                syncAudioPlayer = null
                sendSpinPlayer?.setSyncAudioPlayer(null)
                // Close the chunk fast-path gate; the next stream/start re-opens it.
                decoderReady = false
                releasePlaybackLocks()

                _playbackState.value = _playbackState.value.copy(
                    playbackState = PlaybackStateType.STOPPED
                )
                sendSpinPlayer?.updatePlayWhenReadyFromServer(false)
                broadcastSessionExtras()
            }
        }
    }

    /**
     * Callback for SendSpin events.
     */
    private inner class SendSpinClientCallback : SendSpin.Callback {

        override fun onDisconnected(reconnect: Boolean) {
            mainHandler.post { onConnectionEnded(reconnect) }
        }

        override fun onAdmissionStateChanged(state: AdmissionState) {
            mainHandler.post {
                if (admissionState == state) return@post
                Log.i(TAG, "Admission state: $admissionState -> $state")
                admissionState = state
                broadcastSessionExtras()
            }
        }

        override fun onDynamicPairingCodeEmitted(code: String) {
            mainHandler.post {
                pairingCode = code
                broadcastSessionExtras()
            }
        }

        override fun onDynamicPairingCodeCleared() {
            mainHandler.post {
                // StopEmittingCode fires on every terminal path (success, abort,
                // timeout, superseding activation) - including one where the
                // attempt never got past AWAITING_GESTURE and no code was ever
                // emitted. A terminal ends both the code display and any
                // pending gesture request, so both clear here together;
                // leaving the gesture flag set would strand the "Allow
                // pairing" button on screen with no live attempt behind it.
                pairingCode = null
                pairingGestureRequested = false
                broadcastSessionExtras()
            }
        }

        override fun onDynamicPairingGestureRequested() {
            mainHandler.post {
                pairingGestureRequested = true
                broadcastSessionExtras()
            }
        }

        override fun onStateChanged(state: String) {
            mainHandler.post {
                Log.d(TAG, "State changed: $state")
                val newState = PlaybackStateType.fromString(state)
                noteServerPlaybackState(newState)

                // Handle playback state transitions per SendSpin spec
                if (newState == PlaybackStateType.STOPPED) {
                    // Stop: "reset position to beginning" - clear buffer
                    Log.d(TAG, "State is stopped - clearing audio buffer and releasing playback locks")
                    sendSpinPlayer?.updatePlayWhenReadyFromServer(false)
                    syncAudioPlayer?.clearBuffer()
                    syncAudioPlayer?.pause()
                    releasePlaybackLocks()
                } else if (newState == PlaybackStateType.PAUSED) {
                    // Pause: "maintains current position for later resumption" - keep buffer
                    Log.d(TAG, "State is paused - pausing audio (keeping buffer)")
                    sendSpinPlayer?.updatePlayWhenReadyFromServer(false)
                    syncAudioPlayer?.pause()
                    releasePlaybackLocks()
                } else if (newState == PlaybackStateType.PLAYING) {
                    // Playing: resume playback if paused. Re-attach syncAudioPlayer
                    // symmetrically with the onGroupUpdate PLAYING branch -- a
                    // server-PLAYING transition that arrives without a matching
                    // stream/start (rare but possible) can otherwise leave
                    // sendSpinPlayer's reference stale if a prior disconnect
                    // path nulled it.
                    Log.d(TAG, "State is playing - resuming audio and acquiring playback locks")
                    sendSpinPlayer?.updatePlayWhenReadyFromServer(true)
                    syncAudioPlayer?.resume()
                    sendSpinPlayer?.setSyncAudioPlayer(syncAudioPlayer)
                    acquirePlaybackLocks()
                    // Invalidate the session-extras dedup so the next broadcast
                    // pushes through even when the bundle content is byte-
                    // identical to the last paused-state broadcast. After a
                    // long pause, the VM's idle watchdog clears its local
                    // metadata; the SendSpin playback state (PAUSED/PLAYING)
                    // isn't in the extras bundle, so the dedup would otherwise
                    // skip the resume broadcast and the now-playing screen
                    // would stay on the idle layout. See onGroupUpdate PLAYING
                    // branch for the mirrored invalidation.
                    lastSessionExtrasFingerprint = Long.MIN_VALUE
                }

                _playbackState.value = _playbackState.value.copy(playbackState = newState)

                // Broadcast the state change to controllers (MainActivity). The
                // PLAYING branch above resets the extras fingerprint to force a
                // resume push through the dedup; mirror onGroupUpdate by actually
                // issuing the broadcast here instead of relying on an unrelated
                // later event to carry the forced push.
                broadcastSessionExtras()
            }
        }

        @OptIn(UnstableApi::class)
        override fun onGroupUpdate(groupId: String, groupName: String, playbackState: String) {
            mainHandler.post {
                Log.d(TAG, "Group update: id=$groupId name=$groupName state=$playbackState")

                val currentState = _playbackState.value
                val isGroupChange = groupId.isNotEmpty() && groupId != currentState.groupId
                val newPlaybackState = PlaybackStateType.fromString(playbackState)
                if (playbackState.isNotEmpty()) noteServerPlaybackState(newPlaybackState)

                // Handle playback state transitions per SendSpin spec
                if (playbackState.isNotEmpty()) {
                    when (newPlaybackState) {
                        PlaybackStateType.STOPPED -> {
                            // Stop: "reset position to beginning" - clear buffer
                            Log.d(TAG, "Playback stopped - clearing audio buffer and releasing playback locks")
                            sendSpinPlayer?.updatePlayWhenReadyFromServer(false)
                            syncAudioPlayer?.clearBuffer()
                            syncAudioPlayer?.pause()
                            releasePlaybackLocks()
                        }
                        PlaybackStateType.PAUSED -> {
                            // Pause: "maintains current position for later resumption" - keep buffer
                            Log.d(TAG, "Playback paused - pausing audio (keeping buffer)")
                            sendSpinPlayer?.updatePlayWhenReadyFromServer(false)
                            syncAudioPlayer?.pause()
                            releasePlaybackLocks()
                        }
                        PlaybackStateType.PLAYING -> {
                            // Playing: resume playback if paused
                            Log.d(TAG, "Playback playing - resuming audio and acquiring playback locks")
                            sendSpinPlayer?.updatePlayWhenReadyFromServer(true)
                            syncAudioPlayer?.resume()
                            sendSpinPlayer?.setSyncAudioPlayer(syncAudioPlayer)
                            acquirePlaybackLocks()
                            // Invalidate the session-extras dedup -- see the
                            // onStateChanged PLAYING branch for the rationale.
                            // After a long pause the watchdog clears the VM
                            // state but the service-side extras are unchanged,
                            // and the dedup would skip the resume broadcast.
                            lastSessionExtrasFingerprint = Long.MIN_VALUE
                        }
                        else -> { /* No action needed */ }
                    }
                }

                val newState = if (isGroupChange) {
                    // Preserve total_tracks across the metadata clear. It is the
                    // queue length, delivered ONLY in the connect/subscribe
                    // server/state snapshot -- which races AHEAD of this
                    // group/update on initial join (null -> group). A full
                    // withClearedMetadata() wiped the just-received value and the
                    // NowPlaying "X of Y" counter never recovered, because the
                    // server never re-sends total_tracks on subsequent frames
                    // (withMetadata preserves it, but only if it survives to be
                    // preserved). A genuine switch to a different queue re-delivers
                    // it in that queue's own snapshot, overriding the held value.
                    currentState.withClearedMetadata().copy(
                        groupId = groupId,
                        groupName = groupName.ifEmpty { null },
                        playbackState = newPlaybackState,
                        totalTracks = currentState.totalTracks
                    )
                } else {
                    currentState.copy(
                        groupId = groupId.ifEmpty { currentState.groupId },
                        groupName = groupName.ifEmpty { currentState.groupName },
                        playbackState = if (playbackState.isNotEmpty())
                            newPlaybackState
                        else currentState.playbackState
                    )
                }
                _playbackState.value = newState

                // Broadcast all state including group name to controllers (MainActivity)
                broadcastSessionExtras()
            }
        }

        @OptIn(UnstableApi::class)
        override fun onMetadataUpdate(metadata: TrackMetadata) {
            // Unpack the value object into the raw locals the body below was
            // written against, so the downstream withMetadata / updateMediaItem /
            // artwork-generation logic is unchanged. durationMs / positionMs are
            // TrackMetadata convenience props (progress.trackDuration /
            // progress.trackProgress); playbackSpeed lives on progress.
            val title = metadata.title
            val artist = metadata.artist
            val albumArtist = metadata.albumArtist
            val album = metadata.album
            val artworkUrl = metadata.artworkUrl
            val year = metadata.year
            val albumTrack = metadata.albumTrack
            val queueTrack = metadata.queueTrack
            val totalTracks = metadata.totalTracks
            val durationMs = metadata.durationMs
            val positionMs = metadata.positionMs
            val playbackSpeed = metadata.progress?.playbackSpeed ?: 1000
            mainHandler.post {
                if (title == null && artist == null && album == null) {
                    clearTrackMetadata()
                    return@post
                }
                Log.d(TAG, "Metadata update: $title / $artist / $album")
                Log.d(TAG, "  extra fields: albumArtist=$albumArtist year=$year albumTrack=$albumTrack queueTrack=$queueTrack totalTracks=$totalTracks")

                val artworkUrlOrEmpty = artworkUrl.orEmpty()

                // server/state carries the role's full state (rc1), so a null
                // field means the track has none. Strings go on as "" and ints
                // as 0, which withMetadata clears; null would keep the last
                // track's. (Fork: withMetadata still holds artwork and total
                // tracks across an empty value -- see PlaybackState.)
                val titleOrEmpty = title.orEmpty()
                val artistOrEmpty = artist.orEmpty()
                val albumOrEmpty = album.orEmpty()
                val albumArtistOrEmpty = albumArtist.orEmpty()
                val yearOrNull = year?.takeIf { it > 0 }
                val albumTrackOrNull = albumTrack?.takeIf { it > 0 }
                _playbackState.value = _playbackState.value.withMetadata(
                    title = titleOrEmpty,
                    artist = artistOrEmpty,
                    albumArtist = albumArtistOrEmpty,
                    album = albumOrEmpty,
                    artworkUrl = artworkUrlOrEmpty,
                    year = yearOrNull ?: 0,
                    albumTrack = albumTrackOrNull ?: 0,
                    queueTrack = queueTrack ?: 0,
                    totalTracks = totalTracks ?: 0,
                    durationMs = durationMs,
                    positionMs = positionMs,
                    playbackSpeed = playbackSpeed
                )

                // Bump the artwork generation on every title change so any
                // in-flight fetch/decode for the previous track gets dropped
                // at completion (the captured generation won't match the
                // current one).
                //
                // Compare against the post-withMetadata state title rather
                // than the raw protocol field. The server emits empty title
                // in idle metadata frames; withMetadata preserves the prior
                // title on empty input ("no info" semantics), but comparing
                // against the raw empty input would falsely report a title
                // change and wipe artwork during idle transitions even
                // though the on-screen title is unchanged.
                //
                // Only wipe the bitmap caches when the new track has no
                // artwork URL of its own. With a new URL incoming, keeping
                // the prior bitmap lets AsyncImage / lock screen show the
                // old image until the fetch resolves and replaces it,
                // instead of flashing the placeholder for the duration of
                // the HTTP fetch. The fetch-failure path (see fetchArtwork)
                // drops the stale bitmap so a 404 doesn't keep the wrong
                // image up.
                val resolvedTitle = _playbackState.value.title
                val titleChanged = resolvedTitle != lastTrackTitle
                if (titleChanged) {
                    lastTrackTitle = resolvedTitle
                    ++artworkGeneration
                    if (artworkUrlOrEmpty.isEmpty()) {
                        urlArtwork = null
                        binaryArtwork = null
                    }
                }

                // Refresh the forwarding-player cache BEFORE firing
                // updateMediaItem. updateMediaItem synchronously dispatches
                // onTimelineChanged / onMediaItemTransition to all listeners
                // (SendSpinPlayer.kt:291-294); the MediaSession reads metadata
                // via forwardingPlayer in response. Without the cache refresh
                // first, MetadataForwardingPlayer.getMediaMetadata() returns
                // its prior cachedMetadata (the override at
                // MetadataForwardingPlayer.kt:259 only delegates to the
                // underlying player when currentTitle/currentArtist are null),
                // so lock screen / Auto / AVRCP would see the prior track for
                // one tick. Mirrors the fix in applyFastQueueMetadata.
                updateMediaMetadata()

                // Update the player's media item for lock screen/notification
                sendSpinPlayer?.updateMediaItem(
                    title = titleOrEmpty.ifEmpty { null },
                    artist = artistOrEmpty.ifEmpty { null },
                    album = albumOrEmpty.ifEmpty { null },
                    durationMs = durationMs,
                    albumArtist = albumArtistOrEmpty.ifEmpty { null },
                    year = yearOrNull,
                    albumTrack = albumTrackOrNull
                )

                // Update the player's position so MediaSession reports it
                // to Android Auto, Bluetooth (AVRCP), and lock screen
                sendSpinPlayer?.updatePlaybackState(
                    syncState = null,
                    positionMs = positionMs,
                    durationMs = durationMs
                )

                if (artworkUrlOrEmpty.isEmpty()) {
                    lastArtworkUrl = null
                } else if (artworkUrlOrEmpty != lastArtworkUrl || titleChanged) {
                    lastArtworkUrl = artworkUrlOrEmpty
                    fetchArtwork(artworkUrlOrEmpty)
                }
            }
        }

        /**
         * Fork: no track any more (idle state, or the metadata role removed).
         * withMetadata() holds artwork and total tracks across an empty value,
         * so the clear goes through withClearedMetadata(), the explicit reset.
         */
        private fun clearTrackMetadata() {
            run {
                Log.d(TAG, "Metadata role cleared - blanking track display")
                _playbackState.value = _playbackState.value.withClearedMetadata()

                // Same teardown a track change performs: drop the artwork and
                // bump the generation so an in-flight fetch for the old track
                // cannot land after this.
                lastTrackTitle = null
                lastArtworkUrl = null
                urlArtwork = null
                binaryArtwork = null
                ++artworkGeneration

                // Refresh the forwarding-player cache before updateMediaItem,
                // for the reason spelled out in onMetadataUpdate.
                updateMediaMetadata()
                sendSpinPlayer?.updateMediaItem(
                    title = null,
                    artist = null,
                    album = null,
                    durationMs = 0L,
                    albumArtist = null,
                    year = null,
                    albumTrack = null
                )
                sendSpinPlayer?.updatePlaybackState(
                    syncState = null,
                    positionMs = 0L,
                    durationMs = 0L
                )
                broadcastSessionExtras()
            }
        }

        override fun onArtwork(imageData: ByteArray) {
            // Skip artwork processing in low memory mode
            if (com.sendspindroid.UserSettings.lowMemoryMode) {
                return
            }

            // Posted like onArtworkCleared, so an image and a clear take effect
            // in the order the server sent them.
            mainHandler.post {
                Log.d(TAG, "Artwork received: ${imageData.size} bytes")
                val generation = ++binaryArtworkGeneration
                serviceScope.launch {
                    val scaled = try {
                        withContext(Dispatchers.IO) {
                            val bitmap = BitmapFactory.decodeByteArray(imageData, 0, imageData.size)
                            bitmap?.let { scaleArtwork(it) }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to decode artwork", e)
                        null
                    }
                    if (generation != binaryArtworkGeneration) return@launch
                    // An image that does not decode still replaces the one
                    // before it; effectiveArtwork decides whether it is shown.
                    binaryArtwork = scaled
                    if (scaled != null) binaryArtworkFullRes = imageData
                    updateMediaMetadata()
                }
            }
        }

        override fun onArtistArtwork(imageData: ByteArray?) {
            if (com.sendspindroid.UserSettings.lowMemoryMode) return
            Log.d(TAG, "Artist image: ${imageData?.size ?: 0} bytes")
            _artistArtwork.value = imageData
        }

        override fun onArtworkCleared() {
            mainHandler.post {
                Log.d(TAG, "Artwork cleared by server (empty payload)")
                binaryArtworkGeneration++
                binaryArtwork = null
                updateMediaMetadata()
            }
        }

        override fun onStreamStart(codec: String, sampleRate: Int, channels: Int, bitDepth: Int, codecHeader: ByteArray?) {
            val config = StreamConfig(codec, sampleRate, channels, bitDepth, codecHeader)
            val action = StreamStartAction.of(activeStreamConfig, config)
            activeStreamConfig = config
            if (action == StreamStartAction.UNCHANGED) {
                Log.d(TAG, "stream/start repeats the active format - nothing to do")
                return
            }
            // A stream/start on an active stream reconfigures it: "Clients
            // MUST keep buffered chunks and decode each chunk in the format
            // that was in effect when it was received." FIFO ordering on
            // decodeChannel gives the second half; not discarding anything
            // gives the first.
            val keepBuffered = action == StreamStartAction.FORMAT_CHANGE

            // A new stream invalidates every chunk left from the last one.
            // Bumping here -- on WS-IO, before anything new is queued -- means
            // chunks already in flight toward decodeChannel carry the old tag
            // and are dropped by the worker (#114).
            if (!keepBuffered) decodeGeneration++

            // Post decoder lifecycle to the single-owner decode worker via the
            // channel. FIFO ordering between StartStream and any subsequent
            // Chunk tasks ensures the new decoder is in place before its
            // chunks are decoded. decoderReady is set optimistically so any
            // chunk arriving during reconfiguration is still enqueued; it
            // will decode with the new decoder once the worker drains the
            // StartStream task ahead of it.
            decoderReady = true
            // CRITICAL: launch on the scope's default Main dispatcher (NOT
            // Dispatchers.IO). The WebSocket receive loop invokes onStreamStart
            // and onAudioChunk serially from a single coroutine, but if those
            // callbacks each launch on a multi-thread dispatcher like
            // Dispatchers.IO, the launched coroutines race when reaching
            // decodeChannel.send(...). That reorders StartStream vs. its
            // following Chunks at the decoder -- the decoder is either still
            // the old one or absent when chunks arrive, producing dropped
            // frames or codec-state corruption (audibly: choppy audio,
            // glitches). Main is single-threaded, so launches drain in
            // submission order. ClosedSendChannelException is the benign
            // shutdown race -- worker releases the decoder in its finally.
            serviceScope.launch {
                try {
                    decodeChannel.send(
                        DecodeTask.StartStream(codec, sampleRate, channels, bitDepth, codecHeader, keepBuffered)
                    )
                } catch (e: ClosedSendChannelException) {
                    // benign: shutdown race
                }
            }

            // Non-decoder state updates continue to run on the main thread,
            // where SyncAudioPlayer + foreground-service + lock bookkeeping
            // live.
            mainHandler.post {
                currentCodec = codec
                currentSampleRate = sampleRate
                currentChannels = channels
                currentBitDepth = bitDepth
                broadcastSessionExtras()

                if (sendSpinClient?.getTimeFilter() == null) {
                    Log.e(TAG, "Cannot start audio: time filter not available")
                    return@post
                }

                // Acquire playback locks (CPU/WiFi) to prevent sleep during streaming
                // The foreground service is already running (started on connect)
                acquirePlaybackLocks()

                // Update notification to show we're now streaming
                startForegroundServiceWithNotification()

                // The decode worker switches the output of an active stream,
                // once the audio buffered in the old format has played.
                if (keepBuffered) return@post

                // Reuse existing player if format matches (DAC timestamps stay warm)
                val existingPlayer = syncAudioPlayer
                if (existingPlayer != null && existingPlayer.matchesFormat(sampleRate, channels, bitDepth)) {
                    Log.i(TAG, "Reusing existing SyncAudioPlayer - DAC already warm")
                    existingPlayer.clearBuffer()
                } else {
                    // Format changed or no existing player - create new one
                    existingPlayer?.release()
                    createSyncAudioPlayer(sampleRate, channels, bitDepth)
                }
            }
        }

        override fun onStreamClear() {
            Log.i(TAG, "[cmd-trace] T2 onStreamClear ts=${System.nanoTime() / 1_000_000} thread=${Thread.currentThread().name}")
            // Decoder flush goes through the channel so it is ordered with
            // any in-flight Chunk tasks: every chunk enqueued before the
            // stream/clear message decodes with the pre-flush decoder
            // state; every chunk enqueued after decodes with the flushed
            // decoder. Preserves the FIFO guarantee from the design.
            //
            // "clients MUST clear all buffered audio chunks and continue with
            // chunks received after this message": the bump discards chunks
            // still waiting to be decoded, which can be seconds of audio while
            // the worker is holding a format switch back.
            decodeGeneration++
            serviceScope.launch {
                try {
                    decodeChannel.send(DecodeTask.Flush)
                } catch (e: ClosedSendChannelException) {
                    // benign: shutdown race
                }
            }

            mainHandler.post {
                Log.i(TAG, "[cmd-trace] T3 onStreamClear.post ts=${System.nanoTime() / 1_000_000} thread=${Thread.currentThread().name}")
                Log.d(TAG, "Stream clear - flushing audio buffer")
                syncAudioPlayer?.clearBuffer()
            }
        }

        override fun onStreamEnd() {
            Log.i(TAG, "[cmd-trace] T2 onStreamEnd ts=${System.nanoTime() / 1_000_000} thread=${Thread.currentThread().name}")

            // Invalidate the ending stream's pre-buffered chunks (issue #114).
            decodeGeneration++
            activeStreamConfig = null

            mainHandler.post {
                Log.i(TAG, "[cmd-trace] T3 onStreamEnd.post ts=${System.nanoTime() / 1_000_000} thread=${Thread.currentThread().name}")
                Log.i(TAG, "Stream end - server terminated playback")
                // Close the fast-path gate so any chunks still in the WS receive
                // queue after stream/end are dropped before being launched into
                // the decode channel. The streamGeneration counter inside
                // SyncAudioPlayer is the primary defense against stale chunks,
                // but rejecting them at the source avoids the decode-pipeline
                // CPU burn. The next stream/start re-opens the gate.
                decoderReady = false
                // Enter idle mode: keep AudioTrack alive and writing silence
                // so DAC timestamps stay warm for the next stream start.
                //
                // Deliberately do NOT zero currentSampleRate/Channels/BitDepth here.
                // SendSpin fires stream/end whenever playback pauses, and the Now
                // Playing UI wants the codec/bit-depth/sample-rate chips to remain
                // visible through a pause. The spec is cleared on disconnect via
                // clearAudioStreamSpec() in the TransportState.Idle / Failed handlers.
                syncAudioPlayer?.enterIdle()
                broadcastSessionExtras()
            }
        }

        override fun onAudioChunk(serverTimeMicros: Long, audioData: ByteArray) {
            // Fast-path gate: once onStreamStart has been observed but before
            // any audio has been accepted, decoderReady stays false so we
            // don't pile chunks into the channel for a decoder that will
            // never exist. Normal steady state sees decoderReady = true.
            if (!decoderReady) return

            // Hand the chunk to the single-owner decode worker via the
            // channel. Wrapping send() in launch lets this callback return
            // immediately on WS-IO (fixing H-4); the launched coroutine
            // suspends on the channel if it is full. We deliberately do
            // NOT use trySend + drop: dropping compressed chunks mid-frame
            // corrupts codec state (this is exactly the regression PR #142
            // introduced via drop-oldest). Suspend-on-full is the
            // correctness property; see design doc H-4 / M-8 rationale.
            //
            // CRITICAL: launch on the scope's default Main dispatcher (NOT
            // a multi-threaded one like Dispatchers.IO). Chunks must reach
            // decodeChannel.send(...) in the same order onAudioChunk was
            // called; with a multi-thread pool the launched coroutines race
            // and chunks land out of order at the decoder, corrupting codec
            // state and producing choppy audio. Main is single-threaded so
            // launches drain in submission order. The 50 launches/sec cost
            // is ~150us/sec on Main -- negligible vs the frame budget.
            // ClosedSendChannelException is the benign shutdown race --
            // worker handles decoder release in its finally.
            //
            // Snapshot the generation on WS-IO so the tag reflects the stream
            // this chunk actually arrived on, not whatever is current by the
            // time the launched coroutine runs on the main dispatcher.
            val generation = decodeGeneration
            serviceScope.launch {
                try {
                    decodeChannel.send(DecodeTask.Chunk(serverTimeMicros, audioData, generation))
                } catch (e: ClosedSendChannelException) {
                    // benign: shutdown race
                }
            }
        }

        override fun onVolumeChanged(volume: Int) {
            Log.i(TAG, "[cmd-trace] T2 onVolumeChanged ts=${System.nanoTime() / 1_000_000} thread=${Thread.currentThread().name} vol=$volume")
            mainHandler.post {
                Log.i(TAG, "[cmd-trace] T3 onVolumeChanged.post ts=${System.nanoTime() / 1_000_000} thread=${Thread.currentThread().name} vol=$volume")
                // Convert from 0-100 to 0.0-1.0 and apply to device volume
                val volumeFloat = volume / 100f
                setVolume(volumeFloat)  // Sets device STREAM_MUSIC volume and the cached volume
                // Broadcast all state including volume to UI controllers
                broadcastSessionExtras()
            }
        }

        override fun onMutedChanged(muted: Boolean) {
            mainHandler.post {
                // Mute silences the output and leaves the volume alone: the
                // two are independent, and a later volume change must not
                // make a muted player audible.
                syncAudioPlayer?.setMuted(SyncAudioPlayer.MuteReason.PLAYER, muted)
                com.sendspindroid.UserSettings.setPlayerMuted(muted)
                // Update playback state with new mute status
                _playbackState.value = _playbackState.value.copy(muted = muted)
                // Broadcast all state including mute to UI controllers
                broadcastSessionExtras()
            }
        }

        override fun onSyncOffsetApplied(offsetMs: Double, source: String) {
            android.util.Log.i(TAG, "Sync offset applied: ${offsetMs}ms from $source")
            mainHandler.post {
                lastSyncOffsetMs = offsetMs
                lastSyncOffsetSource = source
                broadcastSessionExtras()
            }
        }

        override fun onSyncMuteChanged(muted: Boolean) {
            mainHandler.post {
                syncAudioPlayer?.setMuted(SyncAudioPlayer.MuteReason.SYNC, muted)
            }
        }

        override fun onNetworkChanged() {
            mainHandler.post {
                android.util.Log.i(TAG, "Network changed - triggering audio player reanchor")
                syncAudioPlayer?.clearBuffer()
            }
        }

    }

    /**
     * Fetches artwork from a URL using Coil.
     * Skipped in low memory mode.
     */
    private fun fetchArtwork(url: String) {
        // Skip artwork loading in low memory mode
        if (com.sendspindroid.UserSettings.lowMemoryMode) {
            return
        }

        val loader = imageLoader ?: return

        if (!isValidArtworkUrl(url)) {
            return
        }

        // Capture generation at launch. If the artwork state is cleared
        // (disconnect, track change, server-pushed clear) before the fetch
        // resolves, the late completion would re-publish the prior track's
        // image to the MediaSession. The check at completion drops stale
        // writes -- mirrors the queuePopulateGeneration pattern.
        val generation = artworkGeneration
        serviceScope.launch(Dispatchers.IO) {
            val errorReason: String? = try {
                val request = ImageRequest.Builder(this@PlaybackService)
                    .data(url)
                    .build()

                val result = loader.execute(request)
                if (result is SuccessResult) {
                    val bitmap = result.drawable.toBitmap()
                    val scaled = scaleArtwork(bitmap)
                    mainHandler.post {
                        // The track moved on while this was loading.
                        if (url != lastArtworkUrl) return@post
                        if (generation != artworkGeneration) {
                            Log.d(TAG, "Discarding stale artwork fetch (gen=$generation, current=$artworkGeneration)")
                            return@post
                        }
                        urlArtwork = scaled
                        updateMediaMetadata()
                    }
                    null
                } else {
                    "non-success result"
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to fetch artwork", e)
                e.javaClass.simpleName
            }

            // Fetch failed (network error, decode error, 404). The
            // onMetadataUpdate flicker fix keeps the prior track's bitmap
            // populated through the fetch so the UI doesn't flash the
            // placeholder; if the fetch never produces a replacement we
            // have to drop the prior bitmap now or the UI keeps showing
            // the wrong image for the rest of the new track.
            //
            // Guarded by the generation check so a slow fetch that gets
            // superseded by a later track's fetch doesn't drop the newer
            // bitmap that already arrived.
            if (errorReason != null) {
                mainHandler.post {
                    if (generation != artworkGeneration) return@post
                    if (urlArtwork == null) return@post
                    Log.d(TAG, "Dropping prior bitmap after fetch failure ($errorReason) for $url")
                    urlArtwork = null
                    updateMediaMetadata()
                }
            }
        }
    }

    private fun isValidArtworkUrl(url: String): Boolean {
        return url.startsWith("http://") || url.startsWith("https://")
    }

    /**
     * Pre-scales artwork bitmaps to fit within the MediaMetadata max size.
     *
     * The Android framework enforces a max of 320dp for MediaMetadata bitmaps
     * (config_mediaMetadataBitmapMaxSize). Oversized bitmaps get silently
     * downscaled and trigger StrictMode violations on Android 16+.
     * Pre-scaling avoids this overhead and keeps memory usage predictable.
     */
    private fun scaleArtwork(bitmap: Bitmap, maxSize: Int = MAX_ARTWORK_SIZE): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        if (width <= maxSize && height <= maxSize) return bitmap

        val scale = maxSize.toFloat() / maxOf(width, height)
        val newWidth = (width * scale).toInt()
        val newHeight = (height * scale).toInt()
        return Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
    }

    /**
     * Artwork URI for the MediaSession bridge, or null when there is none.
     * Out-of-process consumers (Android Auto, lock screen, AVRCP) fetch this
     * themselves, so an absent URL has to leave them on the bitmap blob
     * (artworkData) that MetadataForwardingPlayer already ships.
     */
    private fun externalArtworkUri(url: String?): Uri {
        // Fork: Uri.EMPTY, not null. MetadataForwardingPlayer reads null as
        // "preserve", which kept the previous track's URL on a track whose
        // art comes from the artwork stream; MainActivity prefers the URL.
        if (url.isNullOrEmpty()) return Uri.EMPTY
        return Uri.parse(url)
    }

    /**
     * Teardown when the transport goes Idle. Stops and releases the audio
     * player, closes the chunk fast-path gate, gives up locks and the
     * foreground unless the reconnect loop runs (releaseIfIdle), then clears
     * all per-track state BEFORE the broadcast -- broadcastSessionExtras reads
     * _playbackState and the codec spec at send time, so a stale bundle would
     * re-populate MainActivity's VM.
     *
     * @param error null clears a stale SendSpinPlayer error; non-null shows it.
     * @param preserveVolume keep volume/muted in the reset state, so the slider
     *   does not jump to 100 until the next connect reads the device volume.
     */
    @OptIn(UnstableApi::class)
    private fun tearDownConnection(error: String?, preserveVolume: Boolean) {
        // Stop debug logging session
        stopDebugLogging()
        AppLog.session.end()

        // Stop audio playback
        syncAudioPlayer?.stop()
        syncAudioPlayer?.release()
        syncAudioPlayer = null
        sendSpinPlayer?.setSyncAudioPlayer(null)
        // Close the chunk fast-path gate so any chunks still in the WS receive
        // queue post-disconnect drop before being launched into the decode pipeline.
        decoderReady = false
        sendSpinPlayer?.updateConnectionState(
            connected = false,
            reconnecting = coordinator.reconnectStatus.value is ReconnectStatus.Attempting,
        )
        // Locks and the foreground stay while the reconnect loop runs.
        releaseIfIdle()
        if (error == null) sendSpinPlayer?.clearError() else sendSpinPlayer?.setError(error)

        // Refresh browse tree root so "Connect" reappears
        mediaSession?.notifyChildrenChanged(MEDIA_ID_ROOT, 0, null)

        // Clear all per-track state BEFORE the disconnection broadcast (see the
        // KDoc above for why ordering matters).
        clearAudioStreamSpec()
        _artistArtwork.value = null
        _playbackState.value = if (preserveVolume) {
            PlaybackState(
                volume = _playbackState.value.volume,
                muted = _playbackState.value.muted,
            )
        } else {
            PlaybackState()
        }
        lastArtworkUrl = null
        lastTrackTitle = null
        urlArtwork = null
        binaryArtwork = null
        binaryArtworkGeneration++
        ++artworkGeneration
        forwardingPlayer?.clearMetadata()

        // Broadcast disconnection to controllers (MainActivity)
        broadcastSessionExtras()
    }

    @OptIn(UnstableApi::class)
    private fun updateMediaMetadata() {
        val state = _playbackState.value

        // Ancillary fields use the "" / 0 clear sentinel rather than null when
        // syncing the cache to state. MetadataForwardingPlayer treats null as
        // "preserve prior value" (useful for partial track updates), but here we
        // want it to mirror state exactly -- null in state means "the new track
        // has no value for this field" and the cache must clear, not leak the
        // previous track's albumArtist / year / albumTrack.
        //
        // clearArtwork = (effectiveArtwork == null) so a track change that has
        // wiped urlArtwork / binaryArtwork actually drops the prior bitmap from
        // the forwarding-player cache. Without it MetadataForwardingPlayer's
        // null-as-preserve semantics would keep the old track's artwork on the
        // lock screen until a new bitmap arrives, contradicting the
        // titleChanged invalidation that onMetadataUpdate already performed.
        forwardingPlayer?.updateMetadata(
            title = state.title,
            artist = state.artist,
            album = state.album,
            artwork = effectiveArtwork,
            artworkUri = externalArtworkUri(state.artworkUrl),
            clearArtwork = effectiveArtwork == null,
            albumArtist = state.albumArtist ?: "",
            year = state.year ?: 0,
            albumTrack = state.albumTrack ?: 0
        )

        broadcastSessionExtras()
    }

    /**
     * Broadcasts all session state (connection, metadata, group, volume) to
     * connected MediaControllers via session extras.
     *
     * This is how MainActivity and Android Auto react to state changes without
     * observing PlaybackService's StateFlows across the process boundary.
     * Broadcasting everything together keeps individual setSessionExtras calls
     * from overwriting each other, so call this whenever any state changes that
     * needs to be reflected in the UI.
     *
     * The connection state, server name, and error message are derived from the
     * coordinator / playback state here, so callers only need to make sure the
     * underlying state is set before invoking this. [forceState] /
     * [forceErrorMessage] are for callers that need to broadcast a state which
     * has not yet propagated through the coordinator's stateIn flow (e.g. the
     * connect*() entry points stamping STATE_CONNECTING before
     * SendSpinClient.connect() returns, or catch blocks reporting a synchronous
     * connect failure with the exception message).
     */
    private fun broadcastSessionExtras(
        forceState: String? = null,
        forceErrorMessage: String? = null,
    ) {
        val playbackState = _playbackState.value
        val sessionState = coordinator.sessionState.value
        val reconnectStatus = coordinator.reconnectStatus.value

        // coordinator.sessionState is a combine() of several upstream flows, so it
        // settles a beat after sendSpinClient.connectionState - the flow every
        // collector here reacts to. Deriving the published state from the lagging
        // copy made a broadcast triggered by a client transition announce the
        // PREVIOUS state.
        val sendSpinState = sendSpinClient?.connectionState?.value ?: sessionState.sendSpin

        // A reconnect in progress outranks what the attempt in flight looks
        // like: the loop goes through Connecting, Idle and Failed on its way.
        // Fork: forceState wins over both, for a terminal failure stamped from
        // the collector that holds a Failed state not yet published.
        val connectionStateString = forceState ?: when {
            sendSpinState is TransportState.Ready -> STATE_CONNECTED
            reconnectStatus is ReconnectStatus.Attempting -> STATE_RECONNECTING
            sendSpinState is TransportState.Failed -> STATE_ERROR
            sendSpinState is TransportState.Connecting -> STATE_CONNECTING
            else -> STATE_DISCONNECTED
        }

        val serverName: String? = sessionState.server?.name

        // Same source as connectionStateString above, or STATE_ERROR could be
        // published with a null message when the two copies disagree.
        val errorMessage: String? = forceErrorMessage ?: when (sendSpinState) {
            is TransportState.Failed -> failureReasonToMessage(sendSpinState.reason)
            else -> null
        }

        val extras = Bundle().apply {
            // Connection state
            when (connectionStateString) {
                STATE_DISCONNECTED -> putString(EXTRA_CONNECTION_STATE, STATE_DISCONNECTED)
                STATE_CONNECTING -> putString(EXTRA_CONNECTION_STATE, STATE_CONNECTING)
                STATE_CONNECTED -> {
                    putString(EXTRA_CONNECTION_STATE, STATE_CONNECTED)
                    serverName?.let { putString(EXTRA_SERVER_NAME, it) }
                    putString(EXTRA_ADMISSION_STATE, admissionState.name)
                    pairingCode?.let { putString(EXTRA_PAIRING_CODE, it) }
                    putBoolean(EXTRA_PAIRING_GESTURE_REQUESTED, pairingGestureRequested)
                }
                STATE_RECONNECTING -> {
                    putString(EXTRA_CONNECTION_STATE, STATE_RECONNECTING)
                    serverName?.let { putString(EXTRA_SERVER_NAME, it) }
                    (reconnectStatus as? ReconnectStatus.Attempting)?.let {
                        putInt(EXTRA_RECONNECT_ATTEMPT, it.attempt)
                    }
                }
                STATE_ERROR -> {
                    putString(EXTRA_CONNECTION_STATE, STATE_ERROR)
                    errorMessage?.let { putString(EXTRA_ERROR_MESSAGE, it) }
                }
            }

            // Metadata
            putString(EXTRA_TITLE, playbackState.title ?: "")
            putString(EXTRA_ARTIST, playbackState.artist ?: "")
            putString(EXTRA_ALBUM_ARTIST, playbackState.albumArtist ?: "")
            putString(EXTRA_ALBUM, playbackState.album ?: "")
            putString(EXTRA_ARTWORK_URL, playbackState.artworkUrl ?: "")
            putInt(EXTRA_YEAR, playbackState.year ?: 0)
            putInt(EXTRA_ALBUM_TRACK, playbackState.albumTrack ?: 0)
            putInt(EXTRA_QUEUE_TRACK, playbackState.queueTrack ?: 0)
            putInt(EXTRA_TOTAL_TRACKS, playbackState.totalTracks ?: 0)
            putLong(EXTRA_DURATION_MS, playbackState.durationMs)
            putLong(EXTRA_POSITION_MS, playbackState.positionMs)
            putLong(EXTRA_POSITION_UPDATED_AT, playbackState.positionUpdatedAt)

            // Group info
            playbackState.groupName?.let { putString(EXTRA_GROUP_NAME, it) }

            // Volume
            putInt(EXTRA_VOLUME, playbackState.volume)

            // Audio stream spec (0 = not currently streaming)
            putString(EXTRA_AUDIO_CODEC, currentCodec)
            putInt(EXTRA_AUDIO_SAMPLE_RATE, currentSampleRate)
            putInt(EXTRA_AUDIO_CHANNELS, currentChannels)
            putInt(EXTRA_AUDIO_BIT_DEPTH, currentBitDepth)

            // Sync offset (included here to avoid bare-bundle overwrites that
            // would clobber volume, metadata, and connection state)
            if (lastSyncOffsetMs != 0.0) {
                putDouble("sync_offset_ms", lastSyncOffsetMs)
                putString("sync_offset_source", lastSyncOffsetSource)
            }
        }

        // Skip the broadcast if nothing material changed since last push.
        // Fingerprint is a 64-bit hash over the bundle's stable fields --
        // good enough to detect identical contents without allocating a
        // diff structure each call.
        val fingerprint = fingerprintSessionExtras(extras)
        if (fingerprint != lastSessionExtrasFingerprint) {
            lastSessionExtrasFingerprint = fingerprint
            mediaSession?.setSessionExtras(extras)
        }
    }

    /**
     * Build a 64-bit fingerprint over the fields of a session-extras bundle so
     * we can skip identical re-broadcasts. Keys are sorted before hashing so the
     * fingerprint depends only on key/value contents, not on Bundle key-iteration
     * order (which is not guaranteed stable across API levels). Good enough to
     * detect identical contents without allocating a diff structure each call.
     */
    @Suppress("DEPRECATION") // Bundle.get(key) for mixed-type fingerprinting
    private fun fingerprintSessionExtras(extras: Bundle): Long {
        var h = 1125899906842597L // 2^50 - 27 (large prime starting value)
        for (key in extras.keySet().sorted()) {
            h = h * 31 + key.hashCode().toLong()
            val v = extras.get(key) ?: continue
            h = h * 31 + when (v) {
                is String -> v.hashCode().toLong()
                is Long -> v
                is Int -> v.toLong()
                is Boolean -> if (v) 1L else 0L
                is Double -> java.lang.Double.doubleToRawLongBits(v)
                else -> v.hashCode().toLong()
            }
        }
        return h
    }

    private fun failureReasonToMessage(reason: FailureReason): String = when (reason) {
        is FailureReason.AuthRejected -> "Authentication failed"
        is FailureReason.HandshakeFailed -> "Could not establish connection"
        is FailureReason.TransientNetwork -> "Network error"
        is FailureReason.ProtocolError -> "Protocol error"
        is FailureReason.ServerLacksEncryption ->
            "This server does not support encrypted connections. " +
                "Music Assistant 2.9 or newer is required."
    }

    /**
     * Connects to a SendSpin server because someone chose it: the user, Android
     * Auto, boot auto-connect. Takes over from a reconnect loop still trying
     * for the previous server.
     *
     * @param address Server address in "host:port" format
     * @param path WebSocket path (default: /sendspin)
     */
    fun connectToServer(address: String, path: String = "/sendspin") {
        coordinator.cancelReconnect()
        openConnection(address, path)
    }

    private fun openConnection(address: String, path: String) {
        Log.d(TAG, "Connecting to server: $address path=$path")

        // No broadcast here: the coordinator is still Idle at this point, and
        // the publisher reports whatever state the coordinator is in. The
        // Connecting branch of the connectionState collector announces it.

        try {
            // No disconnect() first: connect() leaves the current server
            // itself, with the goodbye reason a server switch requires.

            // Read current device volume and set as initial volume for server and UI
            val am = audioManager
            if (am != null) {
                val currentDeviceVolume = am.getStreamVolume(AudioManager.STREAM_MUSIC)
                val maxVolume = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                val volumePercent = ((currentDeviceVolume.toFloat() / maxVolume) * 100).toInt()
                Log.d(TAG, "Setting initial volume from device: $currentDeviceVolume/$maxVolume = $volumePercent%")
                // Mute is restored from settings: it is the app's own state,
                // where volume is the device's.
                val muted = com.sendspindroid.UserSettings.getPlayerMuted()
                sendSpinClient?.setInitialVolume(volumePercent, muted)
                // Also update playback state so UI shows correct volume from the start
                _playbackState.value = _playbackState.value.copy(volume = volumePercent, muted = muted)
            }

            sendSpinClient?.connect(SendSpinEndpoint.Local(address, path))
        } catch (e: Exception) {
            Log.e(TAG, "Error connecting to server", e)
            broadcastSessionExtras(
                forceState = STATE_ERROR,
                forceErrorMessage = "Connection failed: ${e.message}",
            )
        }
    }

    /**
     * Disconnects from the current server.
     */
    fun disconnectFromServer() {
        Log.d(TAG, "Disconnecting from server")
        sendSpinClient?.disconnect()
    }

    /**
     * An established connection has ended. This is the one place a reconnect
     * starts: the coordinator's loop, for the server the service was connected
     * to, unless the client chose to leave (user disconnect, server switch,
     * server/unpair, rejected activation). No activity needs to be alive.
     *
     * Runs before the connection-state collector sees the connection gone, so
     * that it finds the loop already running and leaves the service in the
     * foreground.
     */
    private fun onConnectionEnded(reconnect: Boolean) {
        if (isDestroyed) return
        val server = _currentServerFlow.value
        if (!reconnect || server == null) {
            coordinator.cancelReconnect()
            return
        }
        Log.i(TAG, "Connection to ${server.name} lost - reconnecting")
        startForegroundServiceWithNotification(server.name, reconnecting = true)
        mainHandler.removeCallbacks(reconnectLockRelease)
        mainHandler.postDelayed(reconnectLockRelease, RECONNECT_LOCK_HOLD_MS)
        startReconnectDiscovery(server)
        coordinator.connect(server)
    }

    // Playback locks are kept across a drop so the loop can run with the
    // screen off, but not for as long as a server may stay away. High Power
    // Mode's own locks are the user's choice and stay.
    private val reconnectLockRelease = Runnable {
        if (coordinator.reconnectStatus.value is ReconnectStatus.Attempting) {
            Log.i(TAG, "Still reconnecting after ${RECONNECT_LOCK_HOLD_MS / 60000}min - releasing playback locks")
            releasePlaybackLocks()
        }
    }

    private fun isConnectedOrReconnecting(): Boolean {
        val state = sendSpinClient?.connectionState?.value
        return state is TransportState.Ready || state is TransportState.Connecting ||
            coordinator.reconnectStatus.value is ReconnectStatus.Attempting
    }

    /**
     * Media3 takes the service out of the foreground on every notification
     * update while nothing is playing, and removes the notification when
     * there is nothing to show. A connected player that is idle or paused
     * would then be an ordinary background app: its network is cut within
     * seconds of the activity leaving the screen and the server loses the
     * player. The foreground is held for as long as there is a connection.
     */
    @OptIn(UnstableApi::class)
    override fun onUpdateNotification(session: MediaSession, startInForegroundRequired: Boolean) {
        val hold = isConnectedOrReconnecting()
        super.onUpdateNotification(session, startInForegroundRequired || hold)
        // With nothing to show, Media3 has just removed the notification and
        // the foreground with it. Otherwise its media notification holds it.
        val player = session.player
        if (hold && (player.currentTimeline.isEmpty || player.playbackState == Player.STATE_IDLE)) {
            startForegroundServiceWithNotification(
                _currentServerFlow.value?.name,
                reconnecting = coordinator.reconnectStatus.value is ReconnectStatus.Attempting,
            )
        }
    }

    /**
     * Give up the foreground and every lock once nothing is connected and
     * nothing is trying to be. While the reconnect loop runs the service is
     * still a media playback service with a session to restore, so it keeps
     * both.
     */
    private fun releaseIfIdle() {
        if (isConnectedOrReconnecting()) return
        sendSpinPlayer?.updateConnectionState(false)
        releasePlaybackLocks()
        releaseHighPowerLocks()
        stopForegroundNotification()
    }

    /**
     * Watch mDNS for [server] while the reconnect loop runs. Seeing it
     * announce itself retries immediately, and refreshes the saved address
     * the next attempt reads.
     */
    private fun startReconnectDiscovery(server: UnifiedServer) {
        stopReconnectDiscovery()
        reconnectDiscoveryManager = NsdDiscoveryManager(this, object : NsdDiscoveryManager.DiscoveryListener {
            override fun onServerDiscovered(name: String, address: String, path: String, friendlyName: String) {
                UnifiedServerRepository.addDiscoveredServer(friendlyName, address, path)
                if (isSameServer(server, name, friendlyName, address)) {
                    Log.i(TAG, "${server.name} seen on mDNS at $address - retrying now")
                    coordinator.retryNow()
                }
            }
            override fun onServerLost(name: String) {}
            override fun onDiscoveryStarted() {}
            override fun onDiscoveryStopped() {}
            override fun onDiscoveryError(error: String) {
                Log.w(TAG, "Reconnect: mDNS discovery error: $error")
            }
        }).also { it.startDiscovery() }
    }

    private fun stopReconnectDiscovery() {
        // cleanup() (not stopDiscovery()) so the multicast lock is released
        // even if onDiscoveryStarted never fired.
        reconnectDiscoveryManager?.cleanup()
        reconnectDiscoveryManager = null
    }

    /**
     * Sets the current server ID.
     * Call this before connecting when the server ID is known.
     *
     * @param serverId The UnifiedServer.id
     */
    fun setCurrentServer(serverId: String?) {
        Log.d(TAG, "Set current server: $serverId")

        _currentServerFlow.value = serverId?.let { UnifiedServerRepository.getServer(it) }
    }

    /**
     * One attempt of the reconnect loop: opens the connection, then awaits
     * the SendSpin.connectionState transition out of Connecting.
     *
     * Returns true on Ready, false on anything else or on timeout.
     */
    private suspend fun connectViaSelectedConnection(
        server: com.sendspindroid.model.UnifiedServer,
        selectedConnection: ConnectionSelector.SelectedConnection,
    ): Boolean {
        // Short-circuit if SendSpin client construction failed in onCreate.
        val client = sendSpinClient ?: return false

        // Set the active server first so observers see context immediately.
        setCurrentServer(server.id)

        // A connection someone started while the loop was waiting is the one
        // to wait for, not one to replace.
        val busy = client.connectionState.value.let {
            it is TransportState.Connecting || it is TransportState.Ready
        }
        if (!busy) {
            when (selectedConnection) {
                is ConnectionSelector.SelectedConnection.Local ->
                    openConnection(selectedConnection.address, selectedConnection.path)
            }
        }

        return withTimeoutOrNull(CONNECT_TIMEOUT_MS) {
            client.connectionState.first { it !is TransportState.Connecting } is TransportState.Ready
        } ?: run {
            Log.w(TAG, "connectViaSelectedConnection timed out after ${CONNECT_TIMEOUT_MS}ms; cancelling transport")
            disconnectFromServer()
            false
        }
    }

    /**
     * Sets the playback volume via device STREAM_MUSIC (Spotify-style).
     *
     * Volume is controlled via the device's media stream, not per-app gain.
     * This enables hardware volume button support and follows best practices
     * used by Spotify, Plexamp, and other major media apps.
     *
     * @param volume Normalized volume from 0.0 (mute) to 1.0 (full)
     */
    fun setVolume(volume: Float) {
        val am = audioManager ?: return
        val maxVolume = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val newVolume = (volume * maxVolume).roundToInt().coerceIn(0, maxVolume)

        Log.d(TAG, "Setting device volume: $newVolume/$maxVolume (normalized: $volume)")

        // Update tracking to prevent echo in observer
        lastKnownVolume = newVolume

        // Every session-extras broadcast resends this value to the activity, so
        // it has to follow each volume set here or the slider snaps back to it.
        _playbackState.value = _playbackState.value.copy(volume = (volume * 100).roundToInt())

        // Set device volume (no flags = silent, no UI popup)
        am.setStreamVolume(AudioManager.STREAM_MUSIC, newVolume, 0)
        Log.i(TAG, "[cmd-trace] T4 setStreamVolume ts=${System.nanoTime() / 1_000_000} thread=${Thread.currentThread().name} new=$newVolume max=$maxVolume")
    }

    /**
     * Acquires wake lock and WiFi lock to keep CPU and WiFi running during audio playback.
     * This prevents the system from putting CPU/WiFi to sleep while streaming.
     *
     * NOTE: This does NOT start the foreground service - that's done separately in onConnected()
     * via startForegroundServiceWithNotification(). The foreground service protects the process
     * from being killed, while these locks protect against CPU/WiFi sleep during active streaming.
     *
     * Wake lock strategy for battery safety:
     * - Uses a 30-minute timeout instead of indefinite or very long timeout
     * - Refreshes the wake lock every 20 minutes during active playback
     * - If the app crashes without releasing, max battery drain is limited to 30 minutes
     * - The refresh mechanism ensures continuous playback isn't interrupted
     */
    @Suppress("DEPRECATION")
    private fun acquirePlaybackLocks() {
        // Request audio focus first - required for Android Auto to route audio to us
        requestAudioFocus()

        // CPU wake lock with 30-minute timeout for battery safety
        // Refreshed periodically during active playback
        if (wakeLock == null) {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "SendSpinDroid::AudioPlayback"
            )
        }
        if (wakeLock?.isHeld == false) {
            wakeLock?.acquire(WAKE_LOCK_TIMEOUT_MS)
            Log.d(TAG, "Wake lock acquired with ${WAKE_LOCK_TIMEOUT_MS / 60000}min timeout")

            // Start periodic refresh to keep wake lock alive during long playback sessions
            wakeLockHandler.removeCallbacks(wakeLockRefreshRunnable)
            wakeLockHandler.postDelayed(wakeLockRefreshRunnable, WAKE_LOCK_REFRESH_INTERVAL_MS)
        }

        // WiFi lock - keeps WiFi active even when screen is off
        if (wifiLock == null) {
            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            wifiLock = wifiManager.createWifiLock(
                WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                "SendSpinDroid::AudioStreaming"
            )
        }
        if (wifiLock?.isHeld == false) {
            wifiLock?.acquire()
            Log.d(TAG, "WiFi lock acquired")
        }
    }

    /**
     * Refreshes the wake lock by releasing and re-acquiring with a fresh timeout.
     * Called periodically during active playback to prevent the wake lock from expiring.
     */
    private fun refreshWakeLock() {
        if (wakeLock?.isHeld == true) {
            // Release and re-acquire with fresh timeout
            wakeLock?.release()
            wakeLock?.acquire(WAKE_LOCK_TIMEOUT_MS)
            Log.d(TAG, "Wake lock refreshed with ${WAKE_LOCK_TIMEOUT_MS / 60000}min timeout")
        } else {
            Log.d(TAG, "Wake lock refresh skipped - not held")
        }
    }

    /**
     * Checks if the audio player is actively playing or waiting to start.
     * Used to determine whether to continue refreshing the wake lock.
     */
    private fun isActivelyPlaying(): Boolean {
        val state = syncAudioPlayer?.getPlaybackState()
        return state == com.sendspindroid.sendspin.PlaybackState.PLAYING ||
               state == com.sendspindroid.sendspin.PlaybackState.WAITING_FOR_START
    }

    /**
     * Checks if we are currently connected to a server.
     */
    private fun isConnected(): Boolean {
        return coordinator.sessionState.value.sendSpin is TransportState.Ready
    }

    /**
     * Logs the current stats to AppLog if logging is enabled.
     * Called periodically when debug mode is active.
     */
    private fun logCurrentStats() {
        val audioStats = syncAudioPlayer?.getStats() ?: return
        val timeFilter = sendSpinClient?.getTimeFilter() ?: return

        val syncStats = SyncStats(
            playbackState = audioStats.playbackState,
            isPlaying = audioStats.isPlaying,
            syncErrorUs = audioStats.syncErrorUs,
            smoothedSyncErrorUs = audioStats.smoothedSyncErrorUs,
            startTimeCalibrated = audioStats.startTimeCalibrated,
            samplesReadSinceStart = audioStats.samplesReadSinceStart,
            queuedSamples = audioStats.queuedSamples,
            chunksReceived = audioStats.chunksReceived,
            chunksPlayed = audioStats.chunksPlayed,
            chunksDropped = audioStats.chunksDropped,
            gapsFilled = audioStats.gapsFilled,
            gapSilenceMs = audioStats.gapSilenceMs,
            overlapsTrimmed = audioStats.overlapsTrimmed,
            overlapTrimmedMs = audioStats.overlapTrimmedMs,
            insertEveryNFrames = audioStats.insertEveryNFrames,
            dropEveryNFrames = audioStats.dropEveryNFrames,
            framesInserted = audioStats.framesInserted,
            framesDropped = audioStats.framesDropped,
            syncCorrections = audioStats.syncCorrections,
            clockReady = timeFilter.isReady,
            clockConverged = timeFilter.isConverged,
            clockOffsetUs = timeFilter.offsetMicros,
            clockDriftPpm = timeFilter.driftPpm,
            clockErrorUs = timeFilter.errorMicros,
            measurementCount = timeFilter.measurementCountValue,
            totalFramesWritten = audioStats.totalFramesWritten,
            serverTimelineCursorUs = audioStats.serverTimelineCursorUs,
            scheduledStartLoopTimeUs = audioStats.scheduledStartLoopTimeUs,
            firstServerTimestampUs = audioStats.firstServerTimestampUs,
            convergenceTimeMs = timeFilter.convergenceTimeMillis
        )

        AppLog.Audio.d("Stats: " +
            "state=${syncStats.playbackState.name}, " +
            "syncErr=${syncStats.syncErrorUs}us, " +
            "queue=${syncStats.queuedSamples}, " +
            "offset=${syncStats.clockOffsetUs}us, " +
            "insertN=${syncStats.insertEveryNFrames}, dropN=${syncStats.dropEveryNFrames}, " +
            "framesIns=${syncStats.framesInserted}, framesDrop=${syncStats.framesDropped}")
    }

    /**
     * Starts the debug logging loop if debug mode is enabled.
     */
    private fun startDebugLogging() {
        if (AppLog.level != LogLevel.OFF) {
            debugLogHandler.removeCallbacks(debugLogRunnable)
            debugLogHandler.postDelayed(debugLogRunnable, DEBUG_LOG_INTERVAL_MS)
            Log.d(TAG, "Debug logging started")
        }
    }

    /**
     * Stops the debug logging loop.
     */
    private fun stopDebugLogging() {
        debugLogHandler.removeCallbacks(debugLogRunnable)
        Log.d(TAG, "Debug logging stopped")
    }

    /**
     * Starts the service in foreground mode with a notification.
     * This is required on Android 8+ to keep the service alive when the app is in the background.
     * On Android 14+, we must specify the foreground service type.
     *
     * @param serverName Optional server name for context-aware notification text.
     *                   If provided, shows "Connected to [serverName]".
     *                   If null, shows "Streaming audio..." (during active playback).
     * @param reconnecting Shows "Reconnecting to [serverName]..." instead.
     */
    private fun startForegroundServiceWithNotification(serverName: String? = null, reconnecting: Boolean = false) {
        // Started as well as bound: a service that is only bound is destroyed
        // when the activity lets go of its controller, foreground or not.
        // Refused from the background, where it is already started.
        runCatching { startService(Intent(this, PlaybackService::class.java)) }
        try {
            val contentText = when {
                reconnecting -> "Reconnecting to $serverName..."
                serverName != null -> "Connected to $serverName"
                else -> "Streaming audio..."
            }

            val notification = NotificationCompat.Builder(this, NotificationHelper.CHANNEL_ID)
                .setContentTitle("SendSpin")
                .setContentText(contentText)
                .setSmallIcon(com.sendspindroid.R.drawable.ic_launcher_foreground)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .build()

            // Use ServiceCompat for backward compatibility
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceCompat.startForeground(
                    this,
                    NotificationHelper.NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                )
            } else {
                startForeground(NotificationHelper.NOTIFICATION_ID, notification)
            }
            Log.d(TAG, "Foreground service started with text: $contentText")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start foreground service", e)
        }
    }

    /**
     * Releases wake lock and WiFi lock when playback stops.
     * Cancels the wake lock refresh handler.
     *
     * NOTE: This does NOT stop the foreground service notification - that's done separately
     * in stopForegroundNotification(). This allows the service to stay alive for reconnection
     * while not draining battery with CPU/WiFi locks during idle periods.
     */
    private fun releasePlaybackLocks() {
        // Abandon audio focus - tells the system we're done producing audio
        abandonAudioFocus()

        // Stop the periodic wake lock refresh first
        wakeLockHandler.removeCallbacks(wakeLockRefreshRunnable)

        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
            Log.d(TAG, "Wake lock released")
        }
        if (wifiLock?.isHeld == true) {
            wifiLock?.release()
            Log.d(TAG, "WiFi lock released")
        }
    }

    /**
     * Requests audio focus for music playback.
     *
     * This is required for Android Auto (and car Bluetooth) to hand over the audio
     * output channel from whatever is currently playing (FM radio, other apps, etc.).
     * Without this call, the car infotainment system doesn't know our app wants to
     * produce audio and won't route output to us.
     */
    private fun requestAudioFocus() {
        val am = audioManager ?: return
        synchronized(audioFocusLock) {
            if (hasAudioFocus) return

            val request = audioFocusRequest ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    AndroidAudioAttributes.Builder()
                        .setUsage(AndroidAudioAttributes.USAGE_MEDIA)
                        .setContentType(AndroidAudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                // Deliver "can duck" losses to the listener instead of letting
                // the system duck us: ducking one synced player sounds wrong
                // next to the others, so it is muted like any other transient loss.
                .setWillPauseWhenDucked(true)
                .setOnAudioFocusChangeListener { focusChange ->
                    mainHandler.post {
                        handleAudioFocusChange(focusChange)
                    }
                }
                .build()
                .also { audioFocusRequest = it }

            val result = am.requestAudioFocus(request)
            hasAudioFocus = (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
            // Fork: the listener is registered as soon as requestAudioFocus()
            // returns, granted or not; abandonAudioFocus keys off this.
            audioFocusRegistered = true
            Log.i(FOCUS_TAG, "focus requested: ${if (hasAudioFocus) "granted" else "denied"}")
            if (hasAudioFocus) {
                // We own the output again: clear any 'external_source' report
                // and any mute left by an interruption.
                sendSpinClient?.setExternalSource(false)
                setInterruptionMuted(false)
            }
        }
    }

    /**
     * Abandons audio focus when playback stops.
     *
     * Gated on audioFocusRegistered (not hasAudioFocus) so a service teardown
     * during AUDIOFOCUS_LOSS_TRANSIENT still releases the AudioManager
     * listener -- otherwise the lambda captures this service and the system
     * keeps it alive past onDestroy().
     */
    private fun abandonAudioFocus() {
        synchronized(audioFocusLock) {
            if (!audioFocusRegistered) return
            // Abandoning now would lose the focus-gain callback that ends the
            // interruption (un-mute, resume after a call). The gain abandons instead.
            if (transientFocusLoss != null) return

            audioFocusRequest?.let { request ->
                audioManager?.abandonAudioFocusRequest(request)
                Log.i(FOCUS_TAG, "focus abandoned")
            }
            hasAudioFocus = false
            audioFocusRegistered = false
        }
    }

    /**
     * Handles audio focus changes from the system.
     */
    private fun handleAudioFocusChange(focusChange: Int) {
        val event = AudioInterruption.fromFocusChange(focusChange)
        if (event == null) {
            Log.i(FOCUS_TAG, "focus change $focusChange ignored")
            return
        }
        handleAudioInterruption(event)
    }

    /**
     * Another app wants the output, or has given it back: ask
     * [AudioInterruptionPolicy] what to do, log it and do it.
     *
     * A local interruption never pauses [syncAudioPlayer]. It is either a pause
     * on the server (which then stops sending, and the server-state handling
     * stops the player) or a mute, under which audio keeps draining in sync.
     */
    private fun handleAudioInterruption(event: AudioInterruption) {
        val audioMode = audioManager?.mode ?: AudioManager.MODE_NORMAL
        val serverState = _playbackState.value.playbackState
        val canSendPause = sendSpinClient?.let { it.isConnected && it.canSendCommand("pause") } == true
        val action = AudioInterruptionPolicy.decide(event, audioMode, serverState, canSendPause, pausedForCall)
        Log.i(
            FOCUS_TAG,
            "$event mode=${AudioInterruptionPolicy.modeName(audioMode)} server=$serverState " +
                "canSendPause=$canSendPause pausedForCall=$pausedForCall -> $action"
        )

        mainHandler.removeCallbacks(callModeRecheckRunnable)
        if (event != AudioInterruption.BECOMING_NOISY) {
            transientFocusLoss = event.takeIf { it.isTransientLoss }
        }

        when (action) {
            InterruptionAction.PAUSE_ON_SERVER -> {
                // Muted first: the pause takes a round trip to stop the audio.
                setInterruptionMuted(true)
                // Only a call is resumed afterwards, never an output disconnect.
                pausedForCall = event.isTransientLoss
                sendSpinClient?.pause()
            }
            InterruptionAction.RESUME_ON_SERVER -> {
                pausedForCall = false
                setInterruptionMuted(false)
                sendSpinClient?.play()
            }
            InterruptionAction.MUTE_LOCALLY -> setInterruptionMuted(true)
            InterruptionAction.UNMUTE_LOCALLY -> setInterruptionMuted(false)
            InterruptionAction.BECOME_UNAVAILABLE -> {
                // Another app took focus permanently: report 'external_source'
                // per spec. The server parks this client in a solo group and
                // ends its streams; playing again re-requests focus, which
                // clears the state and the mute.
                hasAudioFocus = false
                pausedForCall = false
                setInterruptionMuted(true)
                sendSpinClient?.setExternalSource(true)
            }
            InterruptionAction.NONE -> {}
        }

        if (transientFocusLoss != null && !AudioInterruptionPolicy.isCallMode(audioMode)) {
            mainHandler.postDelayed(callModeRecheckRunnable, CALL_MODE_RECHECK_MS)
        }
        // Focus was kept through the interruption (see abandonAudioFocus);
        // give it up now if nothing is playing.
        if (event == AudioInterruption.FOCUS_GAIN &&
            action != InterruptionAction.RESUME_ON_SERVER &&
            serverState != PlaybackStateType.PLAYING
        ) {
            abandonAudioFocus()
        }
    }

    private fun setInterruptionMuted(muted: Boolean) {
        if (interruptionMuted == muted) return
        interruptionMuted = muted
        Log.i(FOCUS_TAG, if (muted) "output muted" else "output un-muted")
        syncAudioPlayer?.setMuted(SyncAudioPlayer.MuteReason.INTERRUPTION, muted)
    }

    /**
     * Called with every playback state the server reports, before it is stored:
     * forget our pause-for-a-call once someone else has changed playback, so the
     * end of the call does not resume over their choice.
     */
    private fun noteServerPlaybackState(newState: PlaybackStateType) {
        val previous = _playbackState.value.playbackState
        if (pausedForCall && AudioInterruptionPolicy.callPauseSuperseded(previous, newState)) {
            pausedForCall = false
            Log.i(FOCUS_TAG, "call pause superseded (server $previous -> $newState): will not resume")
        }
    }

    /**
     * Register a receiver for ACTION_AUDIO_BECOMING_NOISY so the sound stops when
     * the audio output device disconnects. Idempotent.
     */
    private fun registerBecomingNoisyReceiver() {
        if (becomingNoisyReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                    handleBecomingNoisy()
                }
            }
        }
        becomingNoisyReceiver = receiver
        // System-protected broadcast; not exported (constant value is inert on
        // API < 33, the 3-arg overload exists since API 26).
        registerReceiver(
            receiver,
            IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
            Context.RECEIVER_NOT_EXPORTED
        )
        Log.d(TAG, "Registered ACTION_AUDIO_BECOMING_NOISY receiver")
    }

    /**
     * Handles ACTION_AUDIO_BECOMING_NOISY: the audio output is rerouting to the
     * built-in speaker because an external output disconnected (wired headphones
     * unplugged, Bluetooth/Android Auto disconnected). Follow the Android
     * convention and stop the sound: pause the group and do not resume. If the
     * pause cannot be sent the output is muted instead, until playback is next
     * started.
     *
     * ACTION_AUDIO_BECOMING_NOISY is fired once by the system per transition, so
     * it is inherently single-fire -- unlike AudioDeviceCallback.onAudioDevicesRemoved,
     * which can fire several times for one Bluetooth device (A2DP + SCO) and
     * would need debouncing.
     */
    private fun handleBecomingNoisy() {
        handleAudioInterruption(AudioInterruption.BECOMING_NOISY)
    }

    /**
     * Acquires High Power Mode locks (WiFi + CPU) for the entire connection lifetime.
     * These are separate from streaming locks and use low-latency WiFi mode.
     * Called when High Power Mode is enabled and the client is connected.
     */
    @Suppress("DEPRECATION")
    private fun acquireHighPowerLocks() {
        // WiFi lock with low-latency mode (API 29+) for faster ping detection
        if (highPowerWifiLock == null) {
            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val wifiMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            } else {
                WifiManager.WIFI_MODE_FULL_HIGH_PERF
            }
            highPowerWifiLock = wifiManager.createWifiLock(wifiMode, "SendSpinDroid::HighPower")
        }
        if (highPowerWifiLock?.isHeld == false) {
            highPowerWifiLock?.acquire()
            Log.d(TAG, "High Power WiFi lock acquired")
        }

        // CPU wake lock to prevent deep sleep between tracks
        if (highPowerWakeLock == null) {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            highPowerWakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "SendSpinDroid::HighPower"
            )
        }
        if (highPowerWakeLock?.isHeld == false) {
            highPowerWakeLock?.acquire(WAKE_LOCK_TIMEOUT_MS)
            Log.d(TAG, "High Power wake lock acquired with ${WAKE_LOCK_TIMEOUT_MS / 60000}min timeout")

            // Start periodic refresh to keep wake lock alive indefinitely
            wakeLockHandler.removeCallbacks(highPowerWakeLockRefreshRunnable)
            wakeLockHandler.postDelayed(highPowerWakeLockRefreshRunnable, WAKE_LOCK_REFRESH_INTERVAL_MS)
        }
    }

    /**
     * Releases High Power Mode locks.
     * Called on disconnect or when High Power Mode is disabled.
     */
    private fun releaseHighPowerLocks() {
        wakeLockHandler.removeCallbacks(highPowerWakeLockRefreshRunnable)
        if (highPowerWakeLock?.isHeld == true) {
            highPowerWakeLock?.release()
            Log.d(TAG, "High Power wake lock released")
        }
        if (highPowerWifiLock?.isHeld == true) {
            highPowerWifiLock?.release()
            Log.d(TAG, "High Power WiFi lock released")
        }
    }

    /**
     * Called when High Power Mode is toggled in settings.
     * Acquires or releases locks based on the new state and current connection.
     */
    private fun onHighPowerModeChanged(enabled: Boolean) {
        if (enabled && isConnected()) {
            acquireHighPowerLocks()
        } else {
            releaseHighPowerLocks()
        }
    }

    /**
     * Stops the foreground service notification.
     * Called when fully disconnecting from a server.
     */
    private fun stopForegroundNotification() {
        try {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            // Bound clients, if any, keep it alive from here.
            stopSelf()
            Log.d(TAG, "Foreground notification removed")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop foreground service", e)
        }
    }

    @OptIn(UnstableApi::class)
    private fun initializeMediaSession() {
        val player = sendSpinPlayer ?: run {
            Log.e(TAG, "Cannot create MediaSession: sendSpinPlayer is null")
            return
        }

        forwardingPlayer = MetadataForwardingPlayer(player)

        // Create PendingIntent for notification tap - opens MainActivity
        val sessionActivityIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val sessionActivityPendingIntent = PendingIntent.getActivity(
            this,
            0,
            sessionActivityIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        mediaSession = MediaLibrarySession.Builder(this, forwardingPlayer!!, LibraryCallback())
            .setSessionActivity(sessionActivityPendingIntent)
            .build()

        Log.d(TAG, "MediaLibrarySession initialized with browse tree support")
    }

    private inner class LibraryCallback : MediaLibrarySession.Callback {

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<MediaItem>> {
            // Log incoming root hints for debugging Android Auto behavior
            val rootHints = params?.extras
            val tabLimit = rootHints?.getInt(
                "androidx.media.MediaBrowserServiceCompat.BrowserRoot.Extras.KEY_ROOT_CHILDREN_LIMIT", -1
            ) ?: -1
            Log.i(TAG, "onGetLibraryRoot called by: ${browser.packageName}" +
                    " (uid=${browser.uid})" +
                    ", tabLimit=$tabLimit, params=$params")

            val rootItem = MediaItem.Builder()
                .setMediaId(MEDIA_ID_ROOT)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle("SendSpinDroid")
                        .setIsPlayable(false)
                        .setIsBrowsable(true)
                        .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                        .build()
                )
                .build()

            // Build LibraryParams with content style defaults. Search is not
            // advertised: SendSpin defines no library to search.
            val extras = Bundle().apply {
                putInt(CONTENT_STYLE_BROWSABLE, CONTENT_STYLE_LIST)
                putInt(CONTENT_STYLE_PLAYABLE, CONTENT_STYLE_LIST)
            }
            val libraryParams = LibraryParams.Builder().setExtras(extras).build()

            return Futures.immediateFuture(LibraryResult.ofItem(rootItem, libraryParams))
        }

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            Log.d(TAG, "onGetChildren: parentId=$parentId, page=$page")

            // Sync path: root tabs
            if (parentId == MEDIA_ID_ROOT) {
                return Futures.immediateFuture(
                    LibraryResult.ofItemList(ImmutableList.copyOf(getRootChildren()), params)
                )
            }

            // Async path: server discovery (bounded mDNS wait)
            return suspendToFuture {
                val items = when (parentId) {
                    MEDIA_ID_DISCOVERED -> getDiscoveredServers()
                    else -> {
                        // Never hand Android Auto an empty list here -- see the
                        // invariant documented on AutoBrowseTree.unknownParentItem.
                        Log.w(TAG, "Unknown parentId for onGetChildren: $parentId")
                        listOf(AutoBrowseTree.unknownParentItem(parentId))
                    }
                }
                LibraryResult.ofItemList(ImmutableList.copyOf(items), params)
            }
        }

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String
        ): ListenableFuture<LibraryResult<MediaItem>> {
            Log.d(TAG, "onGetItem: mediaId=$mediaId")

            val item = findItemById(mediaId)
            return if (item != null) {
                Futures.immediateFuture(LibraryResult.ofItem(item, null))
            } else {
                Futures.immediateFuture(
                    LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
                )
            }
        }

        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: List<MediaItem>
        ): ListenableFuture<List<MediaItem>> {
            Log.d(TAG, "onAddMediaItems: ${mediaItems.size} items")

            val updatedItems = mediaItems.map { item ->
                val mediaId = item.mediaId

                when {
                    // Saved server (looked up by UUID, auto-selects connection method)
                    mediaId.startsWith(MEDIA_ID_SAVED_SERVER_PREFIX) -> {
                        val serverId = mediaId.removePrefix(MEDIA_ID_SAVED_SERVER_PREFIX)
                        Log.d(TAG, "User selected saved server: $serverId")

                        val server = UnifiedServerRepository.savedServers.value
                            .find { it.id == serverId }
                        if (server != null) {
                            // Use ConnectionSelector to pick the best method
                            val selected = ConnectionSelector.selectConnection(server)

                            when (selected) {
                                is ConnectionSelector.SelectedConnection.Local -> {
                                    setCurrentServer(server.id)
                                    connectToServer(selected.address, selected.path)
                                }
                                null -> {
                                    Log.w(TAG, "No connection method for saved server: ${server.name}")
                                }
                            }
                            UnifiedServerRepository.updateLastConnected(server.id)
                        } else {
                            Log.w(TAG, "Saved server not found: $serverId")
                        }

                        item.buildUpon()
                            .setUri("sendspin://saved/$serverId")
                            .build()
                    }

                    // Discovered server (by address, local connection only)
                    mediaId.startsWith(MEDIA_ID_SERVER_PREFIX) -> {
                        val serverAddress = mediaId.removePrefix(MEDIA_ID_SERVER_PREFIX)
                        Log.d(TAG, "User selected server: $serverAddress")

                        // Look up UnifiedServer by local address
                        val unifiedServer = UnifiedServerRepository.allServers.value.find {
                            it.local?.address == serverAddress
                        }
                        if (unifiedServer != null) {
                            setCurrentServer(unifiedServer.id)
                        } else {
                            Log.w(TAG, "No UnifiedServer found for address: $serverAddress")
                        }

                        connectToServer(serverAddress)

                        // Update last-connected timestamp (replaces legacy addToRecent)
                        if (unifiedServer != null) {
                            UnifiedServerRepository.updateLastConnected(unifiedServer.id)
                        }

                        item.buildUpon()
                            .setUri("sendspin://$serverAddress")
                            .build()
                    }
                    else -> item
                }
            }

            return Futures.immediateFuture(updatedItems)
        }

        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo
        ): MediaSession.ConnectionResult {
            Log.i(TAG, "Controller connecting: ${controller.packageName}" +
                    " (uid=${controller.uid})" +
                    ", session=${session.id}")

            // Must use DEFAULT_SESSION_AND_LIBRARY_COMMANDS (not DEFAULT_SESSION_COMMANDS)
            // so that the legacy MediaBrowserServiceCompat compat bridge includes browse/root
            // commands needed by Android Auto and other MediaBrowserCompat clients.
            val sessionCommands = MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS.buildUpon()
                .add(SessionCommand(COMMAND_CONNECT, Bundle.EMPTY))
                .add(SessionCommand(COMMAND_DISCONNECT, Bundle.EMPTY))
                .add(SessionCommand(COMMAND_SET_VOLUME, Bundle.EMPTY))
                .add(SessionCommand(COMMAND_NEXT, Bundle.EMPTY))
                .add(SessionCommand(COMMAND_PREVIOUS, Bundle.EMPTY))
                .add(SessionCommand(COMMAND_SWITCH_GROUP, Bundle.EMPTY))
                .add(SessionCommand(COMMAND_GET_STATS, Bundle.EMPTY))
                .add(SessionCommand(COMMAND_ALLOW_PAIRING, Bundle.EMPTY))
                .build()

            // Player commands must include SET_MEDIA_ITEM so the legacy compat bridge
            // can translate playFromMediaId -> onAddMediaItems for Android Auto.
            val playerCommands = MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS.buildUpon()
                .add(Player.COMMAND_SET_MEDIA_ITEM)
                .add(Player.COMMAND_PREPARE)
                .build()

            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(sessionCommands)
                .setAvailablePlayerCommands(playerCommands)
                .build()
        }

        override fun onDisconnected(
            session: MediaSession,
            controller: MediaSession.ControllerInfo
        ) {
            Log.i(TAG, "Controller disconnected: ${controller.packageName} (uid=${controller.uid})")
        }

        @Suppress("OVERRIDE_DEPRECATION")
        override fun onPlayerCommandRequest(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            playerCommand: Int
        ): Int {
            if (playerCommand != Player.COMMAND_PLAY_PAUSE) return SessionResult.RESULT_SUCCESS
            if (hasAudioFocus) return SessionResult.RESULT_SUCCESS

            // No focus: opportunistically re-acquire only when the request
            // came from our own in-app UI. System-forwarded media keys (TV
            // remote, Bluetooth headset) arrive with packageName "android";
            // Android Auto and other external controllers use their own
            // package. Letting those grab focus would make SendSpinDroid
            // respond to remote Play/Pause in the background while the user
            // is interacting with a different foreground media app -- the
            // user's Play press toggles both their video app and us.
            val isOwnUi = controller.packageName == applicationContext.packageName
            if (isOwnUi) {
                requestAudioFocus()
                if (hasAudioFocus) return SessionResult.RESULT_SUCCESS
            }

            Log.i(TAG, "Suppressing PLAY_PAUSE from ${controller.packageName} (no audio focus, isOwnUi=$isOwnUi)")
            return SessionResult.RESULT_ERROR_INVALID_STATE
        }

        // Hardware media keys (TV remote, Bluetooth headset) bypass
        // onPlayerCommandRequest entirely: Media3 routes them through
        // MediaSessionService.dispatchMediaKeyEvent, translates them to
        // Player.play()/pause() (or next/previous/etc.), and invokes those
        // methods directly on the player. We confirmed this in logcat -- a
        // KEYCODE_MEDIA_PLAY_PAUSE dispatch is immediately followed by
        // SendSpinPlayer.setPlayWhenReady with no callback in between.
        //
        // onMediaButtonEvent is the only place to intercept that path. The
        // gate is foreground state (not audio focus): when SendSpinDroid is
        // playing in the background with audio focus and the user opens a
        // video app, hasAudioFocus is still true on the first key press, so
        // a focus-only gate lets the event through. Use ProcessLifecycleOwner
        // to detect whether any of our activities is visible. If none is,
        // drop the event so the foreground app's own dispatchKeyEvent path
        // can handle it alone. The own-package short-circuit is kept for
        // safety -- in-app controllers binding via MediaController shouldn't
        // travel through media-button dispatch in practice, but if they do,
        // they should be allowed.
        @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
        override fun onMediaButtonEvent(
            session: MediaSession,
            controllerInfo: MediaSession.ControllerInfo,
            intent: Intent
        ): Boolean {
            val isOwnUi = controllerInfo.packageName == applicationContext.packageName
            if (isOwnUi) return false
            if (appInForeground) return false
            Log.i(TAG, "Suppressing media button from ${controllerInfo.packageName} (app not in foreground)")
            return true
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle
        ): ListenableFuture<SessionResult> {
            Log.d(TAG, "Custom command: ${customCommand.customAction}")

            return when (customCommand.customAction) {
                COMMAND_CONNECT -> {
                    val address = args.getString(ARG_SERVER_ADDRESS)
                    val path = args.getString(ARG_SERVER_PATH) ?: "/sendspin"
                    val serverId = args.getString(ARG_SERVER_ID)
                    if (address != null) {
                        // Set server info before connecting
                        setCurrentServer(serverId)
                        connectToServer(address, path)
                        Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                    } else {
                        Log.e(TAG, "CONNECT command missing server_address")
                        Futures.immediateFuture(SessionResult(SessionError.ERROR_BAD_VALUE))
                    }
                }

                COMMAND_DISCONNECT -> {
                    // The user is done with this server: stop trying for it,
                    // and forget it so a drop racing this cannot start again.
                    coordinator.cancelReconnect()
                    setCurrentServer(null)
                    disconnectFromServer()
                    Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }

                COMMAND_SET_VOLUME -> {
                    val volume = args.getFloat(ARG_VOLUME, -1f)
                    if (volume in 0f..1f) {
                        setVolume(volume)
                        // Also send volume command to server
                        sendSpinClient?.setVolume(volume.toDouble())
                        Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                    } else {
                        Log.e(TAG, "SET_VOLUME command has invalid volume: $volume")
                        Futures.immediateFuture(SessionResult(SessionError.ERROR_BAD_VALUE))
                    }
                }

                COMMAND_NEXT -> {
                    Log.d(TAG, "Next track command received")
                    sendSpinClient?.next()
                    Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }

                COMMAND_PREVIOUS -> {
                    Log.d(TAG, "Previous track command received")
                    sendSpinClient?.previous()
                    Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }

                COMMAND_SWITCH_GROUP -> {
                    Log.d(TAG, "Switch group command received")
                    sendSpinClient?.switchGroup()
                    Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }

                COMMAND_GET_STATS -> {
                    val statsBundle = getStats()
                    Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS, statsBundle))
                }

                COMMAND_ALLOW_PAIRING -> {
                    Log.d(TAG, "Allow pairing gesture confirmed")
                    // Cleared here, at the operator's tap, rather than waiting on a
                    // round trip through the flow: the gate is a local UI concern,
                    // and the button must disappear the moment it is pressed.
                    pairingGestureRequested = false
                    sendSpinClient?.confirmDynamicPairingGesture()
                    broadcastSessionExtras()
                    Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }

                else -> {
                    Log.w(TAG, "Unknown custom command: ${customCommand.customAction}")
                    super.onCustomCommand(session, controller, customCommand, args)
                }
            }
        }
    }

    /**
     * Collects current stats from SyncAudioPlayer and SendSpin.
     * Returns a Bundle containing all stats for Stats for Nerds display.
     */
    /** Translate a coordinator reconnect status into a handoff-episode event. */
    private fun recordHandoff(status: ReconnectStatus) {
        val transport = coordinator.networkState.value.transportType.name
        val methods = coordinator.sessionState.value.server?.configuredMethods?.map { it.name } ?: emptyList()
        val playing = syncAudioPlayer?.getStats()?.isPlaying ?: false
        val phase = when (status) {
            is ReconnectStatus.Attempting -> HandoffEpisodeRecorder.Phase.ATTEMPTING
            is ReconnectStatus.Succeeded -> HandoffEpisodeRecorder.Phase.SUCCEEDED
            ReconnectStatus.Idle -> HandoffEpisodeRecorder.Phase.IDLE
        }
        val method = (status as? ReconnectStatus.Attempting)?.method?.name
        handoffRecorder.onReconnect(phase, method, transport, methods, playing)
    }

    private fun getStats(): Bundle {
        val bundle = Bundle()

        // Get connection info from SendSpin
        sendSpinClient?.let { client ->
            bundle.putString("server_name", client.getServerName())
            bundle.putString("server_address", client.getServerAddress())
            // TransportState variants are plain objects; toString() would show
            // "TransportState$Ready@b7f7b20" on the stats screen.
            val stateLabel = when (client.connectionState.value) {
                is TransportState.Ready -> "Connected"
                is TransportState.Connecting -> "Connecting"
                is TransportState.Idle -> "Disconnected"
                is TransportState.Failed -> "Failed"
            }
            bundle.putString("connection_state", stateLabel)
            bundle.putString("audio_codec", currentCodec.uppercase())
        } ?: run {
            bundle.putString("connection_state", "Disconnected")
            bundle.putString("audio_codec", "--")
        }

        // Network-handoff episodes (drop + reconnect outcomes) for bug reports.
        bundle.putString("handoff_episodes", handoffRecorder.summary())

        // Get stats from SyncAudioPlayer
        val audioStats = syncAudioPlayer?.getStats()
        if (audioStats != null) {
            // Playback state
            bundle.putString("playback_state", audioStats.playbackState.name)
            bundle.putBoolean("is_playing", audioStats.isPlaying)

            // Sync error
            bundle.putLong("sync_error_us", audioStats.syncErrorUs)
            bundle.putLong("smoothed_sync_error_us", audioStats.smoothedSyncErrorUs)
            bundle.putDouble("sync_error_drift", audioStats.syncErrorDrift)
            bundle.putLong("grace_period_remaining_us", audioStats.gracePeriodRemainingUs)

            // DAC/Audio
            bundle.putBoolean("start_time_calibrated", audioStats.startTimeCalibrated)
            bundle.putInt("dac_calibration_count", audioStats.dacCalibrationCount)
            bundle.putBoolean("dac_timestamps_stable", audioStats.dacTimestampsStable)
            bundle.putLong("samples_read_since_start", audioStats.samplesReadSinceStart)
            bundle.putLong("total_frames_written", audioStats.totalFramesWritten)
            bundle.putLong("buffer_underrun_count", audioStats.bufferUnderrunCount)

            // Buffer
            bundle.putLong("queued_samples", audioStats.queuedSamples)
            bundle.putLong("chunks_received", audioStats.chunksReceived)
            bundle.putLong("chunks_played", audioStats.chunksPlayed)
            bundle.putLong("chunks_dropped", audioStats.chunksDropped)
            bundle.putLong("gaps_filled", audioStats.gapsFilled)
            bundle.putLong("gap_silence_ms", audioStats.gapSilenceMs)
            bundle.putLong("overlaps_trimmed", audioStats.overlapsTrimmed)
            bundle.putLong("overlap_trimmed_ms", audioStats.overlapTrimmedMs)

            // Sync correction
            bundle.putInt("insert_every_n_frames", audioStats.insertEveryNFrames)
            bundle.putInt("drop_every_n_frames", audioStats.dropEveryNFrames)
            bundle.putLong("frames_inserted", audioStats.framesInserted)
            bundle.putLong("frames_dropped", audioStats.framesDropped)
            bundle.putLong("sync_corrections", audioStats.syncCorrections)
            bundle.putLong("reanchor_count", audioStats.reanchorCount)

            // Playback tracking
            bundle.putLong("server_timeline_cursor_us", audioStats.serverTimelineCursorUs)

            // Timing
            audioStats.scheduledStartLoopTimeUs?.let {
                bundle.putLong("scheduled_start_loop_time_us", it)
            }
            audioStats.firstServerTimestampUs?.let {
                bundle.putLong("first_server_timestamp_us", it)
            }
        } else {
            // No audio player - provide default values
            bundle.putString("playback_state", "NO_AUDIO")
            bundle.putBoolean("is_playing", false)
        }

        // Get stats from SendSpin (clock sync)
        sendSpinClient?.let { client ->
            val timeFilter = client.getTimeFilter()
            bundle.putBoolean("clock_ready", timeFilter.isReady)
            bundle.putBoolean("clock_converged", timeFilter.isConverged)
            bundle.putLong("clock_offset_us", timeFilter.offsetMicros)
            bundle.putDouble("clock_drift_ppm", timeFilter.driftPpm)
            bundle.putLong("clock_error_us", timeFilter.errorMicros)
            bundle.putInt("measurement_count", timeFilter.measurementCountValue)
            bundle.putLong("last_time_sync_age_ms", client.getLastTimeSyncAgeMs())
            bundle.putDouble("static_delay_ms", timeFilter.staticDelayMs)

            // Connection health telemetry (issue #128). Keys left absent when
            // the underlying value is null so StatsViewModel can distinguish
            // "no disconnect yet" from "last disconnect was code=0".
            bundle.putLong("last_byte_received_ago_ms", client.getLastByteReceivedAgoMs())
            bundle.putBoolean("stall_watchdog_armed", client.isStallWatchdogArmed())
            client.getLastDisconnectCode()?.let { bundle.putInt("last_disconnect_code", it) }
            client.getLastDisconnectReason()?.let { bundle.putString("last_disconnect_reason", it) }
            bundle.putLong("time_filter_convergence_ms", timeFilter.convergenceTimeMillis)
        }

        // Refresh network state before reading it: no NetworkEvaluator.Listener is
        // registered, so without this the diagnostics bundle would stay frozen at
        // whatever the network looked like at service start instead of reflecting
        // the current network (e.g. a Wi-Fi to cellular handover).
        networkEvaluator?.evaluateCurrentNetwork()

        // Get network stats from NetworkEvaluator
        networkEvaluator?.networkState?.value?.let { netState ->
            bundle.putString("network_type", netState.transportType.name)
            bundle.putString("network_quality", netState.quality.name)
            bundle.putBoolean("network_metered", netState.isMetered)
            bundle.putBoolean("network_connected", netState.isConnected)
            netState.wifiRssi?.let { bundle.putInt("wifi_rssi", it) }
            netState.wifiLinkSpeedMbps?.let { bundle.putInt("wifi_link_speed", it) }
            netState.wifiFrequencyMhz?.let { bundle.putInt("wifi_frequency", it) }
            netState.cellularType?.let { bundle.putString("cellular_type", it.name) }
            netState.downstreamBandwidthKbps?.let { bundle.putInt("bandwidth_down_kbps", it) }
        }

        return bundle
    }

    /**
     * Applies the manual sync offset from UserSettings to the TimeFilter.
     * Called when connecting to a server to apply the saved offset.
     */
    private fun applySyncOffsetFromSettings() {
        val offsetMs = com.sendspindroid.UserSettings.getSyncOffsetMs()
        if (offsetMs != 0) {
            sendSpinClient?.getTimeFilter()?.let { timeFilter ->
                timeFilter.setUserSyncOffsetMs(offsetMs.toDouble())
                Log.i(TAG, "Applied manual sync offset from settings: ${offsetMs}ms")
            }
        }
    }

    /**
     * Updates the sync offset and applies it immediately if connected.
     * Called when the user changes the offset in settings.
     */
    fun updateSyncOffset(offsetMs: Int) {
        com.sendspindroid.UserSettings.setSyncOffsetMs(offsetMs)
        sendSpinClient?.getTimeFilter()?.let { timeFilter ->
            timeFilter.setUserSyncOffsetMs(offsetMs.toDouble())
            Log.i(TAG, "Updated sync offset to: ${offsetMs}ms")
        }
    }

    private fun getRootChildren(): List<MediaItem> {
        return AutoBrowseTree.rootChildren(isConnected())
    }

    private suspend fun getDiscoveredServers(): List<MediaItem> {
        // Trigger mDNS scan so servers populate for Android Auto / external browsers
        ensureBrowseDiscoveryRunning()

        // AutoBrowseTree waits briefly for the first mDNS result when nothing
        // is known yet, and guarantees a non-empty list (guidance row when no
        // servers are available) so Android Auto never shows a blank screen.
        return AutoBrowseTree.serverListChildren(
            savedServers = UnifiedServerRepository.savedServers.value,
            discoveredServersFlow = UnifiedServerRepository.filteredDiscoveredServers,
            discoveryWaitMs = BROWSE_DISCOVERY_WAIT_MS,
        )
    }

    /**
     * Starts mDNS discovery if not already running.
     * Used when an external client (Android Auto) browses the server list,
     * since the main Activity may not be open to trigger discovery.
     */
    private fun ensureBrowseDiscoveryRunning() {
        if (browseDiscoveryManager != null) return  // Already initialized

        Log.i(TAG, "Starting mDNS discovery for browse tree")
        browseDiscoveryManager = NsdDiscoveryManager(this, object : NsdDiscoveryManager.DiscoveryListener {
            override fun onServerDiscovered(name: String, address: String, path: String, friendlyName: String) {
                Log.d(TAG, "Browse discovery: found $name at $address (path=$path friendlyName=$friendlyName)")
                UnifiedServerRepository.addDiscoveredServer(friendlyName, address, path)
                // Notify subscribed browsers that children changed
                mediaSession?.notifyChildrenChanged(MEDIA_ID_DISCOVERED, 0, null)
            }

            override fun onServerLost(name: String) {
                Log.d(TAG, "Browse discovery: lost $name")
                // Find the server by name to get its address for removal
                val server = UnifiedServerRepository.discoveredServers.value.find { it.name == name }
                server?.local?.address?.let { address ->
                    UnifiedServerRepository.removeDiscoveredServer(address)
                    mediaSession?.notifyChildrenChanged(MEDIA_ID_DISCOVERED, 0, null)
                }
            }

            override fun onDiscoveryStarted() {
                Log.d(TAG, "Browse discovery started")
            }

            override fun onDiscoveryStopped() {
                Log.d(TAG, "Browse discovery stopped")
            }

            override fun onDiscoveryError(error: String) {
                Log.e(TAG, "Browse discovery error: $error")
            }
        })
        browseDiscoveryManager?.startDiscovery()
    }

    private fun findItemById(mediaId: String): MediaItem? {
        return when {
            mediaId == MEDIA_ID_ROOT -> {
                MediaItem.Builder()
                    .setMediaId(MEDIA_ID_ROOT)
                    .setMediaMetadata(
                        MediaMetadata.Builder()
                            .setTitle("SendSpinDroid")
                            .setIsPlayable(false)
                            .setIsBrowsable(true)
                            .build()
                    )
                    .build()
            }
            mediaId == MEDIA_ID_DISCOVERED -> {
                AutoBrowseTree.browsableItem(MEDIA_ID_DISCOVERED, "Connect", "Choose a server")
            }
            mediaId.startsWith(MEDIA_ID_SAVED_SERVER_PREFIX) -> {
                val serverId = mediaId.removePrefix(MEDIA_ID_SAVED_SERVER_PREFIX)
                UnifiedServerRepository.savedServers.value
                    .find { it.id == serverId }
                    ?.let { AutoBrowseTree.savedServerItem(it) }
            }
            mediaId.startsWith(MEDIA_ID_SERVER_PREFIX) -> {
                val address = mediaId.removePrefix(MEDIA_ID_SERVER_PREFIX)
                val server = UnifiedServerRepository.getServerByAddress(address)
                server?.let { AutoBrowseTree.playableServerItem(it.name, it.local?.address ?: address) }
            }
            else -> null
        }
    }

    @OptIn(UnstableApi::class)
    private fun initializePlayer() {
        sendSpinPlayer = SendSpinPlayer()
        Log.d(TAG, "SendSpinPlayer initialized")
    }

    override fun onGetSession(
        controllerInfo: MediaSession.ControllerInfo
    ): MediaLibrarySession? {
        Log.i(TAG, "onGetSession called by: ${controllerInfo.packageName}" +
                " (uid=${controllerInfo.uid})" +
                ", mediaSession=${mediaSession != null}" +
                ", player=${sendSpinPlayer != null}" +
                ", forwardingPlayer=${forwardingPlayer != null}" +
                ", isDestroyed=$isDestroyed")

        // Defensive: ensure MediaSession exists for external callers like Android Auto
        if (mediaSession == null) {
            Log.w(TAG, "MediaSession is null when onGetSession called - attempting recovery")
            if (sendSpinPlayer == null) {
                Log.d(TAG, "Creating SendSpinPlayer for external caller")
                initializePlayer()
            }
            if (sendSpinPlayer != null) {
                Log.d(TAG, "Creating MediaSession for external caller")
                initializeMediaSession()
            }
            Log.i(TAG, "Recovery result: mediaSession=${mediaSession != null}")
        }

        if (mediaSession == null) {
            Log.e(TAG, "Failed to create MediaSession for ${controllerInfo.packageName}")
        }

        return mediaSession
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand: action=${intent?.action}, flags=$flags")
        super.onStartCommand(intent, flags, startId)

        if (intent?.action == ACTION_AUTO_CONNECT) {
            // Must call startForeground within 5s when launched via
            // startForegroundService() (Android 8+) -- and unconditionally here,
            // BEFORE handleAutoConnect, because that method returns early on a
            // missing/unknown server without ever promoting us.
            //
            // Gated on ACTION_AUTO_CONNECT: that is the only action any
            // startForegroundService()/getForegroundService() path uses (see
            // BootReceiver, both the direct start and the tap-to-resume
            // PendingIntent), so it covers every case that owes Android a
            // startForeground. Promoting unconditionally instead meant any
            // media-button or Media3-originated start posted a permanent
            // setOngoing(true) notification -- under an id Media3 does not own,
            // so nothing would ever clear it while disconnected -- and on API
            // 31+ a background-originated start threw
            // ForegroundServiceStartNotAllowedException into the catch below.
            startForegroundServiceWithNotification()
            handleAutoConnect(intent)
        }

        return START_STICKY
    }

    /**
     * Handle auto-connect from BootReceiver.
     * Looks up the default server and connects using its preferred connection method.
     * Shows a foreground notification immediately to satisfy Android's requirements.
     */
    private fun handleAutoConnect(intent: Intent) {
        val serverId = intent.getStringExtra(EXTRA_SERVER_ID)
        if (serverId == null) {
            Log.w(TAG, "Auto-connect: missing server ID")
            return
        }

        val server = UnifiedServerRepository.getServer(serverId)
        if (server == null) {
            Log.w(TAG, "Auto-connect: server $serverId not found")
            return
        }

        Log.i(TAG, "Auto-connect on boot: ${server.name} (${server.id})")

        // The server this service is connected to, which a later drop
        // reconnects to.
        setCurrentServer(server.id)

        // Show foreground notification immediately (Android requires this within 10s)
        startForegroundServiceWithNotification(server.name)

        // Connect using the server's local address (the only connection method left).
        when {
            server.local != null -> {
                // Re-resolve via mDNS first: a stored static IP can go stale
                // (DHCP) and cause a refused connect on boot. See #158.
                autoConnectLocalWithMdns(server)
            }
            else -> {
                Log.w(TAG, "Auto-connect: server ${server.name} has no configured connection methods")
            }
        }
    }

    /**
     * Auto-connect to a saved local server, re-resolving its current address via
     * mDNS first so a stale stored address (DHCP change) doesn't cause a failed
     * connect on boot. Falls back to the stored address if mDNS doesn't find the
     * server within [MDNS_AUTOCONNECT_TIMEOUT_MS] (server offline, or the network
     * not yet up on boot). See #158.
     */
    private fun autoConnectLocalWithMdns(server: UnifiedServer) {
        val local = server.local ?: return
        serviceScope.launch {
            val resolved = resolveLocalAddressViaMdns(server.name, MDNS_AUTOCONNECT_TIMEOUT_MS)
            val address = resolved ?: local.address
            when {
                resolved == null ->
                    // warn: on boot this correlates with a likely-failing connect
                    // when the stored address has gone stale (the #158 scenario).
                    Log.w(TAG, "Auto-connect: mDNS did not find '${server.name}'; using stored ${local.address}")
                resolved != local.address ->
                    Log.i(TAG, "Auto-connect: mDNS resolved '${server.name}' to $resolved (stored ${local.address})")
                else ->
                    Log.i(TAG, "Auto-connect: mDNS confirmed '${server.name}' at $resolved")
            }
            connectToServer(address, local.path)
        }
    }

    /**
     * Run a short, bounded mDNS discovery and return the current address of the
     * discovered server whose friendly name (or, as a fallback, raw mDNS service
     * name) equals [serverName], or null on timeout. Every result is fed to
     * [UnifiedServerRepository.addDiscoveredServer], which also refreshes the
     * matching saved server's stored address (by friendly name).
     */
    private suspend fun resolveLocalAddressViaMdns(serverName: String, timeoutMs: Long): String? {
        val result = CompletableDeferred<String?>()
        val manager = NsdDiscoveryManager(this, object : NsdDiscoveryManager.DiscoveryListener {
            override fun onServerDiscovered(name: String, address: String, path: String, friendlyName: String) {
                UnifiedServerRepository.addDiscoveredServer(friendlyName, address, path)
                if (!result.isCompleted && (friendlyName == serverName || name == serverName)) {
                    result.complete(address)
                }
            }
            override fun onServerLost(name: String) {}
            override fun onDiscoveryStarted() {}
            override fun onDiscoveryStopped() {}
            override fun onDiscoveryError(error: String) {
                // Complete so we fall back to the stored address immediately
                // instead of stalling for the full timeout.
                Log.w(TAG, "Auto-connect: mDNS discovery error for '$serverName': $error")
                if (!result.isCompleted) result.complete(null)
            }
        })
        return try {
            manager.startDiscovery()
            withTimeoutOrNull(timeoutMs) { result.await() }
        } finally {
            // cleanup() (not stopDiscovery()) so the multicast lock is released
            // even if onDiscoveryStarted never fired.
            manager.cleanup()
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Check if SyncAudioPlayer is actively playing
        val audioPlayerState = syncAudioPlayer?.getPlaybackState()
        val isPlaying = audioPlayerState == com.sendspindroid.sendspin.PlaybackState.PLAYING ||
                        audioPlayerState == com.sendspindroid.sendspin.PlaybackState.WAITING_FOR_START

        // Keep alive if auto-start is enabled and we're connected (even if
        // idle) or working on getting the connection back
        val autoStartKeepAlive = UserSettings.autoStartOnBoot && (
            coordinator.sessionState.value.sendSpin is TransportState.Ready ||
                coordinator.reconnectStatus.value is ReconnectStatus.Attempting
            )

        Log.d(TAG, "onTaskRemoved (playing=$isPlaying, autoStart=$autoStartKeepAlive, state=$audioPlayerState)")

        if (!isPlaying && !autoStartKeepAlive) {
            Log.d(TAG, "Not playing and no auto-start keep-alive, stopping service")
            stopSelf()
        } else {
            Log.d(TAG, "Continuing in background (playing=$isPlaying, autoStart=$autoStartKeepAlive)")
        }

        super.onTaskRemoved(rootIntent)
    }

    @OptIn(UnstableApi::class)
    override fun onDestroy() {
        Log.d(TAG, "PlaybackService destroyed")

        // Set destroyed flag first to prevent any pending callbacks from executing
        isDestroyed = true

        // Detach the process-lifecycle observer before any other cleanup.
        // ProcessLifecycleOwner is a process-singleton and would otherwise
        // retain this anonymous observer (and the service instance it
        // captures) across service re-creates.
        appLifecycleObserver?.let {
            androidx.lifecycle.ProcessLifecycleOwner.get().lifecycle.removeObserver(it)
        }
        appLifecycleObserver = null

        // Remove all pending callbacks from all handlers
        mainHandler.removeCallbacksAndMessages(null)
        wakeLockHandler.removeCallbacks(wakeLockRefreshRunnable)
        wakeLockHandler.removeCallbacks(highPowerWakeLockRefreshRunnable)
        debugLogHandler.removeCallbacks(debugLogRunnable)

        // Stop debug logging (also removes callbacks, but flag is set above)
        stopDebugLogging()

        // Unregister sync offset receiver
        LocalBroadcastManager.getInstance(this).unregisterReceiver(syncOffsetReceiver)

        // Unregister log level receiver
        LocalBroadcastManager.getInstance(this).unregisterReceiver(logLevelReceiver)

        // Unregister High Power Mode receiver and release locks
        LocalBroadcastManager.getInstance(this).unregisterReceiver(highPowerModeReceiver)
        LocalBroadcastManager.getInstance(this).unregisterReceiver(preferredCodecReceiver)
        releaseHighPowerLocks()

        // Let the final releasePlaybackLocks() abandon focus even mid-interruption
        transientFocusLoss = null

        // Unregister the becoming-noisy receiver (system broadcast)
        becomingNoisyReceiver?.let {
            runCatching { unregisterReceiver(it) }
            becomingNoisyReceiver = null
        }

        // Unregister volume observer (only if it was registered)
        if (volumeObserverRegistered) {
            volumeObserver?.let { contentResolver.unregisterContentObserver(it) }
            volumeObserverRegistered = false
        }
        volumeObserver = null

        // Stop browse discovery if running
        browseDiscoveryManager?.cleanup()
        browseDiscoveryManager = null
        stopReconnectDiscovery()

        // Send a final Release through the channel so it runs after any
        // pending decode tasks, then close the channel and wait up to 500 ms
        // for the worker to drain and exit. trySend (not send) because
        // onDestroy is non-suspending -- if the channel is somehow full we
        // don't want to block teardown waiting for the worker to make
        // progress; handleDecodeRelease still runs if trySend succeeds, or
        // the worker will release the decoder as it exits when the channel
        // is closed.
        decodeChannel.trySend(DecodeTask.Release)
        decodeChannel.close()
        runBlocking {
            withTimeoutOrNull(500) { decodeJob?.join() }
        }

        if (::coordinator.isInitialized) coordinator.close()
        serviceScope.cancel()
        imageLoader?.shutdown()

        // audioDecoder is released by handleDecodeRelease on the decode
        // worker (invariant: only that coroutine mutates the field).
        // Cancel playback locks and foreground notification.
        syncAudioPlayer?.release()
        syncAudioPlayer = null
        releasePlaybackLocks()
        stopForegroundNotification()

        mediaSession?.run {
            release()
        }
        mediaSession = null

        forwardingPlayer = null

        sendSpinPlayer?.release()
        sendSpinPlayer = null

        sendSpinClient?.destroy()
        sendSpinClient = null

        super.onDestroy()
    }
}

