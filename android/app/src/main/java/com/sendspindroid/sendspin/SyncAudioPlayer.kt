package com.sendspindroid.sendspin

import android.media.AudioAttributes
import android.media.AudioFormat
import android.os.Build
import android.media.AudioTrack
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import com.sendspindroid.logging.AppLog
import com.sendspindroid.logging.throwableSummary
import com.sendspindroid.sendspin.audio.AudioSink
import com.sendspindroid.sendspin.audio.AudioTrackSink
import com.sendspindroid.sendspin.protocol.SendSpinProtocol
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.android.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.abs

/**
 * Playback state machine for synchronized audio.
 *
 * Follows the Python reference implementation pattern for start gating and reanchoring.
 * This state machine ensures synchronized playback by controlling when audio starts
 * and handling sync errors gracefully.
 *
 * ## State Diagram
 * ```
 *                      +--------------+
 *          +---------->| INITIALIZING |<------------------------------+
 *          |           +------+-------+                               |
 *          |                  | first chunk received                  |
 *          |                  | (queueChunk)                          |
 *          |                  v                                       |
 *          |      +-----------------------+                           |
 *          |      |  WAITING_FOR_START    |<------+                   |
 *          |      |  (buffer filling)     |       |                   |
 *          |      +-----------+-----------+       |                   |
 *          |                  | buffer >= 200ms   |                   |
 *          |                  | AND scheduled     | reanchor chunk    |
 *          |                  | start time        | received          |
 *          |                  | reached           |                   |
 *          |                  v                   |                   |
 *          |           +--------------+     +-----+------+            |
 *          |           |   PLAYING    |---->| REANCHORING|------------+
 *          |           |              |     +------------+
 *          |           +--------------+      large sync error
 *          |                                 (> 500ms)
 *          |
 *          +--- stop() / clearBuffer() from any state
 * ```
 *
 * ## State Transition Table
 * ```
 * ┌─────────────────────┬─────────────────────┬─────────────────────────────────────────────────┐
 * │ From State          │ To State            │ Trigger / Condition                             │
 * ├─────────────────────┼─────────────────────┼─────────────────────────────────────────────────┤
 * │ INITIALIZING        │ WAITING_FOR_START   │ First audio chunk received in queueChunk()     │
 * │ WAITING_FOR_START   │ PLAYING             │ Buffer >= 200ms AND scheduled start time       │
 * │                     │                     │ reached (handleStartGating)                    │
 * │ PLAYING             │ REANCHORING         │ Sync error > 500ms (triggerReanchor)           │
 * │ REANCHORING         │ INITIALIZING        │ After clearing buffers (triggerReanchor)       │
 * │ REANCHORING         │ WAITING_FOR_START   │ New chunk received during reanchor             │
 * │ Any State           │ INITIALIZING        │ stop() or clearBuffer() called                 │
 * └─────────────────────┴─────────────────────┴─────────────────────────────────────────────────┘
 * ```
 *
 * ## State Descriptions
 *
 * ### INITIALIZING
 * Initial state. Waiting for the first audio chunk and time synchronization.
 * No audio output occurs. Transitions to WAITING_FOR_START when first chunk arrives.
 *
 * ### WAITING_FOR_START
 * Buffer is being filled with audio chunks. A scheduled start time has been computed
 * based on the first chunk's server timestamp. Waits until:
 * - Buffer has at least 200ms of audio (MIN_BUFFER_BEFORE_START_MS)
 * - Scheduled start time is reached or passed
 * During this state, the scheduled start time is continuously updated as time sync improves.
 *
 * ### PLAYING
 * Active synchronized playback with sample insert/drop corrections.
 * Audio is written to AudioTrack with:
 * - Sync error monitoring (Kalman filtered)
 * - Sample insertion (slow down) or dropping (speed up) to maintain sync
 * - 500ms startup grace period before corrections begin
 *
 * ### REANCHORING
 * Transient state triggered by large sync error (> 500ms).
 * Clears all buffers and resets timing state to recover from severe desync.
 * Has a 5-second cooldown to prevent thrashing. Transitions to INITIALIZING
 * immediately, then to WAITING_FOR_START when new chunk arrives.
 */
/**
 * Default production [AudioSink] factory for [SyncAudioPlayer].
 *
 * Builds an [AudioTrack] with the same configuration that SyncAudioPlayer previously
 * constructed inline (USAGE_MEDIA / CONTENT_TYPE_MUSIC, MODE_STREAM,
 * PERFORMANCE_MODE_LOW_LATENCY) and wraps it in an [AudioTrackSink]. The
 * [bufferSize] is precomputed by the caller; this factory does not query
 * [AudioTrack.getMinBufferSize].
 *
 * Tests inject a FakeAudioSink instead via SyncAudioPlayer's `sinkFactory`
 * constructor parameter, allowing the player to run off-device without a real
 * AudioTrack.
 */
private fun defaultSinkFactory(
    sampleRate: Int,
    channels: Int,
    bitDepth: Int,
    bufferSize: Int,
): AudioSink {
    val channelConfig = when (channels) {
        1 -> AudioFormat.CHANNEL_OUT_MONO
        2 -> AudioFormat.CHANNEL_OUT_STEREO
        else -> throw IllegalArgumentException("Unsupported channel count: $channels")
    }
    val encoding = when (bitDepth) {
        16 -> AudioFormat.ENCODING_PCM_16BIT
        24 -> if (Build.VERSION.SDK_INT >= 31) {
            AudioFormat.ENCODING_PCM_24BIT_PACKED
        } else {
            throw IllegalStateException("24-bit PCM requires API 31+, device is API ${Build.VERSION.SDK_INT}")
        }
        32 -> if (Build.VERSION.SDK_INT >= 31) {
            AudioFormat.ENCODING_PCM_32BIT
        } else {
            throw IllegalStateException("32-bit PCM requires API 31+, device is API ${Build.VERSION.SDK_INT}")
        }
        else -> throw IllegalArgumentException("Unsupported bit depth: $bitDepth")
    }
    val bytesPerFrame = channels * (bitDepth / 8)
    val track = AudioTrack.Builder()
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
        )
        .setAudioFormat(
            AudioFormat.Builder()
                .setSampleRate(sampleRate)
                .setChannelMask(channelConfig)
                .setEncoding(encoding)
                .build()
        )
        .setBufferSizeInBytes(bufferSize)
        .setTransferMode(AudioTrack.MODE_STREAM)
        .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
        .build()
    // build() does not always throw on a quirky HAL (e.g. HDMI-audio route mid-
    // renegotiation): it can hand back an uninitialized, silent track. Reject it
    // here so the caller's existing try/catch nulls the sink and retries, rather
    // than calling play()/write() on a dead track.
    if (track.state != AudioTrack.STATE_INITIALIZED) {
        track.release()
        throw IllegalStateException("AudioTrack init failed: state=${track.state}")
    }
    return AudioTrackSink(track, bytesPerFrame)
}

enum class PlaybackState {
    /** Waiting for first audio chunk and time sync to be ready. */
    INITIALIZING,

    /** Buffer filling, scheduled start time computed. Waiting for enough buffer and start time. */
    WAITING_FOR_START,

    /** Active synchronized playback with sample insert/drop corrections. */
    PLAYING,

    /** Large sync error exceeded threshold. Resetting timing state to recover. */
    REANCHORING
}

/**
 * Callback interface for SyncAudioPlayer state changes.
 */
interface SyncAudioPlayerCallback {
    /**
     * Called when the playback state changes.
     */
    fun onPlaybackStateChanged(state: PlaybackState)

    /**
     * Fork: the AudioTrack could not be recovered (sink-recovery budget spent).
     * The owner should tear this player down so the next stream builds a new one.
     */
    fun onBufferExhausted() {}
}

/**
 * Synchronized audio player for Sendspin protocol.
 *
 * Receives PCM audio chunks with server timestamps and plays them at the correct
 * client time using the Kalman-filtered time offset. Uses imperceptible sample
 * insert/drop for sync correction (no pitch changes).
 *
 * ## Sync Correction Strategy
 * Instead of rate adjustment (which causes audible pitch changes), we use sample
 * insert/drop which is completely imperceptible:
 * - Behind schedule: Drop frames to catch up (skip input samples)
 * - Ahead of schedule: Insert duplicate frames to slow down
 * - At most one 21us step per 20ms (0.1% speed change)
 * - Past +/-1ms, at startup and after an underrun: one-shot resync instead
 *
 * ## Architecture
 * ```
 * SendSpin ──┬── Audio chunks (timestamped) ──► SyncAudioPlayer
 *                  │                                        │
 *                  └── TimeFilter ◄─────────────────────────┘
 *                         │
 *                    serverToClient()
 * ```
 */
