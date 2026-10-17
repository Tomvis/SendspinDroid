package com.sendspindroid.ui.main

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import com.sendspindroid.sendspin.pairing.PairingCodeFormat
import com.sendspindroid.sendspin.protocol.AdmissionState
import com.sendspindroid.ui.settings.unfinishedMessage
import com.sendspindroid.model.AppConnectionState
import com.sendspindroid.ui.adaptive.AdaptiveDefaults
import com.sendspindroid.ui.adaptive.FormFactor
import com.sendspindroid.ui.adaptive.LocalFormFactor
import com.sendspindroid.ui.adaptive.TvOverscanHorizontal
import com.sendspindroid.ui.adaptive.TvOverscanVertical
import com.sendspindroid.ui.main.components.AlbumArtCard
import com.sendspindroid.ui.main.components.ConnectionProgress
import com.sendspindroid.ui.main.components.PlaybackControls
import com.sendspindroid.ui.main.components.ReconnectingBanner
import com.sendspindroid.ui.main.components.TrackProgressBar
import com.sendspindroid.ui.main.components.VolumeSlider
import com.sendspindroid.ui.preview.AllDevicePreviews
import com.sendspindroid.ui.preview.TabletPreviews
import com.sendspindroid.ui.theme.SendSpinTheme
import com.sendspindroid.ui.tv.LocalTvMenuOpen

/**
 * Now Playing screen showing album art, track info, and playback controls.
 * Adapts layout based on form factor and the shape of the space it has:
 * - Taller than wide: Album art at top, controls below
 * - Wider than tall: Album art on left, controls on right
 */
@Composable
fun NowPlayingScreen(
    viewModel: MainActivityViewModel,
    onPreviousClick: () -> Unit,
    onPlayPauseClick: () -> Unit,
    onNextClick: () -> Unit,
    onSwitchGroupClick: () -> Unit,
    onVolumeChange: (Float) -> Unit,
    onSeek: (Long) -> Unit,
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
    val playbackSpeed by viewModel.playbackSpeed.collectAsStateWithLifecycle()
    val seekable by viewModel.seekable.collectAsStateWithLifecycle()
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
    //
    // A pairing code or the Allow pairing prompt replaces them too, with
    // roles live. "Pairing can run alongside playback": a pairing
    // server/activate "does not by itself affect active_roles", so a player
    // with roles keeps them, and stays READY, all through the attempt. The
    // code goes out on the display, and "the display showing artwork" is an
    // output the client "suspends ... locally for the duration of the
    // attempt" (pairing.md, Entering and leaving pairing). The audio plays
    // on: only the display is advertised as an out-channel. Now Playing
    // returns whenever both are clear: at the attempt's end, and for the
    // round trip from the Allow pairing tap, which clears the prompt, to the
    // code.
    val pairingPrompt = pairingCode != null || pairingGestureRequested
    if (connectionState is AppConnectionState.Connected &&
        (admissionState != AdmissionState.READY || pairingPrompt)
    ) {
        AdmissionNotice(
            // READY gets here only with a prompt, which is PAIRING's to show
            state = if (admissionState == AdmissionState.READY) AdmissionState.PAIRING else admissionState,
            serverName = serverName,
            pairingCode = pairingCode?.code,
            pairingCodeFormat = pairingCode?.format ?: PairingCodeFormat.DIGITS,
            gestureRequested = pairingGestureRequested,
            onOpenPairingClick = onOpenPairingClick,
            onAllowPairingClick = onAllowPairingClick,
            lastFailure = lastPairingOutcome?.unfinishedMessage(LocalContext.current.resources),
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

    val configuration = LocalConfiguration.current
    val formFactor = LocalFormFactor.current

    // A line of its own, so only where there is the height for it
    val albumArtist = albumArtistToShow(metadata)
        ?.takeIf { showsAlbumArtist(formFactor, configuration.screenHeightDp) }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        // Goes by the space this screen has, not the device's orientation: a
        // floating or split window can be any shape.
        val isLandscape = maxWidth > maxHeight
        val compactHeight = maxHeight < COMPACT_HEIGHT
        val controlsFormFactor = controlsFormFactor(formFactor, compactHeight)
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
                    playbackSpeed = playbackSpeed,
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
                    albumArtist = albumArtist,
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
                    playbackSpeed = playbackSpeed,
                    onPreviousClick = onPreviousClick,
                    onPlayPauseClick = onPlayPauseClick,
                    onNextClick = onNextClick,
                    onSwitchGroupClick = onSwitchGroupClick,
                    onVolumeChange = onVolumeChange,
                    seekable = seekable && controlsEnabled,
                    onSeek = onSeek,
                    controlsFormFactor = controlsFormFactor,
                    compactHeight = compactHeight
                )
            }
            else -> {
                NowPlayingPortrait(
                    metadata = stickyMetadata,
                    albumArtist = albumArtist,
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
                    playbackSpeed = playbackSpeed,
                    onPreviousClick = onPreviousClick,
                    onPlayPauseClick = onPlayPauseClick,
                    onNextClick = onNextClick,
                    onSwitchGroupClick = onSwitchGroupClick,
                    onVolumeChange = onVolumeChange,
                    seekable = seekable && controlsEnabled,
                    onSeek = onSeek,
                    controlsFormFactor = controlsFormFactor
                )
            }
        }

        // Reconnecting banner overlay at top
        reconnectingState?.let { state ->
            ReconnectingBanner(
                state = state,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .then(
                        // Inside the part of the picture every TV shows
                        if (formFactor == FormFactor.TV) {
                            Modifier.padding(
                                start = TvOverscanHorizontal,
                                top = TvOverscanVertical,
                                end = TvOverscanHorizontal
                            )
                        } else {
                            Modifier.padding(16.dp)
                        }
                    )
            )
        }
    }
}

