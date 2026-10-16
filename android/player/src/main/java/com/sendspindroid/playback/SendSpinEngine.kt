package com.sendspindroid.playback

import android.util.Log
import com.sendspindroid.model.PlaybackStateType
import com.sendspindroid.sendspin.PlaybackState as SyncPlaybackState
import com.sendspindroid.sendspin.SendSpin
import com.sendspindroid.sendspin.SyncAudioPlayer
import com.sendspindroid.sendspin.SyncAudioPlayerCallback
import com.sendspindroid.sendspin.decoder.AudioDecoder
import com.sendspindroid.sendspin.decoder.AudioDecoderFactory
import com.sendspindroid.sendspin.protocol.SendSpinProtocol
import com.sendspindroid.sendspin.protocol.StreamConfig
import com.sendspindroid.sendspin.SendspinTimeFilter
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The audio pipeline between a [SendSpin] client and the output: one decode
 * worker, the [SyncAudioPlayer] and its format changes, and the local mutes.
 *
 * The host passes on the client's stream callbacks and its connection
 * events, and keeps the rest (media session, notification, audio focus,
 * device volume, connections). The `on*` stream methods are called on the
 * client's thread; every other method, and every [Listener] call, is on the
 * main thread.
 */
class SendSpinEngine(
    private val listener: Listener,
    /** Cap the decoded queue at about ten seconds. Read when a player is created. */
    private val lowMemoryMode: () -> Boolean,
    /** The saved `muted` state. Read when a player is created. */
    private val playerMuted: () -> Boolean,
    /** Ordered with the host's own main-thread posts: pass one built on the host's Handler. */
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main,
    // Decode worker: single-thread-equivalent coroutine that owns the
    // decoder for its lifetime. See
    // docs/superpowers/specs/2026-04-23-codec-safe-decoder-redesign-design.md
    // for the H-4 + M-8 rationale.
    @OptIn(ExperimentalCoroutinesApi::class)
    private val decodeDispatcher: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1),
    private val newPlayer: (SendspinTimeFilter, sampleRate: Int, channels: Int, bitDepth: Int, maxQueueSamples: Long) -> SyncAudioPlayer =
        { timeFilter, sampleRate, channels, bitDepth, maxQueueSamples ->
            SyncAudioPlayer(
                timeFilter = timeFilter,
                sampleRate = sampleRate,
                channels = channels,
                bitDepth = bitDepth,
                maxQueueSamples = maxQueueSamples,
            )
        },
) {

    interface Listener {
        /**
         * A stream started and the client's clock is available. Called
         * before its player is set up, for the host to take what playback
         * needs (locks, foreground).
         */
        fun onStreamStarted()

        /** The player was created or replaced, or released ([player] null). */
        fun onPlayerChanged(player: SyncAudioPlayer?)

        /** The player's own playback state changed. */
        fun onPlayerStateChanged(state: SyncPlaybackState)

        /**
         * Fork: the player could not recover its AudioTrack and was released
         * ([onPlayerChanged] null came first). The next stream/start builds a
         * fresh one.
         */
        fun onOutputLost() {}
    }

    /** The client being played from, whose clock the player follows. */
    @Volatile
    var client: SendSpin? = null

    /** The output, null between streams of different formats and after a disconnect. */
    @Volatile
    var player: SyncAudioPlayer? = null
        private set

    /** Codec of the last stream started, for stats. */
    var codec: String = "pcm"
        private set

    // Configuration of the active player stream, null when none is active.
    // Tells a stream/start that begins a stream from one that reconfigures
    // the stream already playing, which must keep its buffered audio.
    @Volatile
    var activeStream: StreamConfig? = null
        private set

    // Mutes kept across players. PLAYER is read from the host instead, and
    // SYNC belongs to the player whose sync it reports.
    private val heldMutes = mutableSetOf<SyncAudioPlayer.MuteReason>()

    // Players replaced by a format change on an active stream, still playing
    // out the audio received in the old format (see switchOutputFormat).
    // Everything done to the output is done to them too. Main thread only.
    private val retiredPlayers = mutableListOf<SyncAudioPlayer>()

    // Track gain kept across players (see setTrackGain)
    private var trackGain = 1f

    private val scope = CoroutineScope(SupervisorJob() + mainDispatcher)

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
        class Chunk(
            val serverTimeMicros: Long,
            val audioData: ByteArray,
            val generation: Int,
        ) : DecodeTask()
        class StartStream(
            val codec: String,
            val sampleRate: Int,
            val channels: Int,
            val bitDepth: Int,
            val codecHeader: ByteArray?,
            // True when this reconfigures an active stream: audio queued
            // before it is in the previous format and must still play.
            val keepBuffered: Boolean,
            // decodeGeneration and streamEpoch when the stream/start arrived
            val generation: Int,
            val epoch: Int,
        ) : DecodeTask()
        object Flush : DecodeTask()
        object Release : DecodeTask()
    }

    // Capacity = 1000 ~= 20 seconds at the expected 50 chunks/sec. Sized to
    // absorb cold-start + pre-buffered bursts (~100 entries) with 10x headroom.
    private val decodeChannel = Channel<DecodeTask>(capacity = 1000)

    private val decodeJob: Job

    // Owned exclusively by the decode worker coroutine (serialized on
    // decodeDispatcher). Single-writer invariant: all mutations happen
    // inside handleDecodeStartStream / handleDecodeRelease, both of which
    // run on decodeDispatcher. No volatile / lock needed because no other
    // thread reads or writes this field.
    private var audioDecoder: AudioDecoder? = null

    // The stream the decoder was set up for, to set it up again after a
    // decode error, and when that was last done. Decode worker only.
    private var decoderStream: DecodeTask.StartStream? = null
    private var lastDecoderSetUpAgainNs = 0L

    // Guards against race condition: onAudioChunk runs on WebSocket thread but
    // decoder creation is posted to the decode worker. Chunks arriving before
    // the new decoder is ready would hit the old (released) decoder and throw.
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

    // Which stream a format change belongs to. Moved on by a stream/start
    // that begins a new stream, by stream/end and by releasePlayer, but not
    // by stream/clear: a format change still applies after a clear. A
    // switch whose stream is gone must not touch the output the later
    // stream/start set up. Written on the client's thread and on main.
    private val streamEpoch = AtomicInteger()

    /**
     * The output the host was last given. On a format change the retired
     * player stays it until the new one plays, so the session reports the
     * audio being heard rather than a new output waiting to start. Main
     * thread.
     */
    var hostPlayer: SyncAudioPlayer? = null
        private set

    init {
        decodeJob = scope.launch(decodeDispatcher) {
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
                // release()'s Release is lost when the channel is full, and a
                // cancelled worker never reaches it. Release on the way out.
                handleDecodeRelease()
            }
        }
    }

    // ========== Stream callbacks, on the client's thread ==========

    fun onStreamStart(codec: String, sampleRate: Int, channels: Int, bitDepth: Int, codecHeader: ByteArray?) {
        val config = StreamConfig(codec, sampleRate, channels, bitDepth, codecHeader)
        val action = StreamStartAction.of(activeStream, config)
        activeStream = config
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
        val generation = decodeGeneration
        val epoch = if (keepBuffered) streamEpoch.get() else streamEpoch.incrementAndGet()

        // Post decoder lifecycle to the single-owner decode worker via the
        // channel. FIFO ordering between StartStream and any subsequent
        // Chunk tasks ensures the new decoder is in place before its
        // chunks are decoded. decoderReady is set optimistically so any
        // chunk arriving during reconfiguration is still enqueued; it
        // will decode with the new decoder once the worker drains the
        // StartStream task ahead of it.
        decoderReady = true
        scope.launch {
            decodeChannel.send(
                DecodeTask.StartStream(codec, sampleRate, channels, bitDepth, codecHeader, keepBuffered, generation, epoch)
            )
        }

        scope.launch {
            this@SendSpinEngine.codec = codec

            if (client?.getTimeFilter() == null) {
                Log.e(TAG, "Cannot start audio: time filter not available")
                return@launch
            }

            listener.onStreamStarted()

            // The decode worker switches the output of an active stream, in
            // order with its chunks (switchOutputFormat).
            if (keepBuffered) return@launch

            // A new stream: what a retired player still holds belongs to the
            // last one, which can end without a stream/end (a server switch)
            releaseRetiredPlayers()

            // Reuse existing player if format matches (DAC timestamps stay warm)
            val existingPlayer = player
            if (existingPlayer != null && existingPlayer.matchesFormat(sampleRate, channels, bitDepth)) {
                Log.i(TAG, "Reusing existing SyncAudioPlayer - DAC already warm")
                existingPlayer.clearBuffer()
            } else {
                // Format changed or no existing player - create new one
                existingPlayer?.release()
                createPlayer(sampleRate, channels, bitDepth)
            }
        }
    }

    fun onAudioChunk(serverTimeMicros: Long, audioData: ByteArray) {
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

        // Snapshot on WS-IO so the tag reflects the stream this chunk
        // actually arrived on, not whatever is current by the time the
        // launched coroutine runs on the main dispatcher.
        val generation = decodeGeneration
        scope.launch {
            decodeChannel.send(DecodeTask.Chunk(serverTimeMicros, audioData, generation))
        }
    }

    fun onStreamClear() {
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
        scope.launch { decodeChannel.send(DecodeTask.Flush) }

        scope.launch {
            Log.i(TAG, "[cmd-trace] T3 onStreamClear.post ts=${System.nanoTime() / 1_000_000} thread=${Thread.currentThread().name}")
            Log.d(TAG, "Stream clear - flushing audio buffer")
            releaseRetiredPlayers()
            player?.clearBuffer()
        }
    }

    fun onStreamEnd() {
        Log.i(TAG, "[cmd-trace] T2 onStreamEnd ts=${System.nanoTime() / 1_000_000} thread=${Thread.currentThread().name}")

        // Invalidate the ending stream's pre-buffered chunks (issue #114).
        decodeGeneration++
        streamEpoch.incrementAndGet()
        activeStream = null

        scope.launch {
            Log.i(TAG, "[cmd-trace] T3 onStreamEnd.post ts=${System.nanoTime() / 1_000_000} thread=${Thread.currentThread().name}")
            Log.i(TAG, "Stream end - server terminated playback")
            // Enter idle mode: keep AudioTrack alive and writing silence
            // so DAC timestamps stay warm for the next stream start
            releaseRetiredPlayers()
            player?.enterIdle()
        }
    }

    /** The clock filter lost its convergence (or regained it): mute (or un-mute) for sync. */
    fun onSyncMuteChanged(muted: Boolean) {
        scope.launch {
            retiredPlayers.forEach { it.setMuted(SyncAudioPlayer.MuteReason.SYNC, muted) }
            player?.setMuted(SyncAudioPlayer.MuteReason.SYNC, muted)
        }
    }

    /** The network changed under the client: re-anchor the output. */
    fun onNetworkChanged() {
        scope.launch {
            Log.i(TAG, "Network changed - triggering audio player reanchor")
            releaseRetiredPlayers()
            player?.clearBuffer()
        }
    }

    // ========== Host calls, on the main thread ==========

    /** The client left Ready. No stream survives the connection it was started on. */
    fun endStream() {
        activeStream = null
    }

    /** The connection is gone: stop and release the output. */
    fun releasePlayer() {
        streamEpoch.incrementAndGet()
        releaseRetiredPlayers()
        player?.stop()
        player?.release()
        player = null
        announce(null)
    }

    /**
     * The server's playback state, per the spec: stopped resets the position,
     * so the buffer goes; paused keeps it for later; playing resumes.
     */
    fun applyServerPlayback(state: PlaybackStateType) {
        val p = player
        when (state) {
            PlaybackStateType.STOPPED -> {
                releaseRetiredPlayers()
                p?.clearBuffer()
                p?.pause()
            }
            PlaybackStateType.PAUSED -> {
                retiredPlayers.forEach { it.pause() }
                p?.pause()
            }
            PlaybackStateType.PLAYING -> {
                retiredPlayers.forEach { it.resume() }
                p?.resume()
            }
            else -> {}
        }
    }

    /**
     * Mute or un-mute the output for one [reason]. INTERRUPTION and VOICE are
     * kept and applied to every later player; PLAYER is the host's to
     * persist, and is read back from [playerMuted] for a new player.
     */
    fun setMuted(reason: SyncAudioPlayer.MuteReason, muted: Boolean) {
        if (reason == SyncAudioPlayer.MuteReason.INTERRUPTION || reason == SyncAudioPlayer.MuteReason.VOICE) {
            if (muted) heldMutes += reason else heldMutes -= reason
        }
        retiredPlayers.forEach { it.setMuted(reason, muted) }
        player?.setMuted(reason, muted)
    }

    /**
     * Gain on the output, 0.0 to 1.0, on top of the device volume: the part
     * of a volume the device's volume index cannot reach. Kept and applied to
     * every later player. Main thread only.
     */
    fun setTrackGain(gain: Float) {
        trackGain = gain
        retiredPlayers.forEach { it.setVolume(gain) }
        player?.setVolume(gain)
    }

    /**
     * Stop the decode worker and release the output. The engine cannot be
     * used afterwards.
     */
    fun release() {
        // Send a final Release through the channel so it runs after any
        // pending decode tasks, then close the channel and wait up to 500 ms
        // for the worker to drain and exit. trySend (not send) because
        // release is non-suspending -- if the channel is somehow full we
        // don't want to block teardown waiting for the worker to make
        // progress; handleDecodeRelease still runs if trySend succeeds, or
        // the worker will release the decoder as it exits when the channel
        // is closed. Bumping the generation first ends a wait for queue room
        // and drops the backlog undecoded, so the worker can exit in time.
        decodeGeneration++
        decodeChannel.trySend(DecodeTask.Release)
        decodeChannel.close()
        runBlocking {
            withTimeoutOrNull(500) { decodeJob.join() }
        }
        scope.cancel()

        // audioDecoder is released by handleDecodeRelease on the decode
        // worker (invariant: only that coroutine mutates the field).
        releaseRetiredPlayers()
        player?.release()
        player = null
        hostPlayer = null
    }

    /** Give the host [p] as its output, if it is not already. Main thread. */
    private fun announce(p: SyncAudioPlayer?) {
        if (hostPlayer === p) return
        hostPlayer = p
        listener.onPlayerChanged(p)
    }

    /**
     * Release the retired players now: the audio they still hold is no
     * longer to be played. Main thread.
     */
    private fun releaseRetiredPlayers() {
        retiredPlayers.forEach { it.release() }
        retiredPlayers.clear()
        announce(player)
    }

    /**
     * Let [old] play out the audio it holds, received in the format before a
     * change, beside the new player, and release it once it has. Main thread.
     */
    private fun retire(old: SyncAudioPlayer) {
        retiredPlayers += old
        scope.launch {
            while (old in retiredPlayers && !old.hasPlayedOut()) delay(RETIRED_POLL_MS)
            // What it last wrote is still passing through the mixer
            delay(RETIRED_TAIL_MS)
            if (retiredPlayers.remove(old)) {
                Log.d(TAG, "Output in the previous format has played out - releasing it")
                old.release()
                // The next to play: another retired player, after a second
                // change before this one's successor started
                announce(retiredPlayers.firstOrNull() ?: player)
            }
        }
    }

    // ========== Decode worker ==========

    /**
     * Decode a single audio chunk. Runs on [decodeDispatcher]; [audioDecoder]
     * is read from the single-owner thread so no TOCTOU local-ref capture is
     * needed.
     */
    private suspend fun handleDecodeChunk(t: DecodeTask.Chunk) {
        // Drop chunks belonging to a stream that has since ended or been
        // replaced. Popping and discarding costs microseconds, so a ~30-second
        // look-ahead backlog clears near-instantly instead of grinding through
        // player.queueChunk back-pressure at real-time rate -- that
        // stall is what delayed decoder reconfiguration in issue #114.
        if (t.generation != decodeGeneration) return

        // No decoder means the stream's codec could not be set up: drop the
        // chunk. PCM has a decoder of its own, so nothing passes through raw.
        val decoder = audioDecoder ?: return
        val decoded = try {
            decoder.decode(t.audioData, t.serverTimeMicros)
        } catch (e: Exception) {
            Log.e(TAG, "Decode error, dropping chunk", e)
            // A codec that has failed can stay in its error state, decoding
            // nothing for the rest of the stream; a flush does not leave it
            setUpDecoderAgain()
            return
        }
        val out = player ?: return
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
            // The server may run minutes ahead (buffer_capacity counts encoded
            // bytes), more than the capped decoded queue holds. Wait for room
            // so the backlog stays encoded in decodeChannel; a stream/clear or
            // stream/end bumps decodeGeneration and ends the wait.
            val samples = (audio.pcm.size / out.bytesPerFrame).toLong()
            while (t.generation == decodeGeneration && !out.hasRoomFor(samples)) {
                delay(FORMAT_SWITCH_POLL_MS)
            }
            out.queueChunk(audio.timestampUs, audio.pcm) { t.generation == decodeGeneration }
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

        // On a format change the old decoder can still hold audio of chunks
        // received in the old format ("Clients MUST keep buffered chunks"):
        // it goes to the player that plays that format, before the release.
        // That audio belongs to the generation the stream/start arrived in,
        // so a clear or end since then discards it, and it waits for room
        // like any other chunk.
        val current = { t.generation == decodeGeneration && t.epoch == streamEpoch.get() }
        val out = player
        if (t.keepBuffered && current() && out != null) {
            val held = audioDecoder?.drain().orEmpty()
            Log.d(TAG, "Previous decoder handed back ${held.size} buffers (${held.sumOf { it.pcm.size }} bytes)")
            for (audio in held) {
                val samples = (audio.pcm.size / out.bytesPerFrame).toLong()
                while (current() && !out.hasRoomFor(samples)) delay(FORMAT_SWITCH_POLL_MS)
                out.queueChunk(audio.timestampUs, audio.pcm, current)
            }
        }

        setUpDecoder(t)

        if (t.keepBuffered) switchOutputFormat(t)
    }

    /** Release the decoder and create one for [t]'s stream. Decode worker only. */
    private fun setUpDecoder(t: DecodeTask.StartStream) {
        audioDecoder?.release()
        audioDecoder = null
        decoderStream = null
        try {
            val decoder = AudioDecoderFactory.create(t.codec)
            decoder.configure(t.sampleRate, t.channels, t.bitDepth, t.codecHeader)
            audioDecoder = decoder
            decoderStream = t
            Log.i(TAG, "Audio decoder created: ${t.codec}")
            decoderReady = true
        } catch (e: Exception) {
            Log.e(TAG, "No decoder for ${t.codec}; dropping this stream's audio", e)
            // Subsequent chunks are rejected at the WS-IO fast-path gate.
            decoderReady = false
        }
    }

    /**
     * After a decode error: a fresh decoder for the same stream, at most
     * once a [DECODER_SET_UP_AGAIN_INTERVAL_NS], so data the codec cannot
     * decode does not have it set up for every chunk. Decode worker only.
     */
    private fun setUpDecoderAgain() {
        val stream = decoderStream ?: return
        val now = System.nanoTime()
        if (lastDecoderSetUpAgainNs != 0L && now - lastDecoderSetUpAgainNs < DECODER_SET_UP_AGAIN_INTERVAL_NS) return
        lastDecoderSetUpAgainNs = now
        Log.w(TAG, "Setting the ${stream.codec} decoder up again after a decode error")
        setUpDecoder(stream)
    }

    /**
     * Format change on an active stream. Everything already queued in
     * [player] was decoded in the previous format and must still be played,
     * so when the PCM format differs the old player is retired to play it
     * out, and the new player is created at once beside it. Its track warms
     * up while the old one plays, and its start gating places the first chunk
     * in the new format where the old audio ends ("Servers MUST timestamp the
     * first chunk in the new format to follow the last chunk in the previous
     * format"), so there is no gap. The chunks behind this task are in the new
     * format and go to the new player.
     */
    private suspend fun switchOutputFormat(t: DecodeTask.StartStream) = withContext(mainDispatcher) {
        // Its stream has ended or been replaced: the later stream/start set
        // up the output on this thread, ordered with this check
        if (t.epoch != streamEpoch.get()) return@withContext
        val old = player
        if (old != null && old.matchesFormat(t.sampleRate, t.channels, t.bitDepth)) return@withContext
        if (old != null) {
            Log.i(TAG, "Output format change on active stream - the previous output plays out its " +
                "${old.getBufferedDurationMs()}ms beside the new one")
            retire(old)
        }
        createPlayer(t.sampleRate, t.channels, t.bitDepth, announceNow = old == null)
    }

    private fun handleDecodeFlush() {
        audioDecoder?.flush()
    }

    private fun handleDecodeRelease() {
        audioDecoder?.release()
        audioDecoder = null
        decoderReady = false
    }

    /**
     * Creates and starts a [SyncAudioPlayer] for the given PCM format. Main
     * thread. Without [announceNow] the host is given it once it plays.
     */
    private fun createPlayer(sampleRate: Int, channels: Int, bitDepth: Int, announceNow: Boolean = true) {
        val timeFilter = client?.getTimeFilter()
        if (timeFilter == null) {
            Log.e(TAG, "Cannot start audio: time filter not available")
            return
        }
        // The decoded queue is capped; the decoder waits for room and keeps
        // the rest encoded, which buffer_capacity bounds. That is
        // DURATION_NORMAL_SEC at the highest PCM byte rate advertised, so
        // twice it holds 16-bit PCM. Uncapped, an Opus backlog filling it
        // would decode to 13-17 minutes (150-200 MB). Low memory mode: ~10 s.
        val maxSamples = sampleRate.toLong() * if (lowMemoryMode()) {
            SendSpinProtocol.Buffer.DURATION_LOW_MEM_SEC
        } else {
            2 * SendSpinProtocol.Buffer.DURATION_NORMAL_SEC
        }
        val created = newPlayer(timeFilter, sampleRate, channels, bitDepth, maxSamples)
        created.apply {
            setStateCallback(object : SyncAudioPlayerCallback {
                override fun onPlaybackStateChanged(state: SyncPlaybackState) {
                    scope.launch {
                        Log.d(TAG, "SyncAudioPlayer state changed: $state")
                        if (state == SyncPlaybackState.PLAYING && (player === created || created in retiredPlayers)) {
                            announce(created)
                        }
                        if (hostPlayer === created) listener.onPlayerStateChanged(state)
                    }
                }

                // Fork: the sink-recovery budget is spent. stop() alone would
                // leave a player whose loop is gone, and reusing it for the next
                // stream would play nothing: release it, and forget the stream
                // so the next stream/start sets one up again.
                override fun onBufferExhausted() {
                    scope.launch {
                        if (player !== created) return@launch
                        Log.e(TAG, "AudioTrack unrecoverable - releasing the player")
                        activeStream = null
                        releasePlayer()
                        listener.onOutputLost()
                    }
                }
            })
            // From the host, not the session state: that is reset on disconnect.
            setMuted(SyncAudioPlayer.MuteReason.PLAYER, playerMuted())
            setMuted(SyncAudioPlayer.MuteReason.INTERRUPTION, SyncAudioPlayer.MuteReason.INTERRUPTION in heldMutes)
            setMuted(SyncAudioPlayer.MuteReason.VOICE, SyncAudioPlayer.MuteReason.VOICE in heldMutes)
            setVolume(trackGain)
            initialize()
            start()
        }
        player = created
        if (announceNow) announce(created)
        Log.i(TAG, "SyncAudioPlayer created: ${sampleRate}Hz, ${channels}ch, ${bitDepth}bit")
    }

    private companion object {
        const val TAG = "SendSpinEngine"

        // Poll interval while the decoder waits for room in a capped queue.
        const val FORMAT_SWITCH_POLL_MS = 20L

        // A retired player is checked this often for having played out, and
        // released this long after, once what it last wrote is through.
        const val RETIRED_POLL_MS = 50L
        const val RETIRED_TAIL_MS = 100L

        // At most this often, a fresh decoder after a decode error
        const val DECODER_SET_UP_AGAIN_INTERVAL_NS = 1_000_000_000L
    }
}
