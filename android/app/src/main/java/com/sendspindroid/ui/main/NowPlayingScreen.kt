package com.sendspindroid.ui.main

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sendspindroid.R
import com.sendspindroid.ui.main.components.AdmissionNotice
import com.sendspindroid.sendspin.pairing.PairedServers
import com.sendspindroid.sendspin.pairing.PairingOutcome
import com.sendspindroid.sendspin.protocol.AdmissionState
import com.sendspindroid.ui.settings.message
import com.sendspindroid.model.AppConnectionState
import com.sendspindroid.ui.adaptive.AdaptiveDefaults
import com.sendspindroid.ui.adaptive.FormFactor
import com.sendspindroid.ui.adaptive.LocalFormFactor
import com.sendspindroid.ui.adaptive.overscanSafe
import com.sendspindroid.ui.main.components.AlbumArtCard
import com.sendspindroid.ui.main.components.ConnectionProgress
import com.sendspindroid.ui.main.components.PlaybackControls
import com.sendspindroid.ui.main.components.ReconnectingBanner
import com.sendspindroid.ui.main.components.TrackProgressBar
import com.sendspindroid.ui.main.components.VolumeSlider
import com.sendspindroid.ui.preview.AllDevicePreviews
import com.sendspindroid.ui.preview.TabletPreviews
import com.sendspindroid.ui.theme.SendSpinTheme

/**
 * Now Playing screen showing album art, track info, and playback controls.
 * Adapts layout based on form factor and orientation:
 * - Phone portrait: Album art at top, controls below
 * - Phone landscape: Album art on left, controls on right
 */
