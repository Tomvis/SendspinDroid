package com.sendspindroid.ui.stats

import android.app.Application
import android.content.ComponentName
import android.os.Bundle
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.sendspindroid.playback.PlaybackService
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * ViewModel for Stats Bottom Sheet.
 * Handles MediaController connection and periodic stats updates.
 */
class StatsViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "StatsViewModel"
        private const val UPDATE_INTERVAL_MS = 500L  // 2 Hz
    }

    private val _statsState = MutableStateFlow(StatsState())
    val statsState: StateFlow<StatsState> = _statsState.asStateFlow()

    private var mediaControllerFuture: ListenableFuture<MediaController>? = null
    private var mediaController: MediaController? = null
    private var isPolling = false

    init {
        initializeMediaController()
    }

    private fun initializeMediaController() {
        val context = getApplication<Application>()
        val sessionToken = SessionToken(
            context,
            ComponentName(context, PlaybackService::class.java)
        )

        mediaControllerFuture = MediaController.Builder(context, sessionToken)
            .buildAsync()

        // Use mainExecutor to ensure callback runs on the main thread,
        // since it writes non-thread-safe fields (mediaController, isPolling)
        mediaControllerFuture?.addListener(
            {
                try {
                    mediaController = mediaControllerFuture?.get()
                    Log.d(TAG, "MediaController connected")
                    startPolling()
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to connect MediaController", e)
                }
            },
            context.mainExecutor
        )
    }

    private fun startPolling() {
        if (isPolling) return
        isPolling = true

        viewModelScope.launch {
            while (isActive && isPolling) {
                requestStats()
                delay(UPDATE_INTERVAL_MS)
            }
        }
    }

    fun stopPolling() {
        isPolling = false
    }

    private fun requestStats() {
        val controller = mediaController ?: return

        try {
            val command = SessionCommand(PlaybackService.COMMAND_GET_STATS, Bundle.EMPTY)
            val result = controller.sendCustomCommand(command, Bundle.EMPTY)

            // Use mainExecutor to ensure stats update runs on the main thread,
            // keeping all state writes on a single thread
            result.addListener(
                {
                    try {
                        val bundle = result.get().extras
                        updateStats(bundle)
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to get stats", e)
                    }
                },
                getApplication<Application>().mainExecutor
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to request stats", e)
        }
    }

    private fun updateStats(bundle: Bundle) {
        _statsState.value = StatsState(
            // Protocol
            serverName = bundle.getString("server_name", null),
            serverAddress = bundle.getString("server_address", null),
            connectionState = bundle.getString("connection_state", "Unknown"),
            serverInitiated = bundle.getBoolean("server_initiated", false),
            audioCodec = bundle.getString("audio_codec", "--"),
            streamSampleRate = bundle.getInt("stream_sample_rate", 0),
            streamBitDepth = bundle.getInt("stream_bit_depth", 0),
            streamChannels = bundle.getInt("stream_channels", 0),
            activeRoles = bundle.getString("active_roles", "").split(',').filter { it.isNotEmpty() },
            pskCategory = bundle.getString("psk_category", null),
            minBufferMs = bundle.getInt("min_buffer_ms", 0),
            requiredLeadTimeMs = bundle.getInt("required_lead_time_ms", 0),
            lastByteReceivedAgoMs = bundle.getLong("last_byte_received_ago_ms", -1L),
            lastDisconnectCode = if (bundle.containsKey("last_disconnect_code")) bundle.getInt("last_disconnect_code") else null,
            lastDisconnectReason = bundle.getString("last_disconnect_reason", null),

            // Network
            networkType = bundle.getString("network_type", "UNKNOWN"),
            networkQuality = bundle.getString("network_quality", "UNKNOWN"),
            wifiRssi = bundle.getInt("wifi_rssi", Int.MIN_VALUE),
            wifiSpeed = bundle.getInt("wifi_link_speed", -1),
            wifiFrequency = bundle.getInt("wifi_frequency", -1),

            // Sync
            playbackState = bundle.getString("playback_state", "UNKNOWN"),
            syncErrorUs = bundle.getLong("sync_error_us", 0L),
            smoothedSyncErrorUs = bundle.getLong("smoothed_sync_error_us", 0L),
            gracePeriodRemainingUs = bundle.getLong("grace_period_remaining_us", -1L),
            startTimeCalibrated = bundle.getBoolean("start_time_calibrated", false),
            staticDelayMs = bundle.getDouble("static_delay_ms", 0.0),

            // Clock
            clockDriftPpm = bundle.getDouble("clock_drift_ppm", 0.0),
            clockErrorUs = bundle.getLong("clock_error_us", 0L),
            clockConverged = bundle.getBoolean("clock_converged", false),
            measurementCount = bundle.getInt("measurement_count", 0),
            lastTimeSyncAgeMs = bundle.getLong("last_time_sync_age_ms", -1L),
            timeFilterConvergenceMs = bundle.getLong("time_filter_convergence_ms", 0L),

            // Correction
            insertEveryNFrames = bundle.getInt("insert_every_n_frames", 0),
            dropEveryNFrames = bundle.getInt("drop_every_n_frames", 0),
            framesInserted = bundle.getLong("frames_inserted", 0L),
            framesDropped = bundle.getLong("frames_dropped", 0L),
            reanchorCount = bundle.getLong("reanchor_count", 0L),
            bufferUnderrunCount = bundle.getLong("buffer_underrun_count", 0L),

            // Buffer
            queuedSamples = bundle.getLong("queued_samples", 0L),
            chunksReceived = bundle.getLong("chunks_received", 0L),
            chunksPlayed = bundle.getLong("chunks_played", 0L),
            chunksDropped = bundle.getLong("chunks_dropped", 0L),
            gapsFilled = bundle.getLong("gaps_filled", 0L),
            gapSilenceMs = bundle.getLong("gap_silence_ms", 0L),
            overlapsTrimmed = bundle.getLong("overlaps_trimmed", 0L),
            overlapTrimmedMs = bundle.getLong("overlap_trimmed_ms", 0L),

            handoffEpisodes = bundle.getString("handoff_episodes", null),
        )
    }

    override fun onCleared() {
        super.onCleared()
        stopPolling()
        mediaControllerFuture?.let {
            MediaController.releaseFuture(it)
        }
        mediaController = null
        mediaControllerFuture = null
    }
}

