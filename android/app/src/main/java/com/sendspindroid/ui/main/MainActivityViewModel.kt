package com.sendspindroid.ui.main

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.sendspindroid.model.AppConnectionState
import com.sendspindroid.sendspin.protocol.AdmissionState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * ViewModel for MainActivity.
 *
 * Manages all UI state for the main activity, including:
 * - Connection state machine
 * - Playback state and metadata
 * - Volume control
 * - Navigation state
 * - Reconnection tracking
 *
 * This ViewModel survives configuration changes, ensuring playback state
 * is preserved during rotation.
 */
class MainActivityViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "MainActivityViewModel"
        private const val IDLE_TIMEOUT_MS = 60_000L
    }

    // ========================================================================
    // Connection State
    // ========================================================================

    private val _connectionState = MutableStateFlow<AppConnectionState>(AppConnectionState.ServerList)
    val connectionState: StateFlow<AppConnectionState> = _connectionState.asStateFlow()

    // ========================================================================
    // Playback State
    // ========================================================================

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    // "Intent to play" -- the Media3 playWhenReady flag. Unlike isPlaying (which
    // the player bridge forces false for every BUFFERING state), this stays TRUE
    // through a mid-track sync reanchor and only flips false on a real pause/stop.
    // It is the signal that distinguishes a reanchor (audio still playing, show
    // the playing/buffering look) from a pause (show the paused look) -- the
    // BUFFERING playbackState alone cannot, because both surface as BUFFERING with
    // isPlaying=false.
    private val _playWhenReady = MutableStateFlow(false)
    val playWhenReady: StateFlow<Boolean> = _playWhenReady.asStateFlow()

    private val _playbackState = MutableStateFlow(PlaybackState.IDLE)
    val playbackState: StateFlow<PlaybackState> = _playbackState.asStateFlow()

    private val _metadata = MutableStateFlow(TrackMetadata.EMPTY)
    val metadata: StateFlow<TrackMetadata> = _metadata.asStateFlow()

    /**
     * Why a connected session cannot play, or READY when it can.
     *
     * Defaults to READY so the guidance never flashes on screen before the
     * first `server/activate` of a session arrives.
     */
    private val _admissionState = MutableStateFlow(AdmissionState.READY)
    val admissionState: StateFlow<AdmissionState> = _admissionState.asStateFlow()

    fun updateAdmissionState(state: AdmissionState) {
        _admissionState.value = state
    }

    /** The dynamic pairing code to show the operator, or null when none is active. */
    private val _pairingCode = MutableStateFlow<String?>(null)
    val pairingCode: StateFlow<String?> = _pairingCode.asStateFlow()

    fun updatePairingCode(code: String?) {
        _pairingCode.value = code
    }

    /** Whether a dynamic pairing attempt is waiting on the "Allow pairing" gesture. */
    private val _pairingGestureRequested = MutableStateFlow(false)
    val pairingGestureRequested: StateFlow<Boolean> = _pairingGestureRequested.asStateFlow()

    fun updatePairingGestureRequested(requested: Boolean) {
        _pairingGestureRequested.value = requested
    }

    private val _groupName = MutableStateFlow("")
    val groupName: StateFlow<String> = _groupName.asStateFlow()

    // Track progress (position and duration in milliseconds)
    private val _positionMs = MutableStateFlow(0L)
    val positionMs: StateFlow<Long> = _positionMs.asStateFlow()

    private val _durationMs = MutableStateFlow(0L)
    val durationMs: StateFlow<Long> = _durationMs.asStateFlow()

    // Monotonic timestamp (SystemClock.elapsedRealtime()) when position was last updated.
    // Used by TrackProgressBar to correctly interpolate from a stale position after recomposition.
    private val _positionUpdatedAt = MutableStateFlow(0L)
    val positionUpdatedAt: StateFlow<Long> = _positionUpdatedAt.asStateFlow()

    // ========================================================================
    // Artwork State
    // ========================================================================

    private val _artworkSource = MutableStateFlow<ArtworkSource?>(null)
    val artworkSource: StateFlow<ArtworkSource?> = _artworkSource.asStateFlow()

    private val _playerColors = MutableStateFlow<PlayerColors?>(null)
    val playerColors: StateFlow<PlayerColors?> = _playerColors.asStateFlow()

    // ========================================================================
    // Audio Stream Spec (codec, sample rate, bit depth, channels)
    // ========================================================================

    private val _audioStreamSpec = MutableStateFlow<AudioStreamSpec?>(null)
    val audioStreamSpec: StateFlow<AudioStreamSpec?> = _audioStreamSpec.asStateFlow()

    // ========================================================================
    // Volume State
    // ========================================================================

    private val _volume = MutableStateFlow(0.75f)
    val volume: StateFlow<Float> = _volume.asStateFlow()

    // ========================================================================
    // Reconnection State
    // ========================================================================

    private val _reconnectingState = MutableStateFlow<ReconnectingState?>(null)
    val reconnectingState: StateFlow<ReconnectingState?> = _reconnectingState.asStateFlow()

    // ========================================================================
    // UI State
    // ========================================================================

    // Music Assistant state
    private val _isMaConnected = MutableStateFlow(false)
    val isMaConnected: StateFlow<Boolean> = _isMaConnected.asStateFlow()

    // ========================================================================
    // Connection State Updates
    // ========================================================================

    fun updateConnectionState(state: AppConnectionState) {
        Log.d(TAG, "Connection state: $state")
        _connectionState.value = state
    }

    // ========================================================================
    // Playback State Updates
    // ========================================================================

    // When playback has been paused for IDLE_TIMEOUT_MS with no resume and no
    // new metadata, drop back to the idle screen. Without this, MA stop/clear
    // leaves the previous track on screen indefinitely because the SendSpin
    // server never pushes a "metadata cleared" event (it just stops sending
    // audio). 60 s is comfortably longer than MA's 30 s pause auto-stop, so
    // a short paused interval stays as "Paused" with the last track visible.
    private var idleTimeoutJob: Job? = null

    /** Feed the raw Media3 play-intent (playWhenReady) from the player bridge. */
    fun updatePlayWhenReady(value: Boolean) {
        _playWhenReady.value = value
    }

    fun updatePlaybackState(isPlaying: Boolean, state: PlaybackState) {
        _isPlaying.value = isPlaying
        _playbackState.value = state
        // Reset the idle watchdog on any "audio is actively flowing" signal.
        // STATE_READY+isPlaying=true is the only "definitely playing" combination
        // produced by the SendSpinPlayer state mapping.
        if (isPlaying && state == PlaybackState.READY) {
            idleTimeoutJob?.cancel()
            idleTimeoutJob = null
        } else if (idleTimeoutJob == null && !_metadata.value.isEmpty) {
            idleTimeoutJob = viewModelScope.launch {
                delay(IDLE_TIMEOUT_MS)
                // ensureActive() so a cancel issued during the delay window
                // (e.g. from resetPlaybackState on a fresh connect) doesn't
                // race past the cancellation and clobber freshly-set state
                // after the cancel. Without this, Job.cancel() is async and
                // the body could still execute after the cancel.
                ensureActive()
                // Re-check on fire: a metadata update or resume during the
                // delay should preempt clearing.
                if (!_isPlaying.value) {
                    Log.d(TAG, "Idle watchdog: clearing stale metadata after ${IDLE_TIMEOUT_MS / 1000}s of non-playing state")
                    _metadata.value = TrackMetadata.EMPTY
                    _artworkSource.value = null
                    _playerColors.value = null
                    _audioStreamSpec.value = null
                }
                idleTimeoutJob = null
            }
        }
    }

    /**
     * Update metadata. Delegates the merge to [mergeTrackMetadata] which
     * handles the "is this a new track?" decision so Media3 emissions that
     * don't carry SendSpin-only fields (queueTrack / totalTracks) don't
     * inherit the previous track's queue position.
     */
    fun updateMetadata(
        title: String,
        artist: String,
        album: String,
        albumArtist: String? = null,
        year: Int? = null,
        albumTrack: Int? = null,
        queueTrack: Int? = null,
        totalTracks: Int? = null
    ) {
        val previousTitle = _metadata.value.title
        val previousArtist = _metadata.value.artist
        _metadata.value = mergeTrackMetadata(
            prev = _metadata.value,
            title = title,
            artist = artist,
            album = album,
            albumArtist = albumArtist,
            year = year,
            albumTrack = albumTrack,
            queueTrack = queueTrack,
            totalTracks = totalTracks
        )
        // Only cancel the idle watchdog on a real track change (different
        // title/artist after the merge). MA pushes server/state updates
        // every few seconds even when paused; cancelling on every push
        // would keep the watchdog from ever firing.
        val nowTitle = _metadata.value.title
        val nowArtist = _metadata.value.artist
        if (nowTitle != previousTitle || nowArtist != previousArtist) {
            idleTimeoutJob?.cancel()
            idleTimeoutJob = null
        }
    }

    fun updateGroupName(name: String) {
        _groupName.value = name
    }

    fun updateTrackProgress(positionMs: Long, durationMs: Long, positionUpdatedAt: Long) {
        _positionMs.value = positionMs
        _durationMs.value = durationMs
        _positionUpdatedAt.value = positionUpdatedAt
    }

    // ========================================================================
    // Artwork Updates
    // ========================================================================

    fun updateArtwork(source: ArtworkSource?) {
        _artworkSource.value = source
    }

    /**
     * Currently uncalled. The artwork-derived accent pipeline is inert: nothing
     * in the app invokes this, and PlayerColors is never constructed anywhere,
     * so [playerColors] only ever holds null. MainActivity's Palette extraction
     * (extractAndApplyColors) writes straight to the legacy View bindings and
     * never reaches this ViewModel. Retained as the wiring point if the accent
     * is ever revived -- see the accent comment in NowPlayingScreen.
     */
    fun updatePlayerColors(colors: PlayerColors?) {
        _playerColors.value = colors
    }

    fun clearArtwork() {
        _artworkSource.value = null
        // _playerColors deliberately not cleared here: when an artwork-less
        // track is displayed, NowPlayingScreen's sticky-artwork logic keeps the
        // prior image visible, and clearing a derived accent alongside it would
        // leave the sticky image with the fallback accent -- a visible mismatch
        // (e.g. orange-tinted album art surrounded by amber glows).
        // resetPlaybackState is the authoritative path that clears both on
        // disconnect.
        //
        // Moot in practice today: _playerColors is always null because nothing
        // calls updatePlayerColors. The reasoning is kept because it is the
        // constraint any revival of that pipeline has to satisfy.
    }

    // ========================================================================
    // Audio Stream Spec Updates
    // ========================================================================

    fun updateAudioStreamSpec(spec: AudioStreamSpec?) {
        _audioStreamSpec.value = spec
    }

    // ========================================================================
    // Volume Updates
    // ========================================================================

    fun updateVolume(volume: Float) {
        _volume.value = volume.coerceIn(0f, 1f)
    }

    // ========================================================================
    // Reconnection Updates
    // ========================================================================

    fun updateReconnectingState(serverName: String, attempt: Int) {
        _reconnectingState.value = ReconnectingState(serverName, attempt)
    }

    fun clearReconnectingState() {
        _reconnectingState.value = null
    }

    // ========================================================================
    // Music Assistant Updates
    // ========================================================================

    fun setMaConnected(connected: Boolean) {
        _isMaConnected.value = connected
    }

    // ========================================================================
    // Reset State
    // ========================================================================

    /**
     * Reset all playback-related state when disconnecting.
     */
    fun resetPlaybackState() {
        idleTimeoutJob?.cancel()
        idleTimeoutJob = null
        _isPlaying.value = false
        _playWhenReady.value = false
        _playbackState.value = PlaybackState.IDLE
        _metadata.value = TrackMetadata.EMPTY
        _groupName.value = ""
        _artworkSource.value = null
        _playerColors.value = null
        _positionMs.value = 0
        _durationMs.value = 0
        _positionUpdatedAt.value = 0L
        _isMaConnected.value = false
        _audioStreamSpec.value = null
    }

    /**
     * Reset all state to initial values.
     */
    fun resetToServerList() {
        _connectionState.value = AppConnectionState.ServerList
        _reconnectingState.value = null
        _pairingCode.value = null
        _pairingGestureRequested.value = false
        resetPlaybackState()
    }
}

/**
 * Playback state from MediaPlayer.
 */
enum class PlaybackState {
    IDLE,
    BUFFERING,
    READY,
    ENDED
}