@Composable
fun NowPlayingScreen(
    viewModel: MainActivityViewModel,
    onPreviousClick: () -> Unit,
    onPlayPauseClick: () -> Unit,
    onNextClick: () -> Unit,
    onSwitchGroupClick: () -> Unit,
    onVolumeChange: (Float) -> Unit,
    onOpenPairingClick: () -> Unit,
    onAllowPairingClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val connectionState by viewModel.connectionState.collectAsStateWithLifecycle()
    val isPlaying by viewModel.isPlaying.collectAsStateWithLifecycle()
    val playWhenReady by viewModel.playWhenReady.collectAsStateWithLifecycle()
    val playbackState by viewModel.playbackState.collectAsStateWithLifecycle()
    val metadata by viewModel.metadata.collectAsStateWithLifecycle()
    val groupName by viewModel.groupName.collectAsStateWithLifecycle()
    val artworkSource by viewModel.artworkSource.collectAsStateWithLifecycle()
    val volume by viewModel.volume.collectAsStateWithLifecycle()
    val reconnectingState by viewModel.reconnectingState.collectAsStateWithLifecycle()
    val playerColors by viewModel.playerColors.collectAsStateWithLifecycle()
    val positionMs by viewModel.positionMs.collectAsStateWithLifecycle()
    val durationMs by viewModel.durationMs.collectAsStateWithLifecycle()
    val positionUpdatedAt by viewModel.positionUpdatedAt.collectAsStateWithLifecycle()
    val admissionState by viewModel.admissionState.collectAsStateWithLifecycle()
    val pairingCode by viewModel.pairingCode.collectAsStateWithLifecycle()
    val pairingGestureRequested by viewModel.pairingGestureRequested.collectAsStateWithLifecycle()
    val audioStreamSpec by viewModel.audioStreamSpec.collectAsStateWithLifecycle()
    // Media3's MediaController can transiently emit blank MediaMetadata between
    // tracks and on state transitions, which on the TV layout flips the whole
    // screen to NowPlayingIdleScreen for one frame (text disappears, ambient
    // background switches to idle blobs) before the real metadata lands. Hold
    // onto the last non-empty metadata (and artwork) while the session is
    // actively connected; release back to whatever the VM currently reports
    // once we drop out of Connected/Reconnecting.
    val isActivelyConnected = connectionState is AppConnectionState.Connected ||
        connectionState is AppConnectionState.Reconnecting
    // Zombie-state safeguard flag, set by the artwork effect below and read
    // by the metadata effect. Pulling stickyMetadata writes into a single
    // effect keeps state ownership clear: if both effects wrote
    // stickyMetadata directly, the metadata effect would re-overwrite the
    // zombie clear on the next non-empty metadata emission, and the order
    // of arrivals decided whether the user landed on the idle screen.
    var artworkZombieClear by remember { mutableStateOf(false) }
    val lastPairingOutcome by PairedServers.lastOutcome.collectAsStateWithLifecycle()

    var stickyArtworkSource by remember { mutableStateOf(artworkSource) }
    LaunchedEffect(artworkSource, isActivelyConnected) {
        if (!isActivelyConnected || artworkSource != null) {
            stickyArtworkSource = artworkSource
            artworkZombieClear = false
        } else {
            // Same debounce as stickyMetadata: a brief null from Media3 between
            // tracks should not clear the held artwork, but the watchdog's
            // intentional clear should.
            kotlinx.coroutines.delay(500)
            if (artworkSource == null) stickyArtworkSource = null
            // Zombie-state safeguard. MA's "Clear queue" stops audio but
            // doesn't fire an empty-metadata frame, so without this the user
            // would see text + spec chips + a frozen progress rail layered
            // over a dark placeholder card, with no way for the screen to
            // recover until the 60 s pause watchdog finally nulls everything.
            // Wait another 1.2 s after the artwork is confirmed gone; if it
            // hasn't come back, signal the metadata effect to flip to EMPTY.
            // Narrow the safeguard to a genuinely STOPPED track. The original
            // guard fired for any actively-connected track that merely lacked
            // cover art, which flipped art-less but actively PLAYING tracks
            // (radio, podcasts, local files) to the idle clock ~1.7s in. Require
            // the track to not be playing -- and not merely mid-reanchor, which
            // surfaces as BUFFERING with isPlaying=false -- so only the
            // stuck-metadata-after-stop case trips the clear.
            if (isActivelyConnected && stickyArtworkSource == null &&
                !isPlaying && playbackState != PlaybackState.BUFFERING) {
                kotlinx.coroutines.delay(1200)
                if (stickyArtworkSource == null &&
                    !isPlaying && playbackState != PlaybackState.BUFFERING) {
                    artworkZombieClear = true
                }
            }
        }
    }

    // Release the zombie latch as soon as audio is flowing again. The artwork
    // effect above is keyed only on (artworkSource, isActivelyConnected), so on
    // an art-less library artworkSource stays null across the whole track change
    // and that effect never re-runs to clear the flag -- leaving stickyMetadata
    // pinned to EMPTY and the screen stuck on the idle clock for every
    // subsequent art-less track. Any resumption of playback (or a buffering
    // fill, which precedes it) means the "stopped with stale metadata" premise
    // no longer holds.
    LaunchedEffect(isPlaying, playbackState) {
        if (isPlaying || playbackState == PlaybackState.BUFFERING) {
            artworkZombieClear = false
        }
    }

    var stickyMetadata by remember { mutableStateOf(metadata) }
    LaunchedEffect(metadata, isActivelyConnected, artworkZombieClear) {
        if (artworkZombieClear) {
            stickyMetadata = TrackMetadata.EMPTY
        } else if (!isActivelyConnected || !metadata.isEmpty) {
            stickyMetadata = metadata
        } else {
            // Debounce the blank: Media3's transient empty emissions resolve
            // in under a few hundred ms. The idle watchdog's intentional
            // clear (after 60 s paused) persists, so wait briefly and only
            // accept the clear if metadata is still empty.
            kotlinx.coroutines.delay(500)
            if (metadata.isEmpty) stickyMetadata = metadata
        }
    }
    // SendSpin fires stream/end on pause, which zeroes the audio spec. Keep the
    // last non-null value so the codec/bit-depth/sample-rate chips stay up
    // while paused. We deliberately do NOT key the remember on track metadata:
    // on a track change, audioStreamSpec is typically still the previous
    // track's value (the server may not yet have fired a fresh stream/start),
    // so resetting here would snapshot stale data. Clear on disconnect instead.
    var stickyAudioSpec by remember { mutableStateOf(audioStreamSpec) }
    LaunchedEffect(audioStreamSpec, isActivelyConnected) {
        when {
            !isActivelyConnected -> stickyAudioSpec = null
            audioStreamSpec != null -> stickyAudioSpec = audioStreamSpec
        }
    }
    // When the zombie-state safeguard flips stickyMetadata back to EMPTY,
    // the spec chips should clear too -- otherwise we'd land on the idle
    // screen and the user would still be looking at the "OPUS · 16-BIT" row
    // for the previous track. Idle implies no spec.
    LaunchedEffect(stickyMetadata) {
        if (stickyMetadata.isEmpty) {
            stickyAudioSpec = null
        }
    }
    // "Audio is loading" signal -- not the same as playbackState==BUFFERING,
    // because SendSpin's mapping marks a real pause as BUFFERING too. We need
    // to distinguish three cases that all surface as playbackState==BUFFERING
    // with isPlaying=false (the player bridge forces isPlaying false for every
    // BUFFERING state):
    //   - track start / mid-skip buffer fill  -> show the buffering look
    //   - mid-track sync reanchor             -> show the playing look
    //   - user pressed pause                  -> show the paused look
    // playWhenReady is the discriminator: it stays TRUE for a start/reanchor
    // (audio is meant to keep playing) and flips FALSE only on a real pause/stop.
    // Gating on playWhenReady (instead of the old positionMs < 2500L proxy) keeps
    // the buffering/playing look for a reanchor at ANY track position and never
    // trips it on a pause, so downstream consumers (ProgressRail
    // playing = isPlaying || isBuffering; NowPlayingFocus
    // effectivePaused = paused && !isBuffering; SourceBadge) read playing during
    // a reanchor and paused on a real pause.
    val isBuffering = playbackState == PlaybackState.BUFFERING &&
        !metadata.isEmpty &&
        playWhenReady
    val controlsEnabled = playbackState == PlaybackState.READY || playbackState == PlaybackState.BUFFERING

    // Get server name from connection state
    val serverName = when (val state = connectionState) {
        is AppConnectionState.Connecting -> state.serverName
        is AppConnectionState.Connected -> state.serverName
        is AppConnectionState.Reconnecting -> state.serverName
        else -> ""
    }

    // Show connection loading overlay if connecting
    if (connectionState is AppConnectionState.Connecting) {
        ConnectionProgress(
            serverName = serverName,
            modifier = modifier
        )
        return
    }

    // A connection with no roles has nothing for the transport controls to
    // act on, so replace them with what the operator has to do instead. Only
    // while actually connected: the reconnect and error states have their own
    // messaging, and a stale notice on top of those would contradict them.
    if (connectionState is AppConnectionState.Connected &&
        admissionState != AdmissionState.READY
    ) {
        AdmissionNotice(
            state = admissionState,
            serverName = serverName,
            pairingCode = pairingCode,
            gestureRequested = pairingGestureRequested,
            onOpenPairingClick = onOpenPairingClick,
            onAllowPairingClick = onAllowPairingClick,
            lastFailure = (lastPairingOutcome as? PairingOutcome.Aborted)
                ?.message(LocalContext.current.resources),
            modifier = modifier
        )
        return
    }

    // Determine accent color from player colors
    val accentColor = playerColors?.let { Color(it.accentColor) }
    // NOTE: accentColor is currently ALWAYS null -- the artwork-derived accent
    // pipeline is not wired up. Nothing populates the ViewModel's
    // _playerColors: updatePlayerColors() has no callers repo-wide, and
    // MainActivity's Palette extraction (extractAndApplyColors) writes only to
    // the legacy View bindings (volume slider, coordinator layout), never to
    // the ViewModel. So every downstream consumer -- TV ambient wash and
    // album-art glow, progress bar gradient, volume slider tint -- renders the
    // hardcoded brand accent (NowPlayingTvScreen.FallbackAccent) at all times.
    // The sticky-accent machinery below is therefore inert scaffolding, kept
    // for whenever the pipeline is revived: it would hold the last non-null
    // value so the surface doesn't flash through FallbackAccent while a new
    // track's palette is computed. Mirrors the stickyMetadata /
    // stickyArtworkSource pattern above.
    var stickyAccentColor by remember { mutableStateOf(accentColor) }
    LaunchedEffect(accentColor, isActivelyConnected) {
        if (!isActivelyConnected || accentColor != null) {
            stickyAccentColor = accentColor
        }
    }

    // Check orientation and form factor
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val formFactor = LocalFormFactor.current

    Box(modifier = modifier.fillMaxSize()) {
        when {
            // Head unit: portrait layout with large touch targets
            formFactor == FormFactor.HEADUNIT -> {
                NowPlayingHeadUnit(
                    metadata = stickyMetadata,
                    groupName = groupName,
                    artworkSource = stickyArtworkSource,
                    isBuffering = isBuffering,
                    isPlaying = isPlaying,
                    controlsEnabled = controlsEnabled,
                    accentColor = stickyAccentColor,
                    positionMs = positionMs,
                    durationMs = durationMs,
                    positionUpdatedAt = positionUpdatedAt,
                    onPreviousClick = onPreviousClick,
                    onPlayPauseClick = onPlayPauseClick,
                    onNextClick = onNextClick,
                    onSwitchGroupClick = onSwitchGroupClick
                )
            }
            // TV: cinematic layout
            formFactor == FormFactor.TV -> {
                NowPlayingTv(
                    metadata = stickyMetadata,
                    groupName = groupName,
                    artworkSource = stickyArtworkSource,
                    isBuffering = isBuffering,
                    isPlaying = isPlaying,
                    accentColor = stickyAccentColor,
                    positionMs = positionMs,
                    durationMs = durationMs,
                    positionUpdatedAt = positionUpdatedAt,
                    audioSpec = stickyAudioSpec,
                    connectionState = connectionState
                )
            }
            isLandscape -> {
                NowPlayingLandscape(
                    metadata = stickyMetadata,
                    groupName = groupName,
                    artworkSource = stickyArtworkSource,
                    isBuffering = isBuffering,
                    isPlaying = isPlaying,
                    controlsEnabled = controlsEnabled,
                    volume = volume,
                    accentColor = stickyAccentColor,
                    positionMs = positionMs,
                    durationMs = durationMs,
                    positionUpdatedAt = positionUpdatedAt,
                    onPreviousClick = onPreviousClick,
                    onPlayPauseClick = onPlayPauseClick,
                    onNextClick = onNextClick,
                    onSwitchGroupClick = onSwitchGroupClick,
                    onVolumeChange = onVolumeChange
                )
            }
            else -> {
                NowPlayingPortrait(
                    metadata = stickyMetadata,
                    groupName = groupName,
                    artworkSource = stickyArtworkSource,
                    isBuffering = isBuffering,
                    isPlaying = isPlaying,
                    controlsEnabled = controlsEnabled,
                    volume = volume,
                    accentColor = stickyAccentColor,
                    positionMs = positionMs,
                    durationMs = durationMs,
                    positionUpdatedAt = positionUpdatedAt,
                    onPreviousClick = onPreviousClick,
                    onPlayPauseClick = onPlayPauseClick,
                    onNextClick = onNextClick,
                    onSwitchGroupClick = onSwitchGroupClick,
                    onVolumeChange = onVolumeChange
                )
            }
        }

        // Reconnecting banner overlay at top. overscanSafe adds 48dp on TV so
        // the banner doesn't sit inside the panel's overscan zone; no-op on
        // phone / tablet / head-unit. The 16dp internal padding shapes the
        // gap between the safe-area edge and the banner pill.
        reconnectingState?.let { state ->
            ReconnectingBanner(
                state = state,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .overscanSafe()
                    .padding(16.dp)
            )
        }
    }
}