/**
 * Complete stats state for the Stats Bottom Sheet.
 */
data class StatsState(
    // Protocol
    val serverName: String? = null,
    val serverAddress: String? = null,
    val connectionState: String = "Unknown",
    /** Whether the server opened the connection, or this device dialled it. */
    val serverInitiated: Boolean = false,
    val audioCodec: String = "--",
    val streamSampleRate: Int = 0,
    val streamBitDepth: Int = 0,
    val streamChannels: Int = 0,
    val activeRoles: List<String> = emptyList(),
    /** Name of the PskCategory that admitted the connection, or null. */
    val pskCategory: String? = null,
    val minBufferMs: Int = 0,
    val requiredLeadTimeMs: Int = 0,
    val lastByteReceivedAgoMs: Long = -1L,
    val lastDisconnectCode: Int? = null,
    val lastDisconnectReason: String? = null,

    // Network
    val networkType: String = "UNKNOWN",
    val networkQuality: String = "UNKNOWN",
    val wifiRssi: Int = Int.MIN_VALUE,
    val wifiSpeed: Int = -1,
    val wifiFrequency: Int = -1,

    // Sync
    val playbackState: String = "UNKNOWN",
    val syncErrorUs: Long = 0L,
    val smoothedSyncErrorUs: Long = 0L,
    val gracePeriodRemainingUs: Long = -1L,
    val startTimeCalibrated: Boolean = false,
    val staticDelayMs: Double = 0.0,

    // Clock
    val clockDriftPpm: Double = 0.0,
    val clockErrorUs: Long = 0L,
    val clockConverged: Boolean = false,
    val measurementCount: Int = 0,
    val lastTimeSyncAgeMs: Long = -1L,
    val timeFilterConvergenceMs: Long = 0L,

    // Correction
    val insertEveryNFrames: Int = 0,
    val dropEveryNFrames: Int = 0,
    val framesInserted: Long = 0L,
    val framesDropped: Long = 0L,
    val reanchorCount: Long = 0L,
    val bufferUnderrunCount: Long = 0L,

    // Buffer
    val queuedSamples: Long = 0L,
    val chunksReceived: Long = 0L,
    val chunksPlayed: Long = 0L,
    val chunksDropped: Long = 0L,
    val gapsFilled: Long = 0L,
    val gapSilenceMs: Long = 0L,
    val overlapsTrimmed: Long = 0L,
    val overlapTrimmedMs: Long = 0L,

    // Newline-delimited reconnect-episode summary from the recorder.
    val handoffEpisodes: String? = null,
) {
    val syncErrorMs: Double get() = syncErrorUs / 1000.0
    val smoothedSyncErrorMs: Double get() = smoothedSyncErrorUs / 1000.0
    val clockErrorMs: Double get() = clockErrorUs / 1000.0

    /** The stream's rate once one has started; the 48 kHz every stream has used so far before that. */
    private val sampleRate: Int get() = if (streamSampleRate > 0) streamSampleRate else 48_000

    val queuedMs: Long get() = queuedSamples * 1000 / sampleRate

    /** How much audio a count of frames is, in milliseconds. */
    fun framesToMs(frames: Long): Double = frames * 1000.0 / sampleRate

    /** What the clocks drifting at this rate adds up to over an hour. */
    val clockDriftMsPerHour: Double get() = clockDriftPpm * 3.6

    val isWifi: Boolean get() = networkType == "WIFI"

    val isPlaying: Boolean get() = playbackState == "PLAYING"

    val wifiBand: String get() = when {
        wifiFrequency >= 5000 -> "5 GHz"
        wifiFrequency > 0 -> "2.4 GHz"
        else -> "--"
    }
}