/**
 * Portrait layout: Album art at top, controls below. The art gives up height
 * first, so the controls stay on screen; once it has none left to give, the
 * screen scrolls.
 */
@Composable
private fun NowPlayingPortrait(
    metadata: TrackMetadata,
    albumArtist: String? = null,
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
    playbackSpeed: Int = 1000,
    onPreviousClick: () -> Unit,
    onPlayPauseClick: () -> Unit,
    onNextClick: () -> Unit,
    onSwitchGroupClick: () -> Unit,
    onVolumeChange: (Float) -> Unit,
    seekable: Boolean = false,
    onSeek: (Long) -> Unit = {},
    controlsFormFactor: FormFactor = LocalFormFactor.current,
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        // At least the screen's height, so the art's weight has that height to
        // share out even though a scrolling column has no maximum.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .heightIn(min = maxHeight)
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(16.dp))

            // Album Art: the height the rest leaves, up to 70% of the width
            Box(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .fillMaxWidth(0.7f),
                contentAlignment = Alignment.Center
            ) {
                AlbumArtCard(
                    artworkSource = artworkSource,
                    isBuffering = isBuffering
                )
            }

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
            val metadataText = buildMetadataString(metadata.artist, metadata.album, metadata.year)
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

            // Album Artist
            if (albumArtist != null) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = stringResource(R.string.album_artist_label, albumArtist),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth()
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
                    playbackSpeed = playbackSpeed,
                    accentColor = accentColor,
                    positionUpdatedAt = positionUpdatedAt,
                    seekable = seekable,
                    onSeek = onSeek
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Playback Controls
            PlaybackControls(
                isPlaying = isPlaying,
                isEnabled = controlsEnabled,
                onPreviousClick = onPreviousClick,
                onPlayPauseClick = onPlayPauseClick,
                onNextClick = onNextClick,
                showSecondaryRow = true,
                isSwitchGroupEnabled = controlsEnabled,
                onSwitchGroupClick = onSwitchGroupClick,
                playButtonSize = AdaptiveDefaults.playButtonSize(controlsFormFactor),
                controlButtonSize = AdaptiveDefaults.controlButtonSize(controlsFormFactor)
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
}

/**
 * Landscape layout: Album art on left, controls on right.
 */