class SyncAudioPlayer(
    private val timeFilter: SendspinTimeFilter,
    private val sampleRate: Int = SendSpinProtocol.AudioFormat.SAMPLE_RATE,
    private val channels: Int = SendSpinProtocol.AudioFormat.CHANNELS,
    private val bitDepth: Int = SendSpinProtocol.AudioFormat.BIT_DEPTH,
    private val maxQueueSamples: Long = 0,  // 0 = unlimited; >0 caps queue to this many samples
    // Injectable monotonic clock for testability; production default is System.nanoTime().
    private val nowNs: () -> Long = { System.nanoTime() },
    // Injectable audio sink factory for testability; production default wraps AudioTrack.
    // The bufferSize parameter is precomputed (via AudioTrack.getMinBufferSize + multiplier)
    // and passed in rather than queried inside the factory.
    private val sinkFactory: (sampleRate: Int, channels: Int, bitDepth: Int, bufferSize: Int) -> AudioSink =
        ::defaultSinkFactory,
) {
    companion object {
        // Sync correction thresholds (microseconds), from the spec's suggested
        // strategy (roles/player/v1.md, "Sample deletion and insertion")
        private const val DEADBAND_THRESHOLD_US = 100L          // 100us - no correction needed
        private const val SNAP_THRESHOLD_US = 1_000L            // 1ms accuracy floor - one-shot resync beyond it
        private const val HARD_RESYNC_THRESHOLD_US = 200_000L   // 200ms - hard resync (drop/skip chunks)

        // Soft correction: one step of CORRECTION_STEP_US of audio (1 frame at
        // 48kHz) at most every CORRECTION_INTERVAL_US. 21us per 20ms is a 0.1%
        // speed change, inside the spec's +/-0.5% over 150ms, and corrects 1ms
        // of error per second.
        private const val CORRECTION_STEP_US = 21L
        private const val CORRECTION_INTERVAL_US = 20_000L

        // Startup grace period - no corrections until timing stabilizes (Windows SDK: 500ms)
        private const val STARTUP_GRACE_PERIOD_US = 500_000L    // 500ms grace period

        // Buffer configuration
        private const val BUFFER_SIZE_MULTIPLIER = 4  // Multiplier for minimum buffer size

        // AudioTrack failure recovery. After this many consecutive failing
        // writeToSink() CALLS (negative AudioTrack.write() codes such as
        // ERROR_DEAD_OBJECT, returned when the HDMI/AVR audio route renegotiates
        // mid-stream), release and recreate the sink instead of spinning forever on
        // a dead track. This is a count of failed write CALLS, not a duration: the
        // silence/keepalive path writes once per ~10ms loop, but the per-frame
        // correction path can issue hundreds of writes per chunk, so the wall-clock
        // time to reach the threshold varies with the active write path.
        private const val SINK_FAILURE_RECOVERY_THRESHOLD = 50
        // Recreate attempts are spaced by SINK_RECOVERY_BACKOFF_US (a renegotiating
        // HAL needs ~1-3s to settle, and the spacing stops a failing rebuild from
        // re-firing every loop tick). At most MAX_SINK_RECOVERIES attempts are
        // allowed within a rolling SINK_RECOVERY_WINDOW_US; recoveries older than the
        // window no longer count, so well-spaced faults over a long session never
        // exhaust the budget, while a tight recreate storm still gives up and
        // escalates via onBufferExhausted().
        private const val MAX_SINK_RECOVERIES = 10
        private const val SINK_RECOVERY_BACKOFF_US = 1_000_000L   // 1s between recreate attempts
        private const val SINK_RECOVERY_WINDOW_US = 60_000_000L   // 60s rolling budget window

        // Sync error Kalman filter parameters
        // AudioTimestamp jitter is about +/-0.65ms peak; the process noise gives
        // the estimate a time constant of about a second.
        private const val SYNC_ERROR_MEASUREMENT_NOISE_US = 400L
        private const val SYNC_ERROR_PROCESS_STD_DEV = 0.085

        // An AudioTimestamp older than this means the track has stalled (underrun)
        private const val TIMESTAMP_MAX_AGE_US = 100_000L

        // Start gating configuration (from Python reference)
        private const val MIN_BUFFER_BEFORE_START_MS = 200  // Wait for 200ms buffer before scheduling
        private const val REANCHOR_THRESHOLD_US = 500_000L  // 500ms error triggers reanchor

        // DAC-position-aware startup alignment
        private const val TARGET_PENDING_US = 250_000L      // 250ms target write-to-DAC distance
        private const val PENDING_TOL_US = 50_000L           // 50ms pacing tolerance
        private const val START_PAD_MAX_US = 20_000L         // start once the head chunk is due within 20ms
        private const val TIMESTAMP_STABLE_READS = 3         // consecutive valid getTimestamp() reads
        private const val REANCHOR_COOLDOWN_US = 5_000_000L // 5 second cooldown between reanchors

        // Silence keepalive: write silence when pending-to-DAC drops below this threshold
        private const val SILENCE_KEEPALIVE_THRESHOLD_US = 200_000L  // 200ms

        // Playback loop timing (milliseconds)
        private const val STATE_POLL_DELAY_MS = 10L   // Polling interval during state transitions
        private const val BUFFER_EMPTY_DELAY_MS = 5L  // Short delay when buffer is empty

        // Gap/overlap detection
        private const val GAP_THRESHOLD_US = 10_000L  // 10ms minimum gap before filling with silence
        private const val DISCONTINUITY_THRESHOLD_US = 100_000L  // 100ms gap indicates discontinuity (for logging)
        // Upper bound on gaps we'll bridge with silence. Larger gaps (typically from
        // a stale expectedNextTimestampUs after a long DRAINING / disconnect) would
        // allocate megabytes of silence and inject multi-second muted audio — instead
        // we discard the stale anchor and let this chunk re-seed the timeline.
        private const val MAX_SILENCE_GAP_US = 2_000_000L  // 2s cap on silence insertion
        // Upper bound on a single audio chunk's duration. Used to cap the empty-chunk
        // cadence estimate so a stale lastChunkServerTime (e.g. surviving a DRAINING
        // / exitDraining cycle) can't push expectedNextTimestampUs seconds ahead.
        // Opus tops out at 60ms per packet; FLAC chunks here run shorter. 200ms is
        // a comfortable upper bound for any future codec.
        private const val MAX_PLAUSIBLE_CHUNK_US = 200_000L

        // Logging and diagnostics
        private const val CHUNK_DROP_LOG_INTERVAL = 100  // Log every Nth dropped chunk when time sync not ready
        private const val DAC_PACING_LOG_INTERVAL_US = 10_000_000L  // Log DAC pacing stats every 10 seconds

        // Stuck-state watchdog: detects when the state machine wedges in a
        // non-PLAYING state while chunks are arriving (diagnostic only).
        private const val STUCK_STATE_WARNING_US = 5_000_000L         // 5s
        private const val STUCK_STATE_WARNING_INTERVAL_US = 10_000_000L  // 10s between warnings

        // Pre-sync buffering - buffer chunks while waiting for time sync to be ready
        private const val MAX_PENDING_CHUNKS = 500  // ~10 seconds at 48kHz/20ms chunks

        // Coroutine cancellation. Best-effort wait after scope.cancel(); the
        // worst case is bounded by a single AudioTrack.write() duration (one
        // chunk, ~20 ms at 48 kHz), so 250 ms is comfortably above the typical
        // exit latency and well below Android's 5 s ANR threshold for main.
        private const val PLAYBACK_LOOP_CANCEL_TIMEOUT_MS = 250L
    }

    /**
     * Timestamped audio chunk waiting to be played.
     */
    private data class AudioChunk(
        val serverTimeMicros: Long,
        val pcmData: ByteArray,
        val sampleCount: Int
    )

    // Dedicated audio thread for the playback write loop.
    //
    // The write loop owns every blocking AudioTrack.write() call, the
    // System.nanoTime()-based sync error computation, and the sample
    // insert/drop corrector. Running it on a shared pool (Dispatchers.Default)
    // at normal priority leaves it vulnerable to background CPU throttling:
    // display-pipeline suspend / big.LITTLE migration / thermal DVFS can delay
    // the coroutine past AudioTrack's DAC deadline, causing underruns and
    // phantom sync drift that the corrector then chases with ±4% rate
    // adjustments (audible pitch warble).
    //
    // A HandlerThread constructed with THREAD_PRIORITY_URGENT_AUDIO stays on
    // the audio-class cpuset across foreground/background transitions, which
    // is exactly what AudioTrack MODE_STREAM push-model playback needs.
    //
    // Thread-level lifecycle: created once per SyncAudioPlayer instance, quit
    // in release() after the final coroutine drains. Safe because only one
    // coroutine is ever launched into the scope (the main playback loop), so
    // serializing through a single Looper cannot starve siblings.
    private val audioThread: HandlerThread =
        HandlerThread("SendSpinAudio", Process.THREAD_PRIORITY_URGENT_AUDIO).apply { start() }
    private val audioDispatcher: CoroutineDispatcher =
        Handler(audioThread.looper).asCoroutineDispatcher("SendSpinAudioDispatcher")

    // Coroutine scope for playback - recreated for each playback session
    private var scope: CoroutineScope? = null
    private var playbackJob: Job? = null

    // Lock for thread-safe state transitions
    private val stateLock = ReentrantLock()

    // Flag to track if release() has been called
    private val isReleased = AtomicBoolean(false)

    // Audio output. @Volatile: nulled by release() (which awaits playback-loop
    // cancellation only with a 250ms timeout) and read from the playback loop on
    // every iteration. Without the barrier, a stale non-null read after the
    // timeout fires can land on a released AudioSink and throw ISE from write().
    @Volatile private var audioSink: AudioSink? = null
    private val isPlaying = AtomicBoolean(false)
    private val isPaused = AtomicBoolean(false)

    // Flush coordination: set by clearBuffer()/enterIdle() on the main thread,
    // checked and cleared by the playback loop before writes.  This avoids
    // flushing AudioTrack while the playback loop is mid-write, which causes
    // clicks/pops and incorrect frame accounting.
    private val isFlushPending = AtomicBoolean(false)
    @Volatile private var pausedAtUs: Long = 0L  // Timestamp when pause() was called, for long-pause detection

    // AudioTrack write-failure recovery state. These counters are mutated on the
    // playback thread (writeToSink and recoverFromSinkFailure at the loop top) and
    // reset in start() on the caller thread under stateLock. start() runs that reset
    // only after the previous playback loop has been awaited/cancelled and launches
    // the new loop afterward, so the lock plus that happens-before ordering -- not
    // pure thread confinement -- is what keeps them safe without @Volatile.
    // audioBufferSize is written once in initialize() (caller thread) and read by
    // recovery (playback thread), so it stays @Volatile.
    private var consecutiveWriteFailures = 0
    private var sinkRecoveryCount = 0
    private var lastSinkRecoveryUs = 0L          // wall-clock of last recovery, for the windowed budget
    private var lastSinkRecoveryAttemptUs = 0L   // wall-clock of last recreate attempt, for backoff
    @Volatile private var audioBufferSize = 0

    // Playback state machine (from Python reference)
    @Volatile private var playbackState = PlaybackState.INITIALIZING
    @Volatile private var stateCallback: SyncAudioPlayerCallback? = null
    // @Volatile: written under stateLock from processChunk on PlaybackService's
    // decodeDispatcher (Dispatchers.IO.limitedParallelism(1)), read off-lock from
    // handleStartGatingKalman/handleStartGatingDacAware on the playback coroutine.
    // Without the barrier, the playback loop can observe a stale null and stall in
    // WAITING_FOR_START indefinitely, or read a stale value and transition to
    // PLAYING with the wrong scheduled start.
    @Volatile private var scheduledStartLoopTimeUs: Long? = null   // When to start in loop time
    @Volatile private var firstServerTimestampUs: Long? = null     // First chunk's server timestamp
    private var lastReanchorTimeUs: Long = 0             // Cooldown tracking for reanchor (single-thread)

    // DAC timestamp stability tracking for start gating.
    // @Volatile: written under stateLock by resume/stop/enterIdle/clearBuffer
    // (Main) and triggerReanchor (playback coroutine); written off-lock on the
    // playback coroutine by preCalibrateDacTiming() as samples accumulate.
    // Read on the playback coroutine without stateLock. Without volatility a
    // stale `true` can persist mid-iteration after a reset, sending the loop
    // down DAC-timestamp-dependent paths whose backing timestamps were just
    // invalidated.
    @Volatile private var consecutiveValidTimestamps = 0       // counts consecutive valid getTimestamp() reads
    @Volatile private var dacTimestampsStable = false           // true once TIMESTAMP_STABLE_READS reached

    // DAC-aware alignment wait: rate-limit the per-iteration "waiting for alignment"
    // log so a 2-12s wait emits ~3-13 lines instead of 200-1200. Entry log fires once
    // when we first enter alignment wait; progress logs fire at 1s intervals while
    // still waiting; exit log fires when alignment completes. Both fields reset to 0L
    // on successful transition to PLAYING so the next alignment wait emits fresh logs.
    @Volatile private var alignmentWaitStartedAtUs: Long = 0L
    @Volatile private var alignmentWaitLastLoggedUs: Long = 0L

    private var lastDacPacingLogTimeUs: Long = 0         // Rate limiting for DAC pacing diagnostics

    // Chunk queue
    private val chunkQueue = ConcurrentLinkedQueue<AudioChunk>()
    private val totalQueuedSamples = AtomicLong(0)
    private var queueCapDrops = 0  // Counter for capacity-based drops (diagnostics)

    // Sync tracking. @Volatile because exitDraining()/enterIdle()/clearBuffer()
    // write 0L under stateLock on Main and triggerReanchor() writes 0L under
    // stateLock on the playback coroutine, while processChunk() reads and writes
    // this on PlaybackService's decodeDispatcher BEFORE acquiring stateLock (the
    // empty-chunk cadence-estimator block and discontinuity-log block run outside
    // the stateLock region). Without the barrier, the decode dispatcher can see a
    // stale pre-disconnect value and the estimator pushes expectedNextTimestampUs
    // seconds ahead.
    @Volatile private var lastChunkServerTime = 0L
    @Volatile private var streamGeneration = 0  // Incremented on stream/clear to invalidate old chunks

    // Sync error tracking
    private val totalFramesWritten = AtomicLong(0)  // Total frames written to AudioTrack

    // Playback position tracking (in server timeline)
    // Tracks where we've written up to in the server timeline (input side).
    // Advanced in playChunkWithCorrection based on input frames consumed.
    // Used for stats/UI display only; NOT used in sync error calculation.
    @Volatile private var serverTimelineCursor = 0L
    private var serverTimelineCursorRemainder = 0L  // Sub-microsecond accumulator for precision

    // ========================================================================
    // Sync Error Tracking
    // ========================================================================
    //
    // Sync error = server time at which a chunk will reach the DAC, minus the
    // chunk's own server timestamp. See updateSyncError().
    //
    // Sign convention:
    //   Positive = audio reaches the DAC late  -> need DROP
    //   Negative = audio reaches the DAC early -> need INSERT
    //
    private var playbackStartTimeUs = 0L          // When playback started (for stats display)
    // @Volatile (fork): written on Main and on the playback coroutine, read
    // off-lock by the loop and on Main by getStats().
    @Volatile private var startTimeCalibrated = false       // Has a sync error been measured since playback (re)started?
    @Volatile private var samplesReadSinceStart = 0L        // Total samples consumed since playback started
    @Volatile private var syncErrorUs = 0L        // Current sync error (for display)

    // Why the output is silenced; audible only when empty. Written on the main thread.
    @Volatile private var muteReasons: Set<MuteReason> = emptySet()

    // 2D Kalman filter for sync error smoothing (tracks offset + drift)
    // Based on Python reference implementation for optimal noise filtering
    private val syncErrorFilter = SyncErrorFilter(
        processStdDev = SYNC_ERROR_PROCESS_STD_DEV,
        measurementNoiseUs = SYNC_ERROR_MEASUREMENT_NOISE_US
    )

    // Sample insert/drop correction state
    private val correctionFrames = maxOf(1, ((CORRECTION_STEP_US * sampleRate + 500_000) / 1_000_000).toInt())
    private val correctionIntervalFrames = ((CORRECTION_INTERVAL_US * sampleRate) / 1_000_000).toInt()
    // @Volatile (fork) on the *EveryNFrames pair: written under stateLock and
    // off-lock by the loop, read on Main via getStats().
    @Volatile private var insertEveryNFrames: Int = 0      // Duplicating a frame every N frames (slow down), 0 = off
    @Volatile private var dropEveryNFrames: Int = 0        // Dropping a frame every N frames (speed up), 0 = off
    private var framesSinceCorrection: Int = 0   // Frames written since the last correction slot
    private var snapDropFrames: Long = 0         // One-shot resync: leading frames still to drop

    // Startup grace period tracking (Windows SDK style).
    // No corrections applied until STARTUP_GRACE_PERIOD_US after entering PLAYING.
    // @Volatile: written under stateLock by resume()/enterIdle()/clearBuffer()
    // (Main) and triggerReanchor() (playback coroutine); also written by
    // setPlaybackState(), which is called from Main and from the playback
    // coroutine (handleStartGatingDacAware/handleStartGatingKalman). Read
    // off-lock by updateCorrectionSchedule() on the playback coroutine and by
    // getGracePeriodRemainingUs() on Main, which reads the field twice and would
    // otherwise observe two different values mid-write.
    @Volatile private var playingStateEnteredAtUs = 0L

    // Statistics - @Volatile because incremented on the playback loop / decode
    // dispatcher and read on Main via getStats(). Single-thread RMW counters can
    // stay @Volatile (Long); chunksDropped is incremented from BOTH the decode
    // dispatcher (queueChunk's pending-buffer-full branch) and the playback loop
    // (stale-chunk drops in handleStartGatingDacAware/handleStartGatingKalman),
    // so it needs AtomicLong to avoid losing increments under concurrent RMW.
    @Volatile private var chunksReceived = 0L
    @Volatile private var chunksPlayed = 0L
    private val chunksDropped = AtomicLong(0)
    @Volatile private var syncCorrections = 0L
    @Volatile private var framesInserted = 0L
    @Volatile private var framesDropped = 0L
    @Volatile private var reanchorCount = 0L        // Count of reanchor events
    @Volatile private var bufferUnderrunCount = 0L  // Count of underrun events (edge-triggered)
    // Latch for the above: set while the queue is starved, cleared once audio
    // is available again, so one starvation counts once however long it lasts.
    @Volatile private var inUnderrun = false

    // Stuck-state watchdog: tracks when a non-PLAYING state was first entered.
    // Used by the stats logger to surface state-machine deadlocks.
    private var stuckStateEnteredAtUs: Long = 0L
    private var lastObservedState: PlaybackState = PlaybackState.INITIALIZING
    private var lastStuckWarningAtUs: Long = 0L

    // Pre-sync chunk buffer - holds chunks received before time sync is ready.
    // These will be processed once time sync completes. Mutations require the
    // `synchronized(pendingChunks)` monitor; see [hasPendingChunks] for the
    // lock-free reader hint.
    private val pendingChunks = mutableListOf<Pair<Long, ByteArray>>()

    // Lock-free fast-path hint for [processPendingChunks]. Writes happen under
    // `synchronized(pendingChunks)`, reads are lock-free. A stale-true read is
    // benign (one wasted lock acquisition); a stale-false read is prevented
    // because every add sets this before releasing the monitor, and @Volatile
    // gives the subsequent reader the correct visibility.
    @Volatile private var hasPendingChunks = false

    // Gap/overlap handling (from Python reference)
    // @Volatile because this is written by both the decode worker (in
    // processChunk via queueChunk) and the Main thread (in resume/enterIdle/
    // clearBuffer/stop). Without it, the worker can observe a stale value
    // after a Main-thread reset and compute spurious gap/overlap stats at
    // the splice point.
    @Volatile private var expectedNextTimestampUs: Long? = null  // Expected server timestamp of next chunk
    // Gap/overlap counters: same @Volatile contract as the stats group above
    // (incremented on the decode dispatcher in processChunk, read on Main via
    // getStats(); never reset, so single-thread RMW is safe).
    @Volatile private var gapsFilled = 0L         // Count of gaps filled with silence
    @Volatile private var gapSilenceMs = 0L       // Total milliseconds of silence inserted
    @Volatile private var overlapsTrimmed = 0L    // Count of overlaps trimmed
    @Volatile private var overlapTrimmedMs = 0L   // Total milliseconds of audio trimmed

    // Bytes per sample (e.g., 2 channels * 2 bytes = 4 bytes per sample frame)
    private val bytesPerFrame = channels * (bitDepth / 8)

    // Pre-allocated silence buffer for DAC pre-calibration and keepalive (10ms at sample rate).
    // Avoids allocating a new ByteArray on every iteration of the hot audio loop (~100 alloc/sec).
    private val silenceFrameCount = sampleRate / 100  // 10ms of silence
    private val silenceBuffer = ByteArray(silenceFrameCount * bytesPerFrame)

    // Microseconds per sample frame
    private val microsPerSample = 1_000_000.0 / sampleRate

    /**
     * Initialize the audio player with the specified format.
     */
    fun initialize() {
        if (isReleased.get()) {
            AppLog.Audio.e("Cannot initialize - player has been released")
            return
        }

        stateLock.withLock {
            if (audioSink != null) {
                AppLog.Audio.w("Already initialized")
                return
            }
        }

        val channelConfig = when (channels) {
            1 -> AudioFormat.CHANNEL_OUT_MONO
            2 -> AudioFormat.CHANNEL_OUT_STEREO
            else -> {
                AppLog.Audio.e("Unsupported channel count: $channels")
                return
            }
        }

        val encoding = when (bitDepth) {
            16 -> AudioFormat.ENCODING_PCM_16BIT
            24 -> if (Build.VERSION.SDK_INT >= 31) {
                AudioFormat.ENCODING_PCM_24BIT_PACKED
            } else {
                AppLog.Audio.e("24-bit PCM requires API 31+, device is API ${Build.VERSION.SDK_INT}")
                return
            }
            32 -> if (Build.VERSION.SDK_INT >= 31) {
                AudioFormat.ENCODING_PCM_32BIT
            } else {
                AppLog.Audio.e("32-bit PCM requires API 31+, device is API ${Build.VERSION.SDK_INT}")
                return
            }
            else -> {
                AppLog.Audio.e("Unsupported bit depth: $bitDepth")
                return
            }
        }

        // Calculate minimum buffer size
        val minBufferSize = AudioTrack.getMinBufferSize(sampleRate, channelConfig, encoding)
        // Use larger buffer for scheduling headroom
        val bufferSize = maxOf(minBufferSize * BUFFER_SIZE_MULTIPLIER, sampleRate * bytesPerFrame) // ~1 second
        audioBufferSize = bufferSize  // captured so recoverFromSinkFailure() can rebuild the same-size sink

        try {
            audioSink = sinkFactory(sampleRate, channels, bitDepth, bufferSize)
            if (muteReasons.isNotEmpty()) audioSink?.setVolume(0f)

            AppLog.Audio.i("AudioTrack initialized: ${sampleRate}Hz, ${channels}ch, ${bitDepth}bit, buffer=${bufferSize}bytes")
        } catch (e: Exception) {
            AppLog.Audio.e("Failed to create AudioTrack", e)
        }
    }

    /**
     * Start playback.
     *
     * This method is thread-safe and handles rapid start/stop cycles by ensuring
     * any existing coroutine scope is fully cancelled before creating a new one.
     */
    fun start() {
        if (isReleased.get()) {
            AppLog.Audio.e("Cannot start - player has been released")
            return
        }

        // Phase 1: Under lock, capture old playback loop refs and check preconditions
        val captured = stateLock.withLock {
            if (isPlaying.get()) {
                AppLog.Audio.w("Already playing")
                return
            }

            if (audioSink == null) {
                AppLog.Audio.e("AudioTrack not initialized")
                return
            }

            // Capture and clear old playback loop references while holding the lock.
            // The actual cancellation/join happens outside the lock to avoid deadlock.
            captureAndClearPlaybackLoop()
        }

        // Phase 2: Outside lock - cancel old scope and wait for coroutine to finish.
        // Safe because references were already nulled under the lock, so no other
        // thread can see or interact with the old scope/job.
        awaitPlaybackLoopCancellation(captured)

        // Phase 3: Re-acquire lock to set up new playback state
        stateLock.withLock {
            // Re-check preconditions after re-acquiring lock - another thread may
            // have called start() or release() while we were awaiting cancellation
            if (isPlaying.get() || isReleased.get()) {
                AppLog.Audio.w("State changed during playback loop cancellation - aborting start")
                return
            }

            val track = audioSink
            if (track == null) {
                AppLog.Audio.e("AudioTrack was released during playback loop cancellation")
                return
            }

            // Defensive check: scope should be null after capture+await
            if (scope != null) {
                AppLog.Audio.e("BUG: Scope was not null after cancellation - forcing cleanup")
                scope?.cancel()
                scope = null
            }

            // Create a new scope for this playback session
            // Using SupervisorJob so child failures don't cancel the scope.
            // Dispatcher is backed by a dedicated HandlerThread running at
            // THREAD_PRIORITY_URGENT_AUDIO so the write loop keeps its audio
            // deadline even when the app is backgrounded.
            scope = CoroutineScope(SupervisorJob() + audioDispatcher)

            // Fresh sink-failure recovery budget per playback session.
            consecutiveWriteFailures = 0
            sinkRecoveryCount = 0
            lastSinkRecoveryUs = 0L
            lastSinkRecoveryAttemptUs = 0L

            isPlaying.set(true)
            isPaused.set(false)
            track.play()

            // Start the playback loop
            startPlaybackLoop()

            AppLog.Audio.i("Playback started")
        }
    }

    /**
     * Pause playback.
     *
     * Flushes the AudioTrack hardware buffer so audio stops immediately.
     * The chunk-level queue is preserved for seamless resume.
     */
    fun pause() {
        stateLock.withLock {
            isPaused.set(true)
            pausedAtUs = nowNs() / 1000
            audioSink?.pause()
            audioSink?.flush()
            AppLog.Audio.d("Playback paused")
        }
    }

    /**
     * Resume playback.
     *
     * Resets sync state that becomes stale during pause:
     * - DAC calibrations (System.nanoTime() continues advancing during pause)
     * - Sync error filter (pre-pause error is no longer relevant)
     * - Correction schedule (start fresh)
     * - Grace period (allow sync to stabilize after resume)
     *
     * For long pauses (>5 seconds), clears the buffer and reinitializes
     * since buffered chunks will be too stale.
     */
    fun resume() {
        stateLock.withLock {
            if (!isPaused.get()) {
                // Even if our flag says not paused, the AudioTrack hardware might still be paused
                // (e.g., after clearBuffer() was called while paused)
                if (audioSink?.playState != AudioTrack.PLAYSTATE_PLAYING) {
                    AppLog.Audio.i("resume() - isPaused is false but AudioTrack is not playing, forcing play")
                    audioSink?.play()
                } else {
                    AppLog.Audio.d("resume() called but not paused - ignoring")
                }
                return@withLock
            }

            val nowUs = nowNs() / 1000
            val pauseDurationUs = nowUs - pausedAtUs
            val LONG_PAUSE_THRESHOLD_US = 5_000_000L  // 5 seconds

            if (pauseDurationUs > LONG_PAUSE_THRESHOLD_US) {
                AppLog.Audio.d("Long pause detected (${pauseDurationUs / 1000}ms) - clearing stale buffer")
                // Clear buffer and let it refill from server
                discardQueuedAudio()
                setPlaybackState(PlaybackState.INITIALIZING)
                expectedNextTimestampUs = null
            }

            // pause() flushed the track, which restarts its frame position from
            // zero. Have the playback loop flush again and restart its own frame
            // count with it, on the thread that does the writes.
            isFlushPending.set(true)

            // Reset sync error filter - pre-pause state is no longer relevant
            syncErrorFilter.reset()
            syncErrorUs = 0L
            startTimeCalibrated = false        // Force recalibration after resume

            // Reset correction schedule - start fresh
            insertEveryNFrames = 0
            dropEveryNFrames = 0
            snapDropFrames = 0

            // Reset grace period to allow sync to stabilize after resume
            playingStateEnteredAtUs = nowUs

            isPaused.set(false)
            audioSink?.play()
            AppLog.Audio.d("Playback resumed after ${pauseDurationUs / 1000}ms pause - sync state reset")
        }
    }

    /**
     * Set the playback volume.
     *
     * Note: Volume is now controlled via device STREAM_MUSIC (AudioManager),
     * not per-AudioTrack gain. This method is kept for API compatibility but
     * AudioTrack always plays at full volume. Device volume handles attenuation.
     *
     * @param volume Volume level from 0.0 (mute) to 1.0 (full volume) - ignored
     */
    @Suppress("UNUSED_PARAMETER")
    fun setVolume(volume: Float) {
        // Volume is now controlled via device STREAM_MUSIC, not AudioTrack gain.
        // AudioTrack plays at full volume; device media stream handles attenuation.
        // This follows Spotify/Plexamp best practices for hardware volume button support.
        AppLog.Audio.d("setVolume called (ignored - using device volume): $volume")
    }

    /** Why the output is muted. The reasons are independent; any one silences it. */
    enum class MuteReason {
        /** The player's `muted` state (server or user); the only one reported in client/state. */
        PLAYER,
        /** The client is reporting `state="error"` and must mute until it is back in sync. */
        SYNC,
        /** Another app has the audio output (focus loss or output disconnect). */
        INTERRUPTION,
    }

    /**
     * Mute or un-mute the output for one [reason]. Mute is AudioTrack gain, not
     * device volume, so the two stay independent: "a volume change ... MUST NOT
     * clear the mute state". It takes effect immediately and audio keeps
     * draining in sync while muted, so un-muting is exactly in time.
     *
     * Main thread only.
     */
    fun setMuted(reason: MuteReason, muted: Boolean) {
        muteReasons = if (muted) muteReasons + reason else muteReasons - reason
        audioSink?.setVolume(if (muteReasons.isEmpty()) 1f else 0f)
        AppLog.Audio.i("Mute $reason=$muted, muted by $muteReasons")
    }

    /**
     * Stop playback and clear buffers.
     *
     * This method is thread-safe and can be called from any thread.
     * It will wait for the playback loop to finish before returning.
     */
    fun stop() {
        // Phase 1: Under lock, signal stop and capture playback loop references
        val captured = stateLock.withLock {
            // Signal the playback loop to stop
            isPlaying.set(false)
            isPaused.set(false)

            // Capture and clear playback loop references while holding the lock
            captureAndClearPlaybackLoop()
        }

        // Phase 2: Outside lock - cancel scope and wait for coroutine to finish.
        // The isPlaying=false signal causes the loop's while condition to exit,
        // and scope.cancel() ensures prompt cancellation of any suspend points.
        awaitPlaybackLoopCancellation(captured)

        // Phase 3: Re-acquire lock for AudioTrack and state cleanup
        stateLock.withLock {
            // Now safe to manipulate AudioTrack - playback loop has stopped
            isFlushPending.set(false)  // Clear any pending flush since we flush directly below
            audioSink?.stop()
            audioSink?.flush()
            totalFramesWritten.set(0)
            discardQueuedAudio()

            // Clear pending chunks buffer
            synchronized(pendingChunks) {
                pendingChunks.clear()
                hasPendingChunks = false
            }

            // Reset playback state machine
            setPlaybackState(PlaybackState.INITIALIZING)
            scheduledStartLoopTimeUs = null
            firstServerTimestampUs = null

            // Reset DAC timestamp stability tracking
            consecutiveValidTimestamps = 0
            dacTimestampsStable = false

            AppLog.Audio.i("Playback stopped")
        }
    }

    /**
     * Enter idle mode: reset sync state but keep AudioTrack alive and writing silence.
     *
     * Used when the stream ends but the server is still connected. The playback loop
     * continues in INITIALIZING state, writing silence to keep DAC timestamps warm
     * for the next stream start.
     *
     * This method is thread-safe and can be called from any thread.
     */
    fun enterIdle() {
        stateLock.withLock {
            // Mirrors clearBuffer(): invalidate any queueChunk() invocations
            // that are still in flight on the WebSocket IO thread. Without
            // this, a chunk whose queueChunk() captured the pre-idle
            // generation can re-populate the queue after the clears below
            // run, leaving stale audio in the pipeline after stream/end.
            streamGeneration++

            AppLog.Audio.i("[cmd-trace] T4 enterIdle ts=${nowNs() / 1_000_000} thread=${Thread.currentThread().name} gen=$streamGeneration")

            // Clear all audio buffers
            discardQueuedAudio()
            synchronized(pendingChunks) {
                pendingChunks.clear()
                hasPendingChunks = false
            }

            lastChunkServerTime = 0L

            // Reset playback state machine to INITIALIZING (silence-writing state)
            setPlaybackState(PlaybackState.INITIALIZING)
            scheduledStartLoopTimeUs = null
            firstServerTimestampUs = null

            // Reset sync error tracking
            totalFramesWritten.set(0)
            serverTimelineCursor = 0L
            serverTimelineCursorRemainder = 0L
            playbackStartTimeUs = 0L
            startTimeCalibrated = false
            samplesReadSinceStart = 0L
            syncErrorUs = 0L
            syncErrorFilter.reset()
            playingStateEnteredAtUs = 0L

            // Reset DAC timestamp stability tracking so it re-warms
            consecutiveValidTimestamps = 0
            dacTimestampsStable = false
            lastDacPacingLogTimeUs = 0L

            // Reset sample insert/drop correction state
            insertEveryNFrames = 0
            dropEveryNFrames = 0
            snapDropFrames = 0

            // Reset gap/overlap tracking
            expectedNextTimestampUs = null

            // Signal the playback loop to flush AudioTrack before its next write.
            // We must NOT flush here because the playback loop may be mid-write()
            // on the coroutine thread (H-11).
            if (audioSink != null && isPlaying.get()) {
                isFlushPending.set(true)
            } else {
                val track = audioSink
                if (track != null) {
                    try {
                        track.flush()
                    } catch (e: IllegalStateException) {
                        AppLog.Audio.w("Failed to flush AudioTrack during enterIdle", e)
                    }
                }
            }

            // NOTE: Do NOT stop AudioTrack or cancel playback loop.
            // The loop will continue in INITIALIZING state, writing silence
            // to keep DAC timestamps warm.

            AppLog.Audio.i("Entered idle mode - continuing silence for DAC keepalive")
        }
    }

    /**
     * Check if this player's format matches the given parameters.
     *
     * Used to determine if the player can be reused for a new stream
     * without tearing down the AudioTrack (preserving DAC timestamp warmth).
     */
    fun matchesFormat(sr: Int, ch: Int, bd: Int): Boolean =
        sr == sampleRate && ch == channels && bd == bitDepth

    /**
     * Capture playback loop references and clear them atomically.
     *
     * Must be called while holding stateLock. Returns a pair of (scope, job) that
     * the caller must pass to [awaitPlaybackLoopCancellation] OUTSIDE the lock.
     *
     * Splitting capture (under lock) from await (outside lock) prevents deadlock:
     * the playback loop may call setPlaybackState() which acquires stateLock, so
     * we must not hold stateLock while waiting for the loop to finish.
     */
    private fun captureAndClearPlaybackLoop(): Pair<CoroutineScope?, Job?> {
        val currentScope = scope
        val job = playbackJob

        // Clear references immediately to prevent race conditions where a new
        // start() call could see stale references
        playbackJob = null
        scope = null

        return Pair(currentScope, job)
    }

    /**
     * Cancel the playback scope and wait for the job to complete.
     *
     * MUST be called OUTSIDE stateLock to avoid deadlock. The playback loop
     * coroutine may be blocked on stateLock (e.g. inside setPlaybackState()),
     * so holding the lock here would create a deadlock cycle:
     *   main thread holds stateLock -> wait for coroutine
     *   coroutine waits for stateLock -> deadlock
     *
     * Uses a [CountDownLatch] + [Job.invokeOnCompletion] instead of
     * `runBlocking { job.join() }`. The scope cancel is the actual cleanup
     * mechanism; this wait only exists so subsequent phases of stop()/release()
     * can touch the AudioTrack without racing the loop's final write. A bare
     * JVM latch avoids spinning up a new coroutine event loop on the caller
     * thread for a single await.
     */
    private fun awaitPlaybackLoopCancellation(scopeAndJob: Pair<CoroutineScope?, Job?>) {
        val (currentScope, job) = scopeAndJob

        if (currentScope == null) {
            return
        }

        // Cancel the scope first - this cancels ALL coroutines in the scope,
        // not just the playback job. This is safer than cancelling individual jobs.
        currentScope.cancel()

        // Wait for the job to complete if it was active
        if (job != null && job.isActive) {
            val latch = CountDownLatch(1)
            job.invokeOnCompletion { latch.countDown() }
            try {
                if (!latch.await(PLAYBACK_LOOP_CANCEL_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                    AppLog.Audio.w("Playback loop did not stop within timeout - scope was cancelled, coroutines will be cleaned up")
                }
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                AppLog.Audio.w("Interrupted while waiting for playback loop to stop", e)
            }
        }

        AppLog.Audio.v("Playback loop cancelled and cleaned up")
    }

    /**
     * Release all resources.
     *
     * After calling this method, the player cannot be reused.
     * This method is idempotent and thread-safe.
     */
    fun release() {
        if (isReleased.getAndSet(true)) {
            AppLog.Audio.w("Already released")
            return
        }

        // Phase 1: Under lock, signal stop and capture playback loop references
        val captured = stateLock.withLock {
            isPlaying.set(false)
            isPaused.set(false)

            // Capture and clear playback loop references while holding the lock
            captureAndClearPlaybackLoop()
        }

        // Phase 2: Outside lock - cancel scope and wait for coroutine to finish
        awaitPlaybackLoopCancellation(captured)

        // Quit the audio HandlerThread only after the playback coroutine has
        // drained off its Looper. Quitting earlier would orphan pending
        // messages and risk IllegalStateException on subsequent post().
        audioThread.quitSafely()

        // Phase 3: Re-acquire lock for final resource cleanup
        stateLock.withLock {
            isFlushPending.set(false)  // Clear any pending flush since we're releasing
            // Release AudioTrack
            try {
                audioSink?.stop()
            } catch (e: IllegalStateException) {
                // AudioTrack may already be stopped
                AppLog.Audio.v("AudioTrack already stopped during release")
            }
            audioSink?.release()
            audioSink = null

            // Clear all buffers and state
            discardQueuedAudio()
            synchronized(pendingChunks) {
                pendingChunks.clear()
                hasPendingChunks = false
            }
            stateCallback = null

            AppLog.Audio.i("Released")
        }
    }

    /**
     * Clear the audio buffer (called on stream/clear or seek).
     *
     * This method is thread-safe. It pauses the playback loop during the clear
     * to prevent concurrent access issues.
     */
    fun clearBuffer() {
        if (isReleased.get()) {
            AppLog.Audio.w("Cannot clear buffer - player has been released")
            return
        }

        stateLock.withLock {
            streamGeneration++

            AppLog.Audio.i("[cmd-trace] T4 clearBuffer ts=${nowNs() / 1_000_000} thread=${Thread.currentThread().name} gen=$streamGeneration")

            // Reset paused state - we're starting a fresh stream (e.g., after seek)
            // This ensures playback loop will process new chunks even if we were paused
            val wasPaused = isPaused.getAndSet(false)

            // Clear the chunk queue (thread-safe operation)
            discardQueuedAudio()

            // Clear pending chunks buffer
            synchronized(pendingChunks) {
                pendingChunks.clear()
                hasPendingChunks = false
            }

            // Signal the playback loop to flush AudioTrack before its next write.
            // We must NOT flush here because the playback loop may be mid-write()
            // on the coroutine thread -- concurrent pause()/flush() causes clicks/pops
            // and incorrect frame accounting (H-11).
            if (audioSink != null && isPlaying.get()) {
                isFlushPending.set(true)
            } else {
                // Not playing -- safe to flush directly (no concurrent writes)
                val track = audioSink
                if (track != null) {
                    try {
                        track.flush()
                    } catch (e: IllegalStateException) {
                        AppLog.Audio.w("Failed to flush AudioTrack during clearBuffer", e)
                    }
                }
            }

            // Ensure AudioTrack hardware matches software state after clearing pause flag
            if (wasPaused) {
                audioSink?.play()
            }

            lastChunkServerTime = 0L

            // Reset playback state machine
            setPlaybackState(PlaybackState.INITIALIZING)
            scheduledStartLoopTimeUs = null
            firstServerTimestampUs = null
            // Note: lastReanchorTimeUs is NOT reset to maintain cooldown across clears

            // Reset sync error tracking (decoupled architecture)
            totalFramesWritten.set(0)
            serverTimelineCursor = 0L
            serverTimelineCursorRemainder = 0L
            playbackStartTimeUs = 0L
            startTimeCalibrated = false
            samplesReadSinceStart = 0L
            syncErrorUs = 0L
            syncErrorFilter.reset()
            playingStateEnteredAtUs = 0L  // Reset grace period

            // Reset DAC timestamp stability tracking
            consecutiveValidTimestamps = 0
            dacTimestampsStable = false
            lastDacPacingLogTimeUs = 0L

            // Reset sample insert/drop correction state
            insertEveryNFrames = 0
            dropEveryNFrames = 0
            snapDropFrames = 0

            // Reset gap/overlap tracking
            expectedNextTimestampUs = null

            AppLog.Audio.d("Buffer cleared, generation=$streamGeneration, state=$playbackState")
        }
    }

    /**
     * Evict oldest chunks from the queue if adding [newSamples] would exceed the
     * capacity limit. Only active when [maxQueueSamples] > 0.
     */
    private fun evictIfOverCapacity(newSamples: Long) {
        if (maxQueueSamples <= 0) return
        while (totalQueuedSamples.get() + newSamples > maxQueueSamples) {
            val evicted = chunkQueue.poll() ?: break
            totalQueuedSamples.addAndGet(-evicted.sampleCount.toLong())
            queueCapDrops++
            if (queueCapDrops % 100 == 1) {
                AppLog.Audio.w("Queue capacity limit reached ($maxQueueSamples samples), " +
                    "dropping oldest chunks (total drops: $queueCapDrops)")
            }
        }
    }

    /**
     * Queue an audio chunk for playback.
     *
     * Handles gaps and overlaps in the audio stream following the Python reference:
     * - Gaps: Insert silence to fill gaps larger than GAP_THRESHOLD_US
     * - Overlaps: Trim the start of chunks that overlap with already-queued audio
     *
     * @param serverTimeMicros Server timestamp when this audio should play
     * @param pcmData Raw PCM audio data
     */
    fun queueChunk(serverTimeMicros: Long, pcmData: ByteArray) {
        if (isReleased.get()) return
        chunksReceived++

        // Buffer chunks until time sync is ready
        if (!timeFilter.isReady) {
            synchronized(pendingChunks) {
                if (pendingChunks.size < MAX_PENDING_CHUNKS) {
                    pendingChunks.add(Pair(serverTimeMicros, pcmData))
                    hasPendingChunks = true
                    if (pendingChunks.size == 1) {
                        AppLog.Audio.d("Buffering chunks while waiting for time sync...")
                    }
                } else {
                    val dropped = chunksDropped.incrementAndGet()  // Only drop if buffer is full
                    if (dropped % CHUNK_DROP_LOG_INTERVAL == 1L) {
                        AppLog.Audio.w("Pending buffer full, dropping chunk (dropped: $dropped)")
                    }
                }
            }
            return
        }

        // Process any pending chunks first (once time sync is ready)
        processPendingChunks()

        // Now process the current chunk
        processChunk(serverTimeMicros, pcmData)
    }

    /**
     * Process pending chunks that were buffered while waiting for time sync.
     * Called when time sync becomes ready.
     *
     * Drains `pendingChunks` under its monitor and then processes the drained
     * snapshot OUTSIDE the monitor. This is required because [processChunk]
     * acquires `stateLock`, while `stop()`, `clearBuffer()`, `enterIdle()`,
     * and `release()` acquire `stateLock` BEFORE `synchronized(pendingChunks)`.
     * Holding `pendingChunks` across a `stateLock` acquisition would create a
     * lock-order inversion and a potential deadlock.
     */
    private fun processPendingChunks() {
        // Lock-free fast path: the overwhelming steady-state case (sync ready,
        // buffer already drained) avoids the monitor entirely.
        if (!hasPendingChunks) return

        val drained: List<Pair<Long, ByteArray>>
        synchronized(pendingChunks) {
            if (pendingChunks.isEmpty()) {
                hasPendingChunks = false
                return
            }
            AppLog.Audio.i("Time sync ready, processing ${pendingChunks.size} buffered chunks")
            drained = pendingChunks.toList()
            pendingChunks.clear()
            hasPendingChunks = false
        }

        // processChunk() acquires stateLock - MUST be called outside the
        // synchronized(pendingChunks) block above.
        for ((timestamp, data) in drained) {
            processChunk(timestamp, data)
        }
    }

    /**
     * Process a single audio chunk (internal implementation).
     * Handles gap/overlap detection and state machine transitions.
     *
     * Called from the WebSocket thread. Captures streamGeneration at entry
     * and rechecks before state transitions to avoid racing with clearBuffer()/stop().
     */
    private fun processChunk(serverTimeMicros: Long, pcmData: ByteArray) {
        // Snapshot generation to detect concurrent clearBuffer()/stop() calls.
        // If generation changes mid-processing, this chunk belongs to a stale stream.
        val gen = streamGeneration

        // Working copies that may be modified by gap/overlap handling
        var workingServerTimeMicros = serverTimeMicros
        var workingPcmData = pcmData

        // Initialize expected next timestamp on first chunk
        val expectedNext = expectedNextTimestampUs
        if (expectedNext == null) {
            expectedNextTimestampUs = serverTimeMicros
        } else {
            // Handle gap: insert silence to fill the gap
            if (serverTimeMicros > expectedNext) {
                val gapUs = serverTimeMicros - expectedNext

                if (gapUs > MAX_SILENCE_GAP_US) {
                    // Gap is too large to bridge with silence (likely a stale
                    // expectedNextTimestampUs after a long disconnect/drain).
                    // Discard the stale anchor and let this chunk re-seed.
                    AppLog.Audio.w(
                        "Gap ${gapUs / 1000}ms exceeds MAX_SILENCE_GAP_US " +
                            "(${MAX_SILENCE_GAP_US / 1000}ms); reseeding timeline " +
                            "without silence insertion"
                    )
                    expectedNextTimestampUs = serverTimeMicros
                } else if (gapUs > GAP_THRESHOLD_US) {
                    // Only fill gaps larger than threshold (small gaps are normal network jitter)
                    val gapFrames = ((gapUs * sampleRate) / 1_000_000).toInt()
                    val silenceBytes = gapFrames * bytesPerFrame
                    val silenceData = ByteArray(silenceBytes)  // Zeros = silence

                    val silenceChunk = AudioChunk(
                        serverTimeMicros = expectedNext,
                        pcmData = silenceData,
                        sampleCount = gapFrames
                    )
                    evictIfOverCapacity(gapFrames.toLong())
                    chunkQueue.add(silenceChunk)
                    totalQueuedSamples.addAndGet(gapFrames.toLong())

                    // Update statistics
                    gapsFilled++
                    val gapMs = gapUs / 1000
                    gapSilenceMs += gapMs

                    // Update expected next timestamp to account for inserted silence
                    val silenceDurationUs = (gapFrames * 1_000_000L) / sampleRate
                    expectedNextTimestampUs = expectedNext + silenceDurationUs
                }
            }
            // Handle overlap: trim the start of the chunk
            else if (serverTimeMicros < expectedNext) {
                val overlapUs = expectedNext - serverTimeMicros
                val overlapFrames = ((overlapUs * sampleRate) / 1_000_000).toInt()
                val trimBytes = overlapFrames * bytesPerFrame

                if (trimBytes < workingPcmData.size) {
                    // Trim the overlapping portion from the start
                    workingPcmData = workingPcmData.copyOfRange(trimBytes, workingPcmData.size)
                    workingServerTimeMicros = expectedNext

                    // Update statistics
                    overlapsTrimmed++
                    val overlapMs = overlapUs / 1000
                    overlapTrimmedMs += overlapMs
                } else {
                    // Entire chunk is overlap - skip it entirely
                    overlapsTrimmed++
                    overlapTrimmedMs += overlapUs / 1000
                    return
                }
            }
        }

        // Calculate sample count for the (possibly trimmed) chunk
        val sampleCount = workingPcmData.size / bytesPerFrame

        // Skip empty chunks. Two legitimate sources:
        //   1. Opus/FLAC decoder produces no PCM on its first few frames while
        //      it buffers internally.
        //   2. Trim leaves a partial-frame remainder smaller than one frame.
        // We still must advance expectedNextTimestampUs so the next chunk
        // isn't treated as a one-chunk-sized gap and inject spurious silence;
        // estimate this chunk's duration from server-time cadence (delta from
        // the previous chunk's start, which is still in lastChunkServerTime).
        // Cap the estimate: after a large gap (the MAX_SILENCE_GAP_US reseed
        // above, or a DRAINING/exitDraining transition that leaves
        // lastChunkServerTime pointing pre-disconnect), the raw delta would
        // push expectedNextTimestampUs seconds ahead and cause real follow-on
        // chunks to be overlap-trimmed to zero -- dropping seconds of audio.
        if (sampleCount == 0 || workingPcmData.isEmpty()) {
            val deltaUs = serverTimeMicros - lastChunkServerTime
            val estimatedDurationUs = if (lastChunkServerTime in 1 until serverTimeMicros &&
                deltaUs <= MAX_PLAUSIBLE_CHUNK_US) {
                deltaUs
            } else {
                0L
            }
            // Base the prediction on the ORIGINAL (untrimmed) serverTimeMicros,
            // not workingServerTimeMicros: an overlap-trim above may have bumped
            // workingServerTimeMicros forward to expectedNext, and the cadence
            // delta was measured against the original server timeline. Using the
            // trimmed value double-counts the overlap and spuriously trims the
            // next real chunk.
            expectedNextTimestampUs = serverTimeMicros + estimatedDurationUs
            lastChunkServerTime = serverTimeMicros
            return
        }

        // Check for large discontinuity (new stream or seek) - for logging only
        if (lastChunkServerTime > 0) {
            val serverGap = serverTimeMicros - lastChunkServerTime
            val expectedGapUs = (pcmData.size.toLong() / bytesPerFrame) * microsPerSample.toLong()

            // If gap is more than threshold different from expected, log it
            if (abs(serverGap - expectedGapUs) > DISCONTINUITY_THRESHOLD_US) {
                AppLog.Audio.w("Discontinuity detected: gap=${serverGap}us, expected=${expectedGapUs}us")
            }
        }
        lastChunkServerTime = serverTimeMicros

        val clientPlayTime = timeFilter.serverToClient(workingServerTimeMicros)

        val chunk = AudioChunk(
            serverTimeMicros = workingServerTimeMicros,
            pcmData = workingPcmData,
            sampleCount = sampleCount
        )
        evictIfOverCapacity(sampleCount.toLong())
        chunkQueue.add(chunk)
        totalQueuedSamples.addAndGet(sampleCount.toLong())

        // Update expected next timestamp based on this chunk's duration
        val chunkDurationUs = (sampleCount * 1_000_000L) / sampleRate
        expectedNextTimestampUs = workingServerTimeMicros + chunkDurationUs

        // ====================================================================
        // State Machine Transitions in queueChunk()
        // ====================================================================
        // This is where incoming audio chunks trigger state transitions.
        // The key transitions here are:
        //   INITIALIZING -> WAITING_FOR_START (first chunk establishes timing)
        //   REANCHORING  -> WAITING_FOR_START (recovery from large sync error)
        //
        // Held under stateLock to avoid racing with clearBuffer()/stop() which
        // reset the state machine on the main thread. The generation check
        // ensures we don't apply stale chunk timing after a stream reset.
        //
        // See PlaybackState enum for the complete state diagram.
        // ====================================================================
        stateLock.withLock {
            // If clearBuffer()/stop() ran since we entered processChunk(),
            // this chunk belongs to a stale stream -- skip the transition.
            if (streamGeneration != gen) return

            when (playbackState) {
                PlaybackState.INITIALIZING -> {
                    // TRANSITION: INITIALIZING -> WAITING_FOR_START
                    // Trigger: First audio chunk received while time sync is ready
                    // Action: Record the first chunk's server timestamp as anchor point,
                    //         compute scheduled client-time start, begin buffer filling
                    firstServerTimestampUs = workingServerTimeMicros
                    scheduledStartLoopTimeUs = clientPlayTime
                    setPlaybackState(PlaybackState.WAITING_FOR_START)
                    AppLog.Audio.i("First chunk received: serverTime=${workingServerTimeMicros/1000}ms, " +
                            "scheduled start at ${clientPlayTime/1000}ms, transitioning to WAITING_FOR_START")
                }
                PlaybackState.WAITING_FOR_START -> {
                    // NO TRANSITION - Still in WAITING_FOR_START
                    // Action: Update scheduled start time as time sync improves.
                    // The time filter's offset estimate improves with more samples,
                    // so we recompute the client play time using the original server timestamp.
                    // This ensures the scheduled start aligns with the corrected time sync.
                    val firstTs = firstServerTimestampUs
                    if (firstTs != null) {
                        scheduledStartLoopTimeUs = timeFilter.serverToClient(firstTs)
                    }
                    // Actual transition to PLAYING happens in playback loop's handleStartGating()
                    // when buffer >= 200ms AND scheduled start time is reached.
                }
                PlaybackState.REANCHORING -> {
                    // TRANSITION: REANCHORING -> WAITING_FOR_START
                    // Trigger: New chunk arrives after reanchor cleared all buffers
                    // Action: Treat this as the new "first" chunk, establish new timing anchor.
                    // This completes the reanchor recovery - we have fresh timing reference.
                    firstServerTimestampUs = workingServerTimeMicros
                    scheduledStartLoopTimeUs = clientPlayTime
                    setPlaybackState(PlaybackState.WAITING_FOR_START)
                    AppLog.Sync.i("Reanchoring: new first chunk at serverTime=${workingServerTimeMicros/1000}ms")
                }
                PlaybackState.PLAYING -> {
                    // NO TRANSITION - Normal chunk processing: chunks added to
                    // queue for playback.
                }
            }
        }

    }

    // ========================================================================
    // Start Gating and Reanchoring (from Python reference)
    // ========================================================================

    /**
     * Reset sync baselines for a fresh playback start.
     *
     * Called when transitioning to PLAYING from handleStartGating() to set up
     * clean timing anchors. Deduplicates the reset code that was previously
     * repeated in the "late" and "on-time" start gating paths.
     *
     * @param nowMicros Current system time in microseconds (System.nanoTime() / 1000)
     */
    private fun resetSyncBaselines(nowMicros: Long) {
        playbackStartTimeUs = nowMicros
        startTimeCalibrated = false
        samplesReadSinceStart = 0L
        syncErrorUs = 0L
        syncErrorFilter.reset()
    }

    /**
     * Handle start gating - decide when and where to begin playback.
     *
     * Two paths:
     * 1. **DAC-aware** (preferred): If AudioTrack timestamps are stable, use the
     *    hardware DAC position to align the queue head to the write cursor in
     *    one shot.
     * 2. **Kalman fallback**: If timestamps are not yet stable, use the existing
     *    Kalman-predicted `scheduledStartLoopTimeUs` approach.
     *
     * @return true if we should continue waiting, false if ready to play
     */
    private fun handleStartGating(): Boolean {
        val track = audioSink
        if (track != null && dacTimestampsStable) {
            return handleStartGatingDacAware(track)
        }
        return handleStartGatingKalman()
    }

    /**
     * DAC-position-aware start gating.
     *
     * Uses AudioTrack.getTimestamp() to determine when a frame written now
     * will reach the DAC, and starts the queue head exactly there (spec:
     * "Large errors and startup"): it waits while the head chunk is not due
     * yet, pads the last few milliseconds with silence, and drops the leading
     * audio that is already late. The playback loop keeps the track fed with
     * silence meanwhile, so its timestamps stay live.
     *
     * @return true if we should continue waiting, false if ready to play
     */
    private fun handleStartGatingDacAware(track: AudioSink): Boolean {
        val nowMicros = nowNs() / 1000
        val headChunk = chunkQueue.peek() ?: return true  // No chunks yet, keep waiting

        // What server time will a frame written now reach the DAC at?
        val dacTimeUs = dacTimeOfNextWriteUs(track)
        if (dacTimeUs == null) {
            // Timestamp read failed despite being "stable" -- fall back to Kalman
            AppLog.Sync.w("DAC-aware start: no usable DAC timestamp, falling back to Kalman")
            return handleStartGatingKalman()
        }
        val writeCursorServerUs = timeFilter.clientToServer(dacTimeUs)

        // How far is the queue head from the write cursor?
        // Positive = head chunk is not due yet, Negative = head chunk is late (stale)
        val startErrUs = headChunk.serverTimeMicros - writeCursorServerUs

        if (startErrUs > START_PAD_MAX_US) {
            // Queue head is not due yet -- wait while the playback loop feeds
            // the track silence.
            //
            // This branch runs every playback-loop iteration (~10ms) while we wait.
            // Rate-limit the log: first-time entry, then once per second progress,
            // then an exit log on transition to PLAYING. See alignmentWait* fields.
            if (alignmentWaitStartedAtUs == 0L) {
                alignmentWaitStartedAtUs = nowMicros
                alignmentWaitLastLoggedUs = nowMicros
                AppLog.Sync.d("DAC-aware start: waiting for alignment, startErr=${startErrUs/1000}ms > ${START_PAD_MAX_US/1000}ms")
            } else if (nowMicros - alignmentWaitLastLoggedUs > 1_000_000L) {
                alignmentWaitLastLoggedUs = nowMicros
                val elapsedMs = (nowMicros - alignmentWaitStartedAtUs) / 1000
                AppLog.Sync.d("DAC-aware start: still waiting, startErr=${startErrUs/1000}ms, elapsed=${elapsedMs}ms")
            }
            return true
        }

        if (startErrUs < 0) {
            // Head chunk is late -- drop the chunks that are late in full
            var droppedFrames = 0
            var droppedChunks = 0

            while (true) {
                val chunk = chunkQueue.peek() ?: break
                val chunkEndUs = chunk.serverTimeMicros + (chunk.sampleCount * 1_000_000L) / sampleRate
                if (chunkEndUs > writeCursorServerUs) break  // Part of this chunk is still due

                chunkQueue.poll()
                totalQueuedSamples.addAndGet(-chunk.sampleCount.toLong())
                droppedFrames += chunk.sampleCount
                droppedChunks++
                chunksDropped.incrementAndGet()
            }

            framesDropped += droppedFrames.toLong()
            AppLog.Sync.d("DAC-aware start: dropped $droppedChunks stale chunks ($droppedFrames frames)")
        }

        // Update timing anchor to actual queue head
        val alignedHead = chunkQueue.peek()
        if (alignedHead == null) {
            // Dropped everything -- wait for more chunks
            AppLog.Sync.w("DAC-aware start: all chunks were stale, waiting for more")
            return true
        }

        // One-shot alignment of the head chunk to the write cursor: silence up
        // to its start time if early, or drop its late leading frames.
        val finalErr = alignedHead.serverTimeMicros - writeCursorServerUs
        val alignFrames = (abs(finalErr) * sampleRate) / 1_000_000
        if (finalErr >= 0) {
            writeSilence(track, alignFrames)
        } else {
            snapDropFrames = alignFrames
        }

        firstServerTimestampUs = alignedHead.serverTimeMicros
        scheduledStartLoopTimeUs = timeFilter.serverToClient(alignedHead.serverTimeMicros)

        resetSyncBaselines(nowMicros)

        // Diagnostic logging
        val bufferedMs = (totalQueuedSamples.get() * 1000) / sampleRate
        AppLog.Sync.i("DAC-aware start gating transition: " +
            "startErr=${startErrUs/1000}ms, finalErr=${finalErr}us, " +
            "firstServerTs=${firstServerTimestampUs}us, " +
            "kalmanOffset=${timeFilter.offsetMicros/1000}ms, " +
            "kalmanMeasurements=${timeFilter.measurementCountValue}, " +
            "bufferedChunks=${chunkQueue.size}, bufferedMs=$bufferedMs")

        // Exit log for the rate-limited alignment-wait path: emit total elapsed
        // so ops can see how long the DAC took to catch up. Reset both fields
        // so the next alignment cycle (e.g. after a track change) starts fresh.
        if (alignmentWaitStartedAtUs != 0L) {
            val waitElapsedMs = (nowMicros - alignmentWaitStartedAtUs) / 1000
            AppLog.Sync.i("DAC-aware start: alignment complete after ${waitElapsedMs}ms wait")
            alignmentWaitStartedAtUs = 0L
            alignmentWaitLastLoggedUs = 0L
        }

        setPlaybackState(PlaybackState.PLAYING)
        AppLog.Sync.i("DAC-aware start gating complete: now PLAYING")
        return false
    }

    /**
     * Kalman-based start gating (original behavior, used as fallback).
     *
     * Waits for `scheduledStartLoopTimeUs` (computed from Kalman filter) before
     * transitioning to PLAYING. If we're late, drops frames to catch up.
     *
     * @return true if we should continue waiting, false if ready to play
     */
    private fun handleStartGatingKalman(): Boolean {
        val scheduledStart = scheduledStartLoopTimeUs ?: return false
        val nowMicros = nowNs() / 1000
        val deltaUs = scheduledStart - nowMicros

        when {
            deltaUs > 0 -> {
                // Not yet time to start - AudioTrack is already playing silence
                return true  // Keep waiting
            }
            deltaUs < -HARD_RESYNC_THRESHOLD_US -> {
                // We're very late - need to drop frames to catch up
                val framesToDrop = ((-deltaUs * sampleRate) / 1_000_000).toInt()
                var droppedFrames = 0

                AppLog.Sync.w("Kalman start gating: late by ${-deltaUs/1000}ms, dropping $framesToDrop frames")

                // Drop chunks until we've caught up
                while (droppedFrames < framesToDrop) {
                    val chunk = chunkQueue.peek() ?: break
                    val chunkFrames = chunk.sampleCount

                    if (droppedFrames + chunkFrames <= framesToDrop) {
                        chunkQueue.poll()
                        totalQueuedSamples.addAndGet(-chunk.sampleCount.toLong())
                        droppedFrames += chunkFrames
                        chunksDropped.incrementAndGet()
                    } else {
                        break
                    }
                }

                // Update timing anchors to match what we're actually playing
                val firstPlayableChunk = chunkQueue.peek()
                if (firstPlayableChunk != null) {
                    firstServerTimestampUs = firstPlayableChunk.serverTimeMicros
                    scheduledStartLoopTimeUs = timeFilter.serverToClient(firstPlayableChunk.serverTimeMicros)
                }

                resetSyncBaselines(nowNs() / 1000)

                framesDropped += droppedFrames.toLong()

                // Diagnostic logging
                val bufferedMs = (totalQueuedSamples.get() * 1000) / sampleRate
                AppLog.Sync.i("Kalman start gating transition (late): " +
                    "scheduledStart=${scheduledStartLoopTimeUs}us, now=${nowMicros}us, " +
                    "delta=${deltaUs/1000}ms, " +
                    "firstServerTs=${firstServerTimestampUs}us, " +
                    "kalmanOffset=${timeFilter.offsetMicros/1000}ms, " +
                    "kalmanMeasurements=${timeFilter.measurementCountValue}, " +
                    "bufferedChunks=${chunkQueue.size}, bufferedMs=$bufferedMs")

                setPlaybackState(PlaybackState.PLAYING)
                AppLog.Sync.i("Kalman start gating complete: dropped $droppedFrames frames, now PLAYING")
                return false
            }
            else -> {
                // Within tolerance - start playing
                val firstChunk = chunkQueue.peek()
                if (firstChunk != null && firstServerTimestampUs != firstChunk.serverTimeMicros) {
                    val oldServerTs = firstServerTimestampUs
                    firstServerTimestampUs = firstChunk.serverTimeMicros
                    scheduledStartLoopTimeUs = timeFilter.serverToClient(firstChunk.serverTimeMicros)
                    AppLog.Sync.d("Realigned timing anchor: serverTs ${oldServerTs}->${firstServerTimestampUs}")
                }

                resetSyncBaselines(nowNs() / 1000)

                // Diagnostic logging
                val bufferedMs = (totalQueuedSamples.get() * 1000) / sampleRate
                AppLog.Sync.i("Kalman start gating transition: " +
                    "scheduledStart=${scheduledStartLoopTimeUs}us, now=${nowMicros}us, " +
                    "delta=${deltaUs/1000}ms, " +
                    "firstServerTs=${firstServerTimestampUs}us, " +
                    "kalmanOffset=${timeFilter.offsetMicros/1000}ms, " +
                    "kalmanMeasurements=${timeFilter.measurementCountValue}, " +
                    "bufferedChunks=${chunkQueue.size}, bufferedMs=$bufferedMs")

                setPlaybackState(PlaybackState.PLAYING)
                AppLog.Sync.i("Kalman start gating complete: delta=${deltaUs/1000}ms, now PLAYING")
                return false
            }
        }
    }

    /**
     * Pre-calibrate DAC timing by writing silence during WAITING_FOR_START.
     *
     * This gets the DAC timestamps going before real audio arrives, making
     * sync error calculations reliable from the first measurement.
     *
     * Android's AudioTimestamp API requires ~21k frames (~443ms at 48kHz) to be
     * played before returning valid data. By actively writing silence during
     * the wait period, we can establish DAC calibration BEFORE real playback
     * begins, avoiding the large initial sync error (~848ms) that would otherwise
     * occur while waiting for calibration.
     */
    private fun preCalibrateDacTiming() {
        val track = audioSink ?: return

        // Write pre-allocated silence (10ms = 480 frames at 48kHz)
        val silenceBytes = silenceBuffer.size
        val written = writeToSink(track, silenceBuffer, 0, silenceBytes)
        if (written <= 0) return

        // CRITICAL: Track silence frames so sync error calculation is accurate
        // Without this, totalFramesWritten excludes pre-cal silence but framePosition
        // includes it, causing a mismatch that shows up as ~200ms initial sync error
        val framesWritten = written / bytesPerFrame
        totalFramesWritten.addAndGet(framesWritten.toLong())

        // Try to get DAC timestamp for stability tracking
        val ts = track.getTimestamp()
        if (ts != null) {
            // Only count usable timestamps (DAC has started, track is running)
            if (dacTimeOfNextWriteUs(track) != null) {
                // Track consecutive valid reads for DAC-aware start gating
                consecutiveValidTimestamps++
                if (consecutiveValidTimestamps >= TIMESTAMP_STABLE_READS && !dacTimestampsStable) {
                    dacTimestampsStable = true
                    AppLog.Sync.i("DAC timestamps stable after $consecutiveValidTimestamps consecutive reads")
                }
            } else {
                // Invalid framePosition resets stability counter
                consecutiveValidTimestamps = 0
            }
        } else {
            // getTimestamp() failed - reset stability counter
            consecutiveValidTimestamps = 0
        }
    }

    /**
     * Reduced-rate silence writer for keeping DAC timestamps warm once stable.
     *
     * Unlike preCalibrateDacTiming() which writes every loop iteration (10ms),
     * this only writes when the pending-to-DAC buffer drops below a threshold.
     * This saves CPU during long idle periods while keeping AudioTimestamp valid.
     */
    private fun writeSilenceKeepAlive() {
        val track = audioSink ?: return

        val pendingUs = getPendingToDacUs(track)
        if (pendingUs > SILENCE_KEEPALIVE_THRESHOLD_US) return

        // Top the buffer back up to the threshold plus one 10ms block. A fixed
        // 10ms per loop iteration is slightly less than real time, so the track
        // would drain and sit in permanent underrun. Without a timestamp
        // (pendingUs == 0) the depth is unknown, so write the one block only.
        val deficitUs = if (pendingUs > 0) SILENCE_KEEPALIVE_THRESHOLD_US - pendingUs else 0L
        writeSilence(track, (deficitUs * sampleRate) / 1_000_000 + silenceFrameCount)
    }

    /**
     * Discard all queued audio.
     *
     * Also re-arms the underrun latch: the emptiness we just caused is
     * deliberate, so a latch left set would swallow the next real underrun
     * event. Every queue-clearing site must go through here to keep that
     * invariant - clearing the queue by hand silently breaks the stat.
     */
    private fun discardQueuedAudio() {
        chunkQueue.clear()
        totalQueuedSamples.set(0)
        inUnderrun = false
    }

    /**
     * Reset all playback timing/sync/DAC state to a fresh-start baseline.
     *
     * Must be called under stateLock. The caller owns AudioTrack handling and the
     * surrounding state transitions; this only clears the software-side timing
     * state. Shared by [triggerReanchor] (same track) and [recoverFromSinkFailure]
     * (new track).
     */
    private fun resetPlaybackTimingState() {
        // Clear queued audio - its server timestamps predate the reset.
        discardQueuedAudio()

        // Reset start gating state
        scheduledStartLoopTimeUs = null
        firstServerTimestampUs = null

        // Reset sync tracking (simplified)
        lastChunkServerTime = 0L
        expectedNextTimestampUs = null   // drop the pre-reset chunk-gap anchor
        insertEveryNFrames = 0
        dropEveryNFrames = 0
        snapDropFrames = 0

        // Reset sync error state (decoupled architecture)
        totalFramesWritten.set(0)
        serverTimelineCursor = 0L
        serverTimelineCursorRemainder = 0L
        playbackStartTimeUs = 0L
        startTimeCalibrated = false
        samplesReadSinceStart = 0L
        syncErrorUs = 0L
        syncErrorFilter.reset()
        playingStateEnteredAtUs = 0L  // Reset grace period

        // Reset DAC timestamp stability tracking
        consecutiveValidTimestamps = 0
        dacTimestampsStable = false
    }

    /**
     * Write to the sink, converting any thrown exception into a negative result so
     * a transiently bad AudioTrack (HAL fault) cannot kill the playback loop.
     *
     * Tracks consecutive write failures (negative return codes such as
     * ERROR_DEAD_OBJECT) so the loop can decide to recreate the sink. The recreate
     * itself runs at the loop top via [recoverFromSinkFailure] -- a safe point with
     * no captured track reference -- never from inside a write.
     *
     * Playback-thread only.
     */
    private fun writeToSink(sink: AudioSink, buffer: ByteArray, offset: Int, size: Int): Int {
        val written = try {
            sink.write(buffer, offset, size)
        } catch (e: Exception) {
            AppLog.Audio.e("AudioTrack write threw: " + throwableSummary(e))
            // Any negative result counts as a write failure below. A thrown write is
            // not necessarily a dead object, so don't borrow AudioTrack.ERROR_DEAD_OBJECT
            // here -- the player operates over the AudioSink abstraction.
            -1
        }
        if (written > 0) {
            consecutiveWriteFailures = 0
        } else if (written < 0) {
            consecutiveWriteFailures++
        }
        return written
    }

    /**
     * Release and recreate the AudioTrack after repeated write failures.
     *
     * Adapted from sendspinlite's dead-track release+recreate: on Shield the
     * HDMI/AVR audio HAL can fault mid-stream (format/surround renegotiation) and
     * return ERROR_DEAD_OBJECT, which the old code only logged while the loop spun
     * forever on a dead track. Invoked from the playback loop top under tryLock so
     * the audio thread never blocks; on lock contention it retries next iteration.
     * Recovery count is capped to avoid recreate storms.
     */
    private fun recoverFromSinkFailure() {
        if (isReleased.get()) return
        if (!stateLock.tryLock()) return
        try {
            if (isReleased.get() || !isPlaying.get()) return

            // Park in INITIALIZING (silence keepalive) so the loop never
            // polls+discards real chunks on a dead/null sink.
            val restState = PlaybackState.INITIALIZING

            val nowUs = nowNs() / 1000

            // Backoff: a renegotiating HDMI/AVR HAL takes ~1-3s to settle. Spacing
            // attempts gives it time and stops a failing rebuild from re-firing every
            // loop tick (which would otherwise burn the whole budget in ~100ms).
            if (nowUs - lastSinkRecoveryAttemptUs < SINK_RECOVERY_BACKOFF_US) {
                setPlaybackState(restState)
                return
            }
            lastSinkRecoveryAttemptUs = nowUs

            // Windowed budget: recoveries older than the window no longer count, so a
            // long healthy stretch restores the budget and well-spaced faults over a
            // multi-hour session never exhaust it.
            if (nowUs - lastSinkRecoveryUs > SINK_RECOVERY_WINDOW_US) {
                sinkRecoveryCount = 0
            }
            lastSinkRecoveryUs = nowUs

            if (sinkRecoveryCount >= MAX_SINK_RECOVERIES) {
                // Persistent fault (too many recreates within the window). Give up and
                // surface it: onBufferExhausted() tears the player down and builds a
                // fresh one on the next stream (which resets this budget), instead of
                // wedging silently in PLAYING and discarding chunks forever.
                AppLog.Audio.e("AudioTrack recovery cap reached ($MAX_SINK_RECOVERIES within window); giving up")
                consecutiveWriteFailures = 0
                setPlaybackState(PlaybackState.INITIALIZING)
                stateCallback?.onBufferExhausted()
                return
            }
            sinkRecoveryCount++
            AppLog.Audio.e(
                "Recreating AudioTrack after $consecutiveWriteFailures consecutive write failures " +
                    "(recovery $sinkRecoveryCount/$MAX_SINK_RECOVERIES)"
            )

            val old = audioSink
            audioSink = null
            if (old != null) {
                try { old.stop() } catch (_: Exception) {}
                try { old.release() } catch (_: Exception) {}
            }

            val rebuilt = try {
                sinkFactory(sampleRate, channels, bitDepth, audioBufferSize)
            } catch (e: Exception) {
                AppLog.Audio.e("AudioTrack recreate failed: " + throwableSummary(e))
                // audioSink stays null. Park in restState (not PLAYING) so the loop
                // writes silence keepalive instead of polling+discarding chunks, and
                // retry after the backoff interval; consecutiveWriteFailures stays
                // high so the loop re-enters here, paced by the backoff gate above.
                setPlaybackState(restState)
                return
            }

            try {
                rebuilt.play()
            } catch (e: Exception) {
                AppLog.Audio.e("AudioTrack recreate play() failed: " + throwableSummary(e))
                try { rebuilt.release() } catch (_: Exception) {}
                setPlaybackState(restState)
                return
            }

            audioSink = rebuilt
            resetPlaybackTimingState()
            setPlaybackState(restState)
            consecutiveWriteFailures = 0
        } finally {
            stateLock.unlock()
        }
    }

    /**
     * Trigger a reanchor - reset sync state due to large error.
     *
     * Called when sync error exceeds REANCHOR_THRESHOLD_US.
     * Respects cooldown to avoid thrashing.
     *
     * Note: This is called from the playback loop, so we use tryLock to avoid
     * blocking if another thread holds the lock.
     *
     * @return true if reanchor was triggered, false if still in cooldown or lock unavailable
     */
    private fun triggerReanchor(): Boolean {
        val nowMicros = nowNs() / 1000
        val timeSinceLastReanchor = nowMicros - lastReanchorTimeUs

        if (timeSinceLastReanchor < REANCHOR_COOLDOWN_US) {
            return false
        }

        // Try to acquire the lock without blocking - if we can't, skip this reanchor attempt
        if (!stateLock.tryLock()) {
            return false
        }

        try {
            AppLog.Sync.w("Triggering reanchor: clearing buffers and resetting state")

            lastReanchorTimeUs = nowMicros
            setPlaybackState(PlaybackState.REANCHORING)

            // Flush the AudioTrack (keep the same device -- a reanchor recovers
            // timing, not the sink), then reset all timing/sync/DAC state.
            val track = audioSink
            if (track != null) {
                try {
                    track.pause()
                    track.flush()
                    track.play()
                } catch (e: IllegalStateException) {
                    AppLog.Sync.w("Failed to flush AudioTrack during reanchor", e)
                }
            }

            resetPlaybackTimingState()

            // Transition to INITIALIZING to wait for new chunks
            setPlaybackState(PlaybackState.INITIALIZING)
            syncCorrections++
            reanchorCount++

            return true
        } finally {
            stateLock.unlock()
        }
    }

    /**
     * Main playback loop that writes audio to AudioTrack at the correct time.
     *
     * Uses a state machine for start gating and sample insert/drop for sync correction.
     * This is imperceptible to the listener (no pitch/tempo changes).
     */
    private fun startPlaybackLoop() {
        val currentScope = scope ?: run {
            AppLog.Audio.e("Cannot start playback loop - scope is null")
            return
        }

        playbackJob = currentScope.launch {
            // Confirms per-device whether THREAD_PRIORITY_URGENT_AUDIO was
            // actually honored. Some OEMs clamp audio priorities for non-system
            // apps; logging the effective tid/priority makes field triage
            // deterministic. On a healthy device we expect priority = -19.
            val tid = Process.myTid()
            AppLog.Audio.i(
                "Playback loop thread: name=${Thread.currentThread().name} " +
                    "tid=$tid priority=${Process.getThreadPriority(tid)}"
            )
            AppLog.Audio.d("Playback loop started, initial state=$playbackState")

            while (isActive && isPlaying.get()) {
                if (isPaused.get()) {
                    delay(STATE_POLL_DELAY_MS)
                    continue
                }

                // Handle deferred flush from clearBuffer()/enterIdle().
                // Performed here (on the playback thread) rather than on the
                // main thread to avoid flushing mid-write (H-11).
                if (isFlushPending.compareAndSet(true, false)) {
                    val track = audioSink
                    if (track != null) {
                        try {
                            track.pause()
                            track.flush()
                            track.play()
                        } catch (e: IllegalStateException) {
                            AppLog.Audio.w("Failed to flush AudioTrack (deferred)", e)
                        }
                    }
                    // The flush restarts the track's frame position from zero.
                    // Restart our count here, on the thread that writes, so a
                    // write that raced the clear cannot leave the two apart.
                    totalFramesWritten.set(0)
                    consecutiveValidTimestamps = 0
                    dacTimestampsStable = false
                }

                // Recreate the AudioTrack if writes have been failing (dead HAL).
                // Done at the loop top -- a safe point with no captured track
                // reference -- never from inside a write.
                if (consecutiveWriteFailures >= SINK_FAILURE_RECOVERY_THRESHOLD) {
                    recoverFromSinkFailure()
                    delay(STATE_POLL_DELAY_MS)
                    continue
                }

                // State machine for synchronized playback
                when (playbackState) {
                    PlaybackState.INITIALIZING -> {
                        // Write silence to keep DAC timestamps warm while waiting
                        // for first chunk. Once stable, reduced-rate keepalive.
                        if (!dacTimestampsStable) {
                            preCalibrateDacTiming()
                        } else {
                            writeSilenceKeepAlive()
                        }
                        delay(STATE_POLL_DELAY_MS)
                        continue
                    }

                    PlaybackState.WAITING_FOR_START -> {
                        // Check if we have enough buffer before starting
                        // Duration check alone is sufficient -- the old chunk count gate
                        // (MIN_CHUNKS_BEFORE_START=16) added unnecessary delay and is now
                        // replaced by DAC timestamp stability tracking in preCalibrateDacTiming()
                        val bufferedMs = (totalQueuedSamples.get() * 1000) / sampleRate
                        if (bufferedMs < MIN_BUFFER_BEFORE_START_MS || handleStartGating()) {
                            // Still waiting for buffer or for the scheduled start.
                            // Keep the track fed with silence throughout: a track
                            // left to underrun stalls with its timestamps frozen,
                            // and a start aligned against those is off by however
                            // long it sat idle.
                            if (!dacTimestampsStable) {
                                preCalibrateDacTiming()
                            } else {
                                writeSilenceKeepAlive()
                            }
                            delay(STATE_POLL_DELAY_MS)
                            continue
                        }
                        // handleStartGating() transitioned us to PLAYING
                    }

                    PlaybackState.REANCHORING -> {
                        // Write silence to keep DAC timestamps warm while waiting
                        // for new chunks after reanchor
                        if (!dacTimestampsStable) {
                            preCalibrateDacTiming()
                        } else {
                            writeSilenceKeepAlive()
                        }
                        delay(STATE_POLL_DELAY_MS)
                        continue
                    }

                    PlaybackState.PLAYING -> {
                        // Normal playback - handled below
                    }
                }

                // PLAYING state: process chunks with sync correction
                val chunk = chunkQueue.peek()
                if (chunk == null) {
                    // No chunks available - buffer underrun
                    // Fork: edge-triggered -- count underrun *events*, not poll
                    // iterations (the loop re-polls every BUFFER_EMPTY_DELAY_MS).
                    if (!inUnderrun) {
                        inUnderrun = true
                        bufferUnderrunCount++
                        AppLog.Audio.w("Buffer underrun: queue empty during playback")
                    }
                    // The track may stall now. Start the sync error estimate over
                    // and hold corrections until it has settled on fresh readings.
                    syncErrorFilter.reset()
                    playingStateEnteredAtUs = nowNs() / 1000
                    delay(BUFFER_EMPTY_DELAY_MS)
                    continue
                }
                // Queue has audio again - re-arm the underrun edge.
                inUnderrun = false

                // Pending-to-DAC pacing: only mechanism needed for write timing.
                // The Python CLI uses a pull/callback model (audio system requests
                // frames); on Android we push, so we pace writes by keeping the
                // AudioTrack ring buffer at a target depth. This replaces the old
                // effectiveLead scheduling which drifted due to Kalman offset changes
                // between chunk-queue time and chunk-play time.
                //
                // Snapshot audioSink into a local: release() can null the field
                // between the non-null check and a `!!` dereference (it runs on
                // another thread and only awaits the playback loop cancellation
                // with a timeout), so the second read could fault.
                val sinkForPacing = audioSink
                val pendingToDacUs = if (sinkForPacing != null && dacTimestampsStable)
                    getPendingToDacUs(sinkForPacing) else 0L

                // Rate-limited DAC pacing diagnostics. The watchdog shares this
                // cadence so stuck-state warnings come out on the same log tick.
                val nowMicros = nowNs() / 1000
                checkStuckState(nowMicros)
                if (dacTimestampsStable && nowMicros - lastDacPacingLogTimeUs > DAC_PACING_LOG_INTERVAL_US) {
                    lastDacPacingLogTimeUs = nowMicros
                    AppLog.Sync.d("DAC pacing: pending=${pendingToDacUs/1000}ms, syncErr=${syncErrorUs}us, " +
                        "smoothed=${syncErrorFilter.offsetMicros}us, dropped=$framesDropped, inserted=$framesInserted")
                }

                // Pause writing when the AudioTrack buffer is sufficiently full
                if (dacTimestampsStable && pendingToDacUs > TARGET_PENDING_US + PENDING_TOL_US) {
                    delay(STATE_POLL_DELAY_MS)
                    continue
                }

                // Reanchor if sync error is extremely large (e.g. after long pause/seek)
                if (startTimeCalibrated && abs(syncErrorUs) > REANCHOR_THRESHOLD_US) {
                    AppLog.Sync.w("Large sync error: ${syncErrorUs/1000}ms, considering reanchor")
                    if (triggerReanchor()) {
                        continue
                    }
                }

                // Normal playback: update correction schedule and write chunk
                playChunkWithCorrection(chunk)
            }

            AppLog.Audio.d("Playback loop ended")
        }
    }

    /**
     * Watchdog invoked once per stats-log cycle. Warns if the state machine
     * has been in a non-PLAYING state for more than STUCK_STATE_WARNING_US
     * while chunks are arriving (indicating the pipeline is wedged, not
     * just idle).
     *
     * Diagnostic only -- no recovery action.
     */
    private fun checkStuckState(nowUs: Long) {
        val state = playbackState

        if (state != lastObservedState) {
            lastObservedState = state
            stuckStateEnteredAtUs = nowUs
            return
        }

        if (state == PlaybackState.PLAYING) return

        val stuckUs = nowUs - stuckStateEnteredAtUs
        if (stuckUs < STUCK_STATE_WARNING_US) return

        // Don't spam when there's no audio backlog -- that's a genuinely
        // idle state (e.g. user paused), not a deadlock.
        if (totalQueuedSamples.get() == 0L) return

        // Rate-limit: once warned, stay quiet for STUCK_STATE_WARNING_INTERVAL_US.
        // The `lastStuckWarningAtUs != 0L` guard ensures the first warning always
        // fires -- otherwise `nowUs - 0` is trivially small and the watchdog
        // would silently suppress its very first report.
        if (lastStuckWarningAtUs != 0L &&
            nowUs - lastStuckWarningAtUs < STUCK_STATE_WARNING_INTERVAL_US
        ) return
        lastStuckWarningAtUs = nowUs

        val bufferedMs = (totalQueuedSamples.get() * 1000) / sampleRate
        AppLog.Audio.w(
            "WATCHDOG: state=$state stuck for ${stuckUs / 1000}ms, " +
                "buffered=${bufferedMs}ms, chunks=${chunkQueue.size}, " +
                "dacTimestampsStable=$dacTimestampsStable"
        )
    }

    /**
     * Decide whether the next correction step drops or duplicates frames.
     *
     * Follows the spec's suggested strategy (roles/player/v1.md, "Sample
     * deletion and insertion"): outside a ~100us dead band, remove or repeat
     * [correctionFrames] frames (21us of audio) so the error shrinks. The
     * decision is taken once per [correctionIntervalFrames] (20ms), which
     * bounds the speed change to about 0.1% -- well inside the spec's +/-0.5%
     * over 150ms -- while still absorbing 1ms of drift per second.
     *
     * Sign convention: positive error = audio reaches the DAC late -> DROP,
     * negative error = early -> INSERT.
     */
    private fun updateCorrectionSchedule() {
        val errorUs = syncErrorFilter.offsetMicros
        val allowed = correctionsAllowed(nowNs() / 1000)
        dropEveryNFrames = if (allowed && errorUs > DEADBAND_THRESHOLD_US) correctionIntervalFrames else 0
        insertEveryNFrames = if (allowed && errorUs < -DEADBAND_THRESHOLD_US) correctionIntervalFrames else 0
    }

    /**
     * Corrections (soft or one-shot) are held back until a sync error has been
     * measured, and while timing is still settling after a start or an
     * underrun.
     */
    private fun correctionsAllowed(nowUs: Long): Boolean {
        if (!startTimeCalibrated) return false
        if (playingStateEnteredAtUs > 0 && nowUs - playingStateEnteredAtUs < STARTUP_GRACE_PERIOD_US) return false
        return true
    }

    /**
     * Write a chunk to AudioTrack, applying any pending one-shot resync and
     * the soft drop/duplicate correction.
     */
    private fun playChunkWithCorrection(chunk: AudioChunk) {
        // Resolve the sink before dequeuing. audioSink can be nulled by a concurrent
        // release() (it awaits loop cancellation only with a timeout) or by recovery
        // on this thread; returning before poll() leaves the chunk queued instead of
        // silently dropping it and decrementing the count for audio never written.
        val track = audioSink ?: return

        chunkQueue.poll() // Remove from queue
        totalQueuedSamples.addAndGet(-chunk.sampleCount.toLong())

        // Track samples consumed for sync error calculation
        samplesReadSinceStart += chunk.sampleCount

        // Measure before writing; skipped while a one-shot drop is still being
        // applied, because the error it corrects is not gone yet.
        if (snapDropFrames == 0L) {
            updateSyncError(track, chunk)
        }

        // One-shot resync, late case: drop the leading frames we are behind by.
        val skipFrames = minOf(snapDropFrames, chunk.sampleCount.toLong()).toInt()
        snapDropFrames -= skipFrames
        framesDropped += skipFrames

        val written = writeWithCorrection(track, chunk.pcmData, skipFrames * bytesPerFrame)

        // Update frame tracking
        val framesWritten = written / bytesPerFrame
        totalFramesWritten.addAndGet(framesWritten.toLong())

        // Update server timeline cursor - tracks input frames CONSUMED (read side).
        // Initialize from chunk's server timestamp on first chunk, then advance
        // by input frames consumed. This matches Python CLI's _server_ts_cursor_us.
        if (serverTimelineCursor == 0L) {
            serverTimelineCursor = chunk.serverTimeMicros
        }
        // Input frames consumed = chunk sample count (all input frames are read).
        // Drops consume extra input without outputting (already counted in sampleCount).
        // Inserts output extra without consuming input (don't affect sampleCount).
        advanceServerCursorFrames(chunk.sampleCount)

        chunksPlayed++
    }

    /**
     * Write PCM from [startOffset], dropping or duplicating [correctionFrames]
     * frames at most once per [correctionIntervalFrames].
     *
     * Drop leaves out the last frames of a slice so its neighbours abut;
     * duplicate repeats the last frame of the slice. Everything else is
     * written bit-exact.
     *
     * @return Total bytes written to AudioTrack
     */
    private fun writeWithCorrection(track: AudioSink, pcmData: ByteArray, startOffset: Int): Int {
        var offset = startOffset
        var totalWritten = 0

        while (pcmData.size - offset >= bytesPerFrame) {
            val frames = minOf(
                (pcmData.size - offset) / bytesPerFrame,
                correctionIntervalFrames - framesSinceCorrection
            )
            framesSinceCorrection += frames
            var size = frames * bytesPerFrame
            var duplicates = 0

            if (framesSinceCorrection >= correctionIntervalFrames) {
                framesSinceCorrection = 0
                updateCorrectionSchedule()
                // The filter is told about each step so it keeps estimating the
                // remaining error instead of lagging behind our own corrections.
                if (dropEveryNFrames > 0 && frames > correctionFrames) {
                    size -= correctionFrames * bytesPerFrame
                    framesDropped += correctionFrames
                    syncErrorFilter.shift(-correctionFrames * microsPerSample)
                } else if (insertEveryNFrames > 0) {
                    duplicates = correctionFrames
                    framesInserted += correctionFrames
                    syncErrorFilter.shift(correctionFrames * microsPerSample)
                }
            }

            var written = writeToSink(track, pcmData, offset, size)
            repeat(duplicates) {
                if (written >= 0) {
                    val dup = writeToSink(track, pcmData, offset + size - bytesPerFrame, bytesPerFrame)
                    written = if (dup < 0) dup else written + dup
                }
            }
            if (written < 0) {
                AppLog.Audio.e("AudioTrack write error: $written")
                break
            }
            totalWritten += written
            offset += frames * bytesPerFrame
        }

        return totalWritten
    }

    /** Write [frames] frames of silence, keeping the frame accounting in step. */
    private fun writeSilence(track: AudioSink, frames: Long) {
        var remaining = frames * bytesPerFrame
        while (remaining > 0) {
            val written = writeToSink(track, silenceBuffer, 0, minOf(remaining, silenceBuffer.size.toLong()).toInt())
            if (written <= 0) return
            totalFramesWritten.addAndGet((written / bytesPerFrame).toLong())
            remaining -= written
        }
    }

    // ========================================================================
    // Sync Error Calculation
    // ========================================================================

    /**
     * Measure the sync error of [chunk], which is about to be written, and
     * resync in one shot if it is beyond what soft correction should handle.
     *
     * The error is where the write cursor will reach the DAC, converted to
     * server time by the time filter, minus the chunk's own server timestamp.
     * Each chunk is measured against its own timestamp, so nothing is carried
     * over from the start of the stream that could hide a constant offset.
     *
     * Sign convention:
     *   Positive = chunk reaches the DAC late  -> need DROP
     *   Negative = chunk reaches the DAC early -> need INSERT
     *
     * One-shot resync (spec: "Large errors and startup"): once the smoothed
     * error is past the +/-1ms floor, drop a leading prefix equal to the error
     * if late, or write silence of the same duration if early. This reading
     * must agree with the estimate from the readings before it, so a single
     * bad timestamp cannot trigger it.
     */
    private fun updateSyncError(track: AudioSink, chunk: AudioChunk) {
        if (playbackState != PlaybackState.PLAYING) return
        val dacTimeUs = dacTimeOfNextWriteUs(track) ?: return
        val nowUs = nowNs() / 1000

        val errorUs = timeFilter.clientToServer(dacTimeUs) - chunk.serverTimeMicros
        syncErrorUs = errorUs
        startTimeCalibrated = true
        val smoothedUs = syncErrorFilter.offsetMicros
        syncErrorFilter.update(errorUs, nowUs)

        if (!correctionsAllowed(nowUs)) return
        val late = errorUs > SNAP_THRESHOLD_US && smoothedUs > SNAP_THRESHOLD_US
        val early = errorUs < -SNAP_THRESHOLD_US && smoothedUs < -SNAP_THRESHOLD_US
        if (!late && !early) return

        val frames = (abs(errorUs) * sampleRate) / 1_000_000
        if (late) {
            snapDropFrames = frames
        } else {
            writeSilence(track, frames)
            framesInserted += frames
        }
        AppLog.Sync.w("One-shot resync: error=${errorUs}us, smoothed=${smoothedUs}us, " +
            "${if (late) "dropping" else "inserting"} $frames frames")
        syncCorrections++
        syncErrorFilter.reset()
        playingStateEnteredAtUs = nowUs  // let the filter settle before correcting again
    }

    /**
     * Local time (microseconds on the System.nanoTime clock) at which the next
     * frame written to the track will reach the DAC, or null if there is no
     * usable AudioTimestamp.
     *
     * AudioTimestamp.nanoTime is already on the System.nanoTime clock, so it
     * is used as-is. A timestamp is not usable when the track has stalled
     * after an underrun (position and time are frozen, so extrapolating from
     * them is wrong) or when it still reports a position from before a flush.
     */
    private fun dacTimeOfNextWriteUs(track: AudioSink): Long? {
        val ts = track.getTimestamp() ?: return null
        if (ts.framePosition <= 0) return null
        val tsUs = ts.nanoTime / 1000
        if (nowNs() / 1000 - tsUs > TIMESTAMP_MAX_AGE_US) return null
        val pendingFrames = totalFramesWritten.get() - ts.framePosition
        if (pendingFrames < 0 || pendingFrames > track.bufferSizeInBytes / bytesPerFrame + sampleRate) return null
        return tsUs + (pendingFrames * 1_000_000L) / sampleRate
    }

    /**
     * Compute microseconds between AudioTrack write cursor and DAC output position.
     * Returns 0 if AudioTimestamp is unavailable or invalid.
     */
    private fun getPendingToDacUs(track: AudioSink): Long {
        val ts = track.getTimestamp() ?: return 0L
        if (ts.framePosition <= 0) return 0L
        val pendingFrames = (totalFramesWritten.get() - ts.framePosition).coerceAtLeast(0)
        return (pendingFrames * 1_000_000L) / sampleRate
    }

    /**
     * Advance the server timeline cursor by a number of input frames consumed.
     *
     * Matches Python CLI's _advance_server_cursor_frames: uses integer accumulator
     * to avoid floating-point drift over long playback sessions.
     *
     * @param frames Number of input frames consumed (read from queue)
     */
    private fun advanceServerCursorFrames(frames: Int) {
        if (frames <= 0) return
        serverTimelineCursorRemainder += frames.toLong() * 1_000_000L
        if (serverTimelineCursorRemainder >= sampleRate) {
            val incUs = serverTimelineCursorRemainder / sampleRate
            serverTimelineCursorRemainder %= sampleRate
            serverTimelineCursor += incUs
        }
    }

    /**
     * Get the server timeline cursor (where we've READ/CONSUMED audio up to).
     *
     * @return Server time in microseconds of input audio consumed from the queue
     */
    fun getServerTimelineCursorUs(): Long = serverTimelineCursor

    /**
     * Get the current sync error.
     *
     * Positive = behind (haven't read enough) → need to DROP
     * Negative = ahead (read too much) → need to INSERT
     *
     * @return Sync error in microseconds
     */
    fun getSyncErrorUs(): Long = syncErrorUs

    /**
     * Check if start time has been calibrated from AudioTimestamp.
     */
    fun isStartTimeCalibrated(): Boolean = startTimeCalibrated

    /**
     * Get the sync error filter's drift value.
     */
    fun getSyncErrorDrift(): Double = syncErrorFilter.driftValue

    /**
     * Get the remaining grace period time in microseconds.
     * Returns -1 if grace period is not active.
     */
    fun getGracePeriodRemainingUs(): Long {
        if (playingStateEnteredAtUs <= 0) return -1
        val nowUs = nowNs() / 1000
        val elapsed = nowUs - playingStateEnteredAtUs
        val remaining = STARTUP_GRACE_PERIOD_US - elapsed
        return if (remaining > 0) remaining else -1
    }

    /**
     * Get current playback state.
     */
    fun getPlaybackState(): PlaybackState = playbackState

    /**
     * Get current buffered duration in milliseconds.
     */
    fun getBufferedDurationMs(): Long {
        return (totalQueuedSamples.get() * 1000) / sampleRate
    }

    /**
     * Set the callback for playback state changes.
     */
    fun setStateCallback(callback: SyncAudioPlayerCallback?) {
        stateCallback = callback
    }

    /**
     * Update playback state and notify callback if changed.
     * Thread-safe via stateLock (ReentrantLock allows re-entry from callers already holding lock).
     */
    private fun setPlaybackState(newState: PlaybackState) {
        stateLock.withLock {
            if (playbackState != newState) {
                // Track when we enter PLAYING state for grace period calculation
                if (newState == PlaybackState.PLAYING && playbackState != PlaybackState.PLAYING) {
                    playingStateEnteredAtUs = nowNs() / 1000
                    AppLog.Audio.d("Entered PLAYING state - grace period starts (${STARTUP_GRACE_PERIOD_US/1000}ms)")
                }
                playbackState = newState
                stateCallback?.onPlaybackStateChanged(newState)
            }
        }
    }

    /**
     * Get current sync statistics.
     */
    fun getStats(): SyncStats {
        return SyncStats(
            chunksReceived = chunksReceived,
            chunksPlayed = chunksPlayed,
            chunksDropped = chunksDropped.get(),
            syncCorrections = syncCorrections,
            queuedSamples = totalQueuedSamples.get(),
            isPlaying = isPlaying.get(),
            // Playback state machine
            playbackState = playbackState,
            scheduledStartLoopTimeUs = scheduledStartLoopTimeUs,
            firstServerTimestampUs = firstServerTimestampUs,
            // Sync error (simplified Windows SDK style)
            syncErrorUs = syncErrorUs,
            smoothedSyncErrorUs = syncErrorFilter.offsetMicros,
            startTimeCalibrated = startTimeCalibrated,
            samplesReadSinceStart = samplesReadSinceStart,
            serverTimelineCursorUs = serverTimelineCursor,
            totalFramesWritten = totalFramesWritten.get(),
            // Sample insert/drop correction stats
            framesInserted = framesInserted,
            framesDropped = framesDropped,
            insertEveryNFrames = insertEveryNFrames,
            dropEveryNFrames = dropEveryNFrames,
            // Gap/overlap handling stats
            gapsFilled = gapsFilled,
            gapSilenceMs = gapSilenceMs,
            overlapsTrimmed = overlapsTrimmed,
            overlapTrimmedMs = overlapTrimmedMs,
            // New stats for comprehensive debugging
            reanchorCount = reanchorCount,
            bufferUnderrunCount = bufferUnderrunCount,
            syncErrorDrift = syncErrorFilter.driftValue,
            gracePeriodRemainingUs = getGracePeriodRemainingUs(),
            dacTimestampsStable = dacTimestampsStable
        )
    }

    data class SyncStats(
        val chunksReceived: Long,
        val chunksPlayed: Long,
        val chunksDropped: Long,
        val syncCorrections: Long,
        val queuedSamples: Long,
        val isPlaying: Boolean,
        // Playback state machine stats
        val playbackState: PlaybackState = PlaybackState.INITIALIZING,
        val scheduledStartLoopTimeUs: Long? = null,
        val firstServerTimestampUs: Long? = null,
        // Sync error stats (simplified Windows SDK style)
        val syncErrorUs: Long = 0,
        val smoothedSyncErrorUs: Long = 0,
        val startTimeCalibrated: Boolean = false,
        val samplesReadSinceStart: Long = 0,
        val serverTimelineCursorUs: Long = 0,
        val totalFramesWritten: Long = 0,
        // Sample insert/drop correction stats
        val framesInserted: Long = 0,
        val framesDropped: Long = 0,
        val insertEveryNFrames: Int = 0,
        val dropEveryNFrames: Int = 0,
        // Gap/overlap handling stats
        val gapsFilled: Long = 0,
        val gapSilenceMs: Long = 0,
        val overlapsTrimmed: Long = 0,
        val overlapTrimmedMs: Long = 0,
        // New stats for comprehensive debugging
        val reanchorCount: Long = 0,
        val bufferUnderrunCount: Long = 0,
        val dacCalibrationCount: Int = 0,
        val syncErrorDrift: Double = 0.0,
        val gracePeriodRemainingUs: Long = -1,
        val dacTimestampsStable: Boolean = false
    )
}