/**
 * Status indicator for color coding.
 */
enum class ThresholdStatus { GOOD, WARNING, BAD }

// The player leaves the error alone inside +/-0.1 ms, nudges it back outside
// that, and resyncs in one step past 1 ms. A smoothed error under 1 ms is the
// player working as designed; past it something has knocked it out of sync.
const val SYNC_DEADBAND_US = 100L
const val SYNC_RESYNC_US = 1_000L

fun getSyncErrorStatus(errorUs: Long): ThresholdStatus {
    val absError = abs(errorUs)
    return when {
        absError < SYNC_RESYNC_US -> ThresholdStatus.GOOD
        absError < 5 * SYNC_RESYNC_US -> ThresholdStatus.WARNING
        else -> ThresholdStatus.BAD
    }
}

fun getClockErrorStatus(errorUs: Long): ThresholdStatus {
    val absError = abs(errorUs)
    return when {
        absError < 1_000L -> ThresholdStatus.GOOD
        absError < 5_000L -> ThresholdStatus.WARNING
        else -> ThresholdStatus.BAD
    }
}

fun getBufferStatus(ms: Long): ThresholdStatus {
    return when {
        ms < 50 -> ThresholdStatus.BAD
        ms < 200 -> ThresholdStatus.WARNING
        else -> ThresholdStatus.GOOD
    }
}

fun getConnectionStatus(state: String): ThresholdStatus {
    return when {
        state.contains("Connected", ignoreCase = true) -> ThresholdStatus.GOOD
        state.contains("Connecting", ignoreCase = true) -> ThresholdStatus.WARNING
        else -> ThresholdStatus.BAD
    }
}