/**
 * Portrait layout: Album art at top, controls below.
 */
@Composable
private fun NowPlayingPortrait(
    metadata: TrackMetadata,
    groupName: String,
    artworkSource: ArtworkSource?,
    isBuffering: Boolean,
    isPlaying: Boolean,
    controlsEnabled: Boolean,
    volume: Float,
    accentColor: Color?,
    positionMs: Long,
    durationMs: Long,
    positionUpdatedAt: Long = 0L,
    onPreviousClick: () -> Unit,
    onPlayPauseClick: () -> Unit,
    onNextClick: () -> Unit,
    onSwitchGroupClick: () -> Unit,
    onVolumeChange: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(16.dp))

        // Album Art
        AlbumArtCard(
            artworkSource = artworkSource,
            isBuffering = isBuffering,
            modifier = Modifier.fillMaxWidth(0.7f)
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Track Title
        Text(
            text = metadata.title.ifEmpty { stringResource(R.string.not_playing) },
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            letterSpacing = (-0.02).sp,
            modifier = Modifier
                .fillMaxWidth()
                .semantics { liveRegion = LiveRegionMode.Polite }
        )

        Spacer(modifier = Modifier.height(4.dp))

        // Artist / Album
        val metadataText = buildMetadataString(metadata.artist, metadata.album)
        if (metadataText.isNotEmpty()) {
            Text(
                text = metadataText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { liveRegion = LiveRegionMode.Polite }
            )
        }

        // Group Name
        if (groupName.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.group_label, groupName),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Track Progress + Speed Control
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            TrackProgressBar(
                positionMs = positionMs,
                durationMs = durationMs,
                isPlaying = isPlaying,
                accentColor = accentColor,
                positionUpdatedAt = positionUpdatedAt
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Playback Controls
        val formFactor = LocalFormFactor.current
        PlaybackControls(
            isPlaying = isPlaying,
            isEnabled = controlsEnabled,
            onPreviousClick = onPreviousClick,
            onPlayPauseClick = onPlayPauseClick,
            onNextClick = onNextClick,
            showSecondaryRow = true,
            isSwitchGroupEnabled = controlsEnabled,
            onSwitchGroupClick = onSwitchGroupClick,
            playButtonSize = AdaptiveDefaults.playButtonSize(formFactor),
            controlButtonSize = AdaptiveDefaults.controlButtonSize(formFactor)
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Volume Slider
        VolumeSlider(
            volume = volume,
            onVolumeChange = onVolumeChange,
            enabled = controlsEnabled,
            accentColor = accentColor
        )

        Spacer(modifier = Modifier.height(16.dp))
    }
}

/**
 * Landscape layout: Album art on left, controls on right.
 */
@Composable
private fun NowPlayingLandscape(
    metadata: TrackMetadata,
    groupName: String,
    artworkSource: ArtworkSource?,
    isBuffering: Boolean,
    isPlaying: Boolean,
    controlsEnabled: Boolean,
    volume: Float,
    accentColor: Color?,
    positionMs: Long,
    durationMs: Long,
    positionUpdatedAt: Long = 0L,
    onPreviousClick: () -> Unit,
    onPlayPauseClick: () -> Unit,
    onNextClick: () -> Unit,
    onSwitchGroupClick: () -> Unit,
    onVolumeChange: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Left: Album Art (square, full height)
        AlbumArtCard(
            artworkSource = artworkSource,
            isBuffering = isBuffering,
            modifier = Modifier.fillMaxHeight()
        )

        Spacer(modifier = Modifier.width(24.dp))

        // Right: Track Info + Controls
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // Track Title
            Text(
                text = metadata.title.ifEmpty { stringResource(R.string.not_playing) },
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                letterSpacing = (-0.02).sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { liveRegion = LiveRegionMode.Polite }
            )

            Spacer(modifier = Modifier.height(6.dp))

            // Artist / Album
            val metadataText = buildMetadataString(metadata.artist, metadata.album)
            if (metadataText.isNotEmpty()) {
                Text(
                    text = metadataText,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // Group Name
            if (groupName.isNotEmpty()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.group_label, groupName),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Track Progress + Speed Control
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                TrackProgressBar(
                    positionMs = positionMs,
                    durationMs = durationMs,
                    isPlaying = isPlaying,
                    accentColor = accentColor,
                    positionUpdatedAt = positionUpdatedAt
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Playback Controls (horizontal layout in landscape)
            val formFactor = LocalFormFactor.current
            PlaybackControls(
                isPlaying = isPlaying,
                isEnabled = controlsEnabled,
                onPreviousClick = onPreviousClick,
                onPlayPauseClick = onPlayPauseClick,
                onNextClick = onNextClick,
                isSwitchGroupEnabled = controlsEnabled,
                onSwitchGroupClick = onSwitchGroupClick,
                playButtonSize = AdaptiveDefaults.playButtonSize(formFactor),
                controlButtonSize = AdaptiveDefaults.controlButtonSize(formFactor)
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Volume Slider
            VolumeSlider(
                volume = volume,
                onVolumeChange = onVolumeChange,
                enabled = controlsEnabled,
                accentColor = accentColor
            )
        }
    }
}

/**
 * Builds the metadata string from artist and album.
 * Format: "Artist" or "Artist - Album" or "Album"
 */
internal fun buildMetadataString(artist: String, album: String): String {
    return buildString {
        if (artist.isNotEmpty()) append(artist)
        if (album.isNotEmpty()) {
            if (isNotEmpty()) append(" \u2022 ") // bullet separator
            append(album)
        }
    }
}

// ============================================================================
// Previews
// ============================================================================

@Preview(showBackground = true, widthDp = 360, heightDp = 640)
@Composable
private fun NowPlayingPortraitPreview() {
    SendSpinTheme {
        NowPlayingPortrait(
            metadata = TrackMetadata(
                title = "Bohemian Rhapsody",
                artist = "Queen",
                album = "A Night at the Opera"
            ),
            groupName = "Living Room",
            artworkSource = null,
            isBuffering = false,
            isPlaying = true,
            controlsEnabled = true,
            volume = 0.75f,
            accentColor = null,
            positionMs = 45000,
            durationMs = 354000,
            onPreviousClick = {},
            onPlayPauseClick = {},
            onNextClick = {},
            onSwitchGroupClick = {},
            onVolumeChange = {}
        )
    }
}

@Preview(showBackground = true, widthDp = 640, heightDp = 360)
@Composable
private fun NowPlayingLandscapePreview() {
    SendSpinTheme {
        NowPlayingLandscape(
            metadata = TrackMetadata(
                title = "Stairway to Heaven",
                artist = "Led Zeppelin",
                album = "Led Zeppelin IV"
            ),
            groupName = "",
            artworkSource = null,
            isBuffering = false,
            isPlaying = false,
            controlsEnabled = true,
            volume = 0.5f,
            accentColor = null,
            positionMs = 120000,
            durationMs = 482000,
            onPreviousClick = {},
            onPlayPauseClick = {},
            onNextClick = {},
            onSwitchGroupClick = {},
            onVolumeChange = {}
        )
    }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 640)
@Composable
private fun NowPlayingBufferingPreview() {
    SendSpinTheme {
        NowPlayingPortrait(
            metadata = TrackMetadata.EMPTY,
            groupName = "",
            artworkSource = null,
            isBuffering = true,
            isPlaying = false,
            controlsEnabled = false,
            volume = 0.75f,
            accentColor = null,
            positionMs = 0,
            durationMs = 0,
            onPreviousClick = {},
            onPlayPauseClick = {},
            onNextClick = {},
            onSwitchGroupClick = {},
            onVolumeChange = {}
        )
    }
}

// -- Multi-Device Previews --

private val previewMetadata = TrackMetadata(
    title = "Bohemian Rhapsody",
    artist = "Queen",
    album = "A Night at the Opera"
)

@AllDevicePreviews
@Composable
private fun NowPlayingAllDevicesPortraitPreview() {
    SendSpinTheme {
        NowPlayingPortrait(
            metadata = previewMetadata,
            groupName = "Living Room",
            artworkSource = null,
            isBuffering = false,
            isPlaying = true,
            controlsEnabled = true,
            volume = 0.75f,
            accentColor = null,
            positionMs = 45000,
            durationMs = 354000,
            onPreviousClick = {},
            onPlayPauseClick = {},
            onNextClick = {},
            onSwitchGroupClick = {},
            onVolumeChange = {}
        )
    }
}

@AllDevicePreviews
@Composable
private fun NowPlayingAllDevicesLandscapePreview() {
    SendSpinTheme {
        NowPlayingLandscape(
            metadata = previewMetadata,
            groupName = "Living Room",
            artworkSource = null,
            isBuffering = false,
            isPlaying = true,
            controlsEnabled = true,
            volume = 0.75f,
            accentColor = null,
            positionMs = 45000,
            durationMs = 354000,
            onPreviousClick = {},
            onPlayPauseClick = {},
            onNextClick = {},
            onSwitchGroupClick = {},
            onVolumeChange = {}
        )
    }
}
