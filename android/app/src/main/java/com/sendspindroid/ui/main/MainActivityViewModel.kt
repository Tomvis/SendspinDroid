package com.sendspindroid.ui.main

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import com.sendspindroid.model.AppConnectionState
import com.sendspindroid.sendspin.protocol.AdmissionState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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

    fun updatePlaybackState(isPlaying: Boolean, state: PlaybackState) {
        _isPlaying.value = isPlaying
        _playbackState.value = state
    }

    fun updateMetadata(title: String, artist: String, album: String) {
        _metadata.value = TrackMetadata(title, artist, album)
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

    fun updatePlayerColors(colors: PlayerColors?) {
        _playerColors.value = colors
    }

    fun clearArtwork() {
        _artworkSource.value = null
        _playerColors.value = null
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
        _isPlaying.value = false
        _playbackState.value = PlaybackState.IDLE
        _metadata.value = TrackMetadata.EMPTY
        _groupName.value = ""
        _artworkSource.value = null
        _playerColors.value = null
        _positionMs.value = 0
        _durationMs.value = 0
        _positionUpdatedAt.value = 0L
        _isMaConnected.value = false
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
