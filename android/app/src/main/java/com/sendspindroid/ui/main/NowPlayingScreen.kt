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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
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
import com.sendspindroid.sendspin.protocol.AdmissionState
import com.sendspindroid.model.AppConnectionState
import com.sendspindroid.ui.adaptive.AdaptiveDefaults
import com.sendspindroid.ui.adaptive.FormFactor
import com.sendspindroid.ui.adaptive.LocalFormFactor
import com.sendspindroid.ui.adaptive.TvInitialFocus
import com.sendspindroid.ui.adaptive.overscanSafe
import com.sendspindroid.ui.adaptive.tvFocusable
import com.sendspindroid.ui.main.components.AlbumArtCard
import com.sendspindroid.ui.main.components.ConnectionProgress
import com.sendspindroid.ui.main.components.PlaybackControls
import com.sendspindroid.ui.main.components.ReconnectingBanner
import com.sendspindroid.ui.main.components.TrackProgressBar
import com.sendspindroid.ui.main.components.TvTrackProgressBar
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

    // Don't show buffering spinner when paused -- SendSpin's audio stream stops on
    // pause, so Media3 reports STATE_BUFFERING even though the user intentionally paused.
    val isBuffering = playbackState == PlaybackState.BUFFERING && !metadata.isEmpty && isPlaying
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
            modifier = modifier
        )
        return
    }

    // Determine accent color from player colors
    val accentColor = playerColors?.let { Color(it.accentColor) }

    // Check orientation and form factor
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val formFactor = LocalFormFactor.current

    Box(modifier = modifier.fillMaxSize()) {
        when {
            // Head unit: portrait layout with large touch targets
            formFactor == FormFactor.HEADUNIT -> {
                NowPlayingHeadUnit(
                    metadata = metadata,
                    groupName = groupName,
                    artworkSource = artworkSource,
                    isBuffering = isBuffering,
                    isPlaying = isPlaying,
                    controlsEnabled = controlsEnabled,
                    accentColor = accentColor,
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
                    metadata = metadata,
                    groupName = groupName,
                    artworkSource = artworkSource,
                    isBuffering = isBuffering,
                    isPlaying = isPlaying,
                    controlsEnabled = controlsEnabled,
                    accentColor = accentColor,
                    positionMs = positionMs,
                    durationMs = durationMs,
                    positionUpdatedAt = positionUpdatedAt,
                    onPreviousClick = onPreviousClick,
                    onPlayPauseClick = onPlayPauseClick,
                    onNextClick = onNextClick,
                    onSwitchGroupClick = onSwitchGroupClick
                )
            }
            isLandscape -> {
                NowPlayingLandscape(
                    metadata = metadata,
                    groupName = groupName,
                    artworkSource = artworkSource,
                    isBuffering = isBuffering,
                    isPlaying = isPlaying,
                    controlsEnabled = controlsEnabled,
                    volume = volume,
                    accentColor = accentColor,
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
                    metadata = metadata,
                    groupName = groupName,
                    artworkSource = artworkSource,
                    isBuffering = isBuffering,
                    isPlaying = isPlaying,
                    controlsEnabled = controlsEnabled,
                    volume = volume,
                    accentColor = accentColor,
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

        // Reconnecting banner overlay at top
        reconnectingState?.let { state ->
            ReconnectingBanner(
                state = state,
                modifier = Modifier
                    .align(Alignment.TopCenter)
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
 * TV cinematic layout: Large album art left, metadata + controls right.
 * No volume slider (TV remote handles volume). Visual progress bar.
 * D-pad focus management with focus rings on all interactive elements.
 */
@Composable
private fun NowPlayingTv(
    metadata: TrackMetadata,
    groupName: String,
    artworkSource: ArtworkSource?,
    isBuffering: Boolean,
    isPlaying: Boolean,
    controlsEnabled: Boolean,
    accentColor: Color?,
    positionMs: Long,
    durationMs: Long,
    positionUpdatedAt: Long = 0L,
    onPreviousClick: () -> Unit,
    onPlayPauseClick: () -> Unit,
    onNextClick: () -> Unit,
    onSwitchGroupClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val formFactor = LocalFormFactor.current
    val playFocusRequester = remember { FocusRequester() }

    // Auto-focus Play button on first composition
    TvInitialFocus(playFocusRequester)

    Row(
        modifier = modifier
            .fillMaxSize()
            .overscanSafe(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Main content area
        Row(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Left: Album Art
            AlbumArtCard(
                artworkSource = artworkSource,
                isBuffering = isBuffering,
                maxWidth = AdaptiveDefaults.albumArtMaxSize(formFactor),
                modifier = Modifier.fillMaxHeight()
            )

            Spacer(modifier = Modifier.width(32.dp))

            // Right: Metadata + Controls
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
                    fontSize = AdaptiveDefaults.titleTextSize(formFactor),
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
                        fontSize = AdaptiveDefaults.bodyTextSize(formFactor),
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
                        fontSize = AdaptiveDefaults.captionTextSize(formFactor),
                        color = MaterialTheme.colorScheme.primary,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))

                // TV Progress Bar (visual bar + timestamps)
                TvTrackProgressBar(
                    positionMs = positionMs,
                    durationMs = durationMs,
                    isPlaying = isPlaying,
                    accentColor = accentColor,
                    positionUpdatedAt = positionUpdatedAt
                )

                Spacer(modifier = Modifier.height(24.dp))

                // Transport Controls (TV-sized)
                PlaybackControls(
                    isPlaying = isPlaying,
                    isEnabled = controlsEnabled,
                    onPreviousClick = onPreviousClick,
                    onPlayPauseClick = onPlayPauseClick,
                    onNextClick = onNextClick,
                    showSecondaryRow = false,
                    playButtonSize = AdaptiveDefaults.playButtonSize(formFactor),
                    controlButtonSize = AdaptiveDefaults.controlButtonSize(formFactor),
                    buttonGap = 24.dp,
                    playFocusRequester = playFocusRequester,
                    isSwitchGroupEnabled = controlsEnabled,
                    onSwitchGroupClick = onSwitchGroupClick
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Secondary Controls Row: Switch Group
                val secondarySize = AdaptiveDefaults.secondaryButtonSize(formFactor)
                Row(
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Switch Group
                    FilledTonalIconButton(
                        onClick = onSwitchGroupClick,
                        enabled = controlsEnabled,
                        modifier = Modifier
                            .size(secondarySize)
                            .tvFocusable()
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_swap_horiz),
                            contentDescription = stringResource(R.string.accessibility_switch_group_button),
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
            }
        }
    }
}

/**
 * Builds the metadata string from artist and album.
 * Format: "Artist" or "Artist - Album" or "Album"
 */
private fun buildMetadataString(artist: String, album: String): String {
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