@Composable
private fun NowPlayingLandscape(
    metadata: TrackMetadata,
    albumArtist: String? = null,
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
    playbackSpeed: Int = 1000,
    onPreviousClick: () -> Unit,
    onPlayPauseClick: () -> Unit,
    onNextClick: () -> Unit,
    onSwitchGroupClick: () -> Unit,
    onVolumeChange: (Float) -> Unit,
    seekable: Boolean = false,
    onSeek: (Long) -> Unit = {},
    controlsFormFactor: FormFactor = LocalFormFactor.current,
    compactHeight: Boolean = false,
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = if (compactHeight) 16.dp else 24.dp)) {
        val width = maxWidth
        val height = maxHeight
        Row(
            modifier = Modifier.fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Left: Album Art (square, full height), leaving the controls at
            // least 60% of a window that is barely wider than it is tall
            AlbumArtCard(
                artworkSource = artworkSource,
                isBuffering = isBuffering,
                modifier = Modifier.fillMaxHeight(),
                maxWidth = minOf(320.dp, width * 0.4f)
            )

            Spacer(modifier = Modifier.width(24.dp))

            // Right: Track Info + Controls, centred, scrolling when they do
            // not fit. The minimum height gives the centring room to work in
            // a scrolling column, which has no maximum.
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .heightIn(min = height),
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
                val metadataText = buildMetadataString(metadata.artist, metadata.album, metadata.year)
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

                // Album Artist
                if (albumArtist != null) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = stringResource(R.string.album_artist_label, albumArtist),
                        style = MaterialTheme.typography.bodyMedium,
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
                        playbackSpeed = playbackSpeed,
                        accentColor = accentColor,
                        positionUpdatedAt = positionUpdatedAt,
                        seekable = seekable,
                        onSeek = onSeek
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Playback Controls (horizontal layout in landscape)
                PlaybackControls(
                    isPlaying = isPlaying,
                    isEnabled = controlsEnabled,
                    onPreviousClick = onPreviousClick,
                    onPlayPauseClick = onPlayPauseClick,
                    onNextClick = onNextClick,
                    isSwitchGroupEnabled = controlsEnabled,
                    onSwitchGroupClick = onSwitchGroupClick,
                    playButtonSize = AdaptiveDefaults.playButtonSize(controlsFormFactor),
                    controlButtonSize = AdaptiveDefaults.controlButtonSize(controlsFormFactor),
                    secondaryInline = compactHeight
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
}

/**
 * Builds the metadata string from artist, album and year: the artist and the
 * album with a bullet between them, and the year after the album as " (1975)",
 * or as a segment of its own without an album. An empty part is left out, so
 * without a year the line is just artist and album.
 *
 * The year is "`year?`: integer - release year in YYYY format"
 * (roles/metadata/v1.md, Server -> Client: server/state metadata object).
 * aiosendspin accepts 0, which is no year, so 0 or less is left out.
 */
internal fun buildMetadataString(artist: String, album: String, year: Int?): String {
    val shownYear = year?.takeIf { it > 0 }
    return buildString {
        if (artist.isNotEmpty()) append(artist)
        if (album.isNotEmpty()) {
            if (isNotEmpty()) append(" \u2022 ") // bullet separator
            append(album)
            if (shownYear != null) append(" ($shownYear)")
        } else if (shownYear != null) {
            if (isNotEmpty()) append(" \u2022 ")
            append(shownYear)
        }
    }
}

/**
 * The album artist to show under the artist and album, or null. It is left
 * out when it is the track artist, case and surrounding space ignored: the
 * Media3 feed reports the track artist as the album artist when the server
 * sends none.
 */
internal fun albumArtistToShow(metadata: TrackMetadata): String? =
    metadata.albumArtist?.trim()
        ?.takeIf { it.isNotEmpty() && !it.equals(metadata.artist.trim(), ignoreCase = true) }

/**
 * Whether Now Playing has the height for the album-artist line: on a TV, and
 * on a tablet at least 600dp tall. [FormFactor] goes by width, so a phone on
 * its side reads as a tablet, but is too short for another line.
 */
internal fun showsAlbumArtist(formFactor: FormFactor, screenHeightDp: Int): Boolean =
    formFactor == FormFactor.TV ||
        ((formFactor == FormFactor.TABLET_7 || formFactor == FormFactor.TABLET_10) && screenHeightDp >= 600)

/**
 * The form factor to size the transport controls for. [FormFactor] goes by
 * width, so a phone on its side or a short floating window reads as a tablet;
 * when Now Playing is [compactHeight] (under [COMPACT_HEIGHT]) it gets the
 * phone's buttons, which leave room for the rest of the screen.
 */
internal fun controlsFormFactor(formFactor: FormFactor, compactHeight: Boolean): FormFactor =
    if ((formFactor == FormFactor.TABLET_7 || formFactor == FormFactor.TABLET_10) && compactHeight) {
        FormFactor.PHONE
    } else {
        formFactor
    }

private val COMPACT_HEIGHT = 480.dp

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
