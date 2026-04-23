package com.sendspindroid.ui.main

import android.content.res.Configuration
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
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
import com.sendspindroid.model.AppConnectionState
import com.sendspindroid.ui.adaptive.AdaptiveDefaults
import com.sendspindroid.ui.adaptive.FormFactor
import com.sendspindroid.ui.adaptive.LocalFormFactor
import com.sendspindroid.ui.main.components.AlbumArtCard
import com.sendspindroid.ui.main.components.ConnectionProgress
import com.sendspindroid.ui.main.components.PlaybackControls
import com.sendspindroid.ui.main.components.QueueButton
import com.sendspindroid.ui.main.components.ReconnectingBanner
import com.sendspindroid.ui.main.components.TrackProgressBar
import com.sendspindroid.ui.main.components.VolumeSlider
import com.sendspindroid.ui.preview.AllDevicePreviews
import com.sendspindroid.ui.preview.TabletPreviews
import com.sendspindroid.ui.queue.QueueSheetContent
import com.sendspindroid.ui.queue.QueueViewModel
import com.sendspindroid.ui.theme.SendSpinTheme

/**
 * Now Playing screen showing album art, track info, and playback controls.
 * Adapts layout based on form factor and orientation:
 * - Phone portrait: Album art at top, controls below
 * - Phone landscape: Album art on left, controls on right
 * - Tablet: Now Playing controls on left, inline queue panel on right
 */
@Composable
fun NowPlayingScreen(
    viewModel: MainActivityViewModel,
    onPreviousClick: () -> Unit,
    onPlayPauseClick: () -> Unit,
    onNextClick: () -> Unit,
    onSwitchGroupClick: () -> Unit,
    onFavoriteClick: () -> Unit,
    onVolumeChange: (Float) -> Unit,
    onQueueClick: () -> Unit,
    queueViewModel: QueueViewModel? = null,
    onBrowseLibrary: () -> Unit = {},
    showPlayerButton: Boolean = false,
    onPlayerClick: () -> Unit = {},
    inlineQueueVisible: Boolean = true,
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
    val isMaConnected by viewModel.isMaConnected.collectAsStateWithLifecycle()
    val playerColors by viewModel.playerColors.collectAsStateWithLifecycle()
    val positionMs by viewModel.positionMs.collectAsStateWithLifecycle()
    val durationMs by viewModel.durationMs.collectAsStateWithLifecycle()
    val positionUpdatedAt by viewModel.positionUpdatedAt.collectAsStateWithLifecycle()
    val audioStreamSpec by viewModel.audioStreamSpec.collectAsStateWithLifecycle()
    // SendSpin fires stream/end on pause, which zeroes the audio spec. Keep the
    // last non-null value so the codec/bit-depth/sample-rate chips stay up while
    // paused, and reset only when the track itself changes.
    var stickyAudioSpec by remember(metadata.title, metadata.artist, metadata.album) {
        mutableStateOf(audioStreamSpec)
    }
    LaunchedEffect(audioStreamSpec) {
        if (audioStreamSpec != null) stickyAudioSpec = audioStreamSpec
    }
    // Optimistic metadata update: when a queue item is tapped, update the UI
    // immediately with the item's metadata instead of waiting for the server round-trip.
    LaunchedEffect(queueViewModel) {
        queueViewModel?.playedItem?.collect { item ->
            viewModel.updateMetadata(item.name, item.artist ?: "", item.album ?: "")
            item.imageUri?.takeIf { it.isNotEmpty() }?.let { url ->
                viewModel.updateArtwork(ArtworkSource.Url(url))
            }
            item.duration?.let { durationSec ->
                viewModel.updateTrackProgress(
                    positionMs = 0,
                    durationMs = durationSec * 1000,
                    positionUpdatedAt = 0
                )
            }
        }
    }

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

    // Determine accent color from player colors
    val accentColor = playerColors?.let { Color(it.accentColor) }

    // Check orientation and form factor
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val formFactor = LocalFormFactor.current
    // Resolve inline queue: non-null only when tablet + MA connected + ViewModel available
    val inlineQueueViewModel = if (
        AdaptiveDefaults.showInlineQueuePanel(formFactor) && isMaConnected
    ) queueViewModel else null

    Box(modifier = modifier.fillMaxSize()) {
        when {
            // Head unit: portrait layout with large touch targets + queue peek
            formFactor == FormFactor.HEADUNIT -> {
                NowPlayingHeadUnit(
                    metadata = metadata,
                    groupName = groupName,
                    artworkSource = artworkSource,
                    isBuffering = isBuffering,
                    isPlaying = isPlaying,
                    controlsEnabled = controlsEnabled,
                    accentColor = accentColor,
                    isMaConnected = isMaConnected,
                    positionMs = positionMs,
                    durationMs = durationMs,
                    positionUpdatedAt = positionUpdatedAt,
                    onPreviousClick = onPreviousClick,
                    onPlayPauseClick = onPlayPauseClick,
                    onNextClick = onNextClick,
                    onSwitchGroupClick = onSwitchGroupClick,
                    onFavoriteClick = onFavoriteClick,
                    queueViewModel = queueViewModel
                )
            }
            // TV with MA connected: cinematic layout with toggleable queue sidebar
            formFactor == FormFactor.TV -> {
                NowPlayingTv(
                    metadata = metadata,
                    groupName = groupName,
                    artworkSource = artworkSource,
                    isBuffering = isBuffering,
                    isPlaying = isPlaying,
                    controlsEnabled = controlsEnabled,
                    accentColor = accentColor,
                    isMaConnected = isMaConnected,
                    positionMs = positionMs,
                    durationMs = durationMs,
                    positionUpdatedAt = positionUpdatedAt,
                    audioSpec = stickyAudioSpec,
                    onPreviousClick = onPreviousClick,
                    onPlayPauseClick = onPlayPauseClick,
                    onNextClick = onNextClick,
                    onSwitchGroupClick = onSwitchGroupClick,
                    onFavoriteClick = onFavoriteClick,
                    queueViewModel = queueViewModel,
                    onBrowseLibrary = onBrowseLibrary,
                    showPlayerButton = showPlayerButton,
                    onPlayerClick = onPlayerClick
                )
            }
            // TV without MA: landscape layout, no queue
            formFactor == FormFactor.TV -> {
                NowPlayingLandscape(
                    metadata = metadata,
                    groupName = groupName,
                    artworkSource = artworkSource,
                    isBuffering = isBuffering,
                    isPlaying = isPlaying,
                    controlsEnabled = controlsEnabled,
                    volume = volume,
                    accentColor = accentColor,
                    isMaConnected = isMaConnected,
                    positionMs = positionMs,
                    durationMs = durationMs,
                    positionUpdatedAt = positionUpdatedAt,
                    onPreviousClick = onPreviousClick,
                    onPlayPauseClick = onPlayPauseClick,
                    onNextClick = onNextClick,
                    onSwitchGroupClick = onSwitchGroupClick,
                    onFavoriteClick = onFavoriteClick,
                    onVolumeChange = onVolumeChange,
                    onQueueClick = onQueueClick,
                    showQueueButton = false,
                    showPlayerButton = showPlayerButton,
                    onPlayerClick = onPlayerClick
                )
            }
            // Tablet: inline queue panel always visible
            inlineQueueViewModel != null -> {
                NowPlayingWithQueuePanel(
                    metadata = metadata,
                    groupName = groupName,
                    artworkSource = artworkSource,
                    isBuffering = isBuffering,
                    isPlaying = isPlaying,
                    controlsEnabled = controlsEnabled,
                    volume = volume,
                    accentColor = accentColor,
                    isMaConnected = isMaConnected,
                    positionMs = positionMs,
                    durationMs = durationMs,
                    positionUpdatedAt = positionUpdatedAt,
                    onPreviousClick = onPreviousClick,
                    onPlayPauseClick = onPlayPauseClick,
                    onNextClick = onNextClick,
                    onSwitchGroupClick = onSwitchGroupClick,
                    onFavoriteClick = onFavoriteClick,
                    onVolumeChange = onVolumeChange,
                    queueViewModel = inlineQueueViewModel,
                    onBrowseLibrary = onBrowseLibrary,
                    showPlayerButton = showPlayerButton,
                    onPlayerClick = onPlayerClick,
                    queueVisible = inlineQueueVisible
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
                    isMaConnected = isMaConnected,
                    positionMs = positionMs,
                    durationMs = durationMs,
                    positionUpdatedAt = positionUpdatedAt,
                    onPreviousClick = onPreviousClick,
                    onPlayPauseClick = onPlayPauseClick,
                    onNextClick = onNextClick,
                    onSwitchGroupClick = onSwitchGroupClick,
                    onFavoriteClick = onFavoriteClick,
                    onVolumeChange = onVolumeChange,
                    onQueueClick = onQueueClick,
                    showPlayerButton = showPlayerButton,
                    onPlayerClick = onPlayerClick
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
                    isMaConnected = isMaConnected,
                    positionMs = positionMs,
                    durationMs = durationMs,
                    positionUpdatedAt = positionUpdatedAt,
                    onPreviousClick = onPreviousClick,
                    onPlayPauseClick = onPlayPauseClick,
                    onNextClick = onNextClick,
                    onSwitchGroupClick = onSwitchGroupClick,
                    onFavoriteClick = onFavoriteClick,
                    onVolumeChange = onVolumeChange,
                    onQueueClick = onQueueClick,
                    showPlayerButton = showPlayerButton,
                    onPlayerClick = onPlayerClick
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
    isMaConnected: Boolean,
    positionMs: Long,
    durationMs: Long,
    positionUpdatedAt: Long = 0L,
    onPreviousClick: () -> Unit,
    onPlayPauseClick: () -> Unit,
    onNextClick: () -> Unit,
    onSwitchGroupClick: () -> Unit,
    onFavoriteClick: () -> Unit,
    onVolumeChange: (Float) -> Unit,
    onQueueClick: () -> Unit,
    showQueueButton: Boolean = true,
    isQueueActive: Boolean = false,
    albumArtFraction: Float = 0.7f,
    compactControls: Boolean = false,
    showPlayerButton: Boolean = false,
    onPlayerClick: () -> Unit = {},
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
            modifier = Modifier.fillMaxWidth(albumArtFraction)
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
            compactLayout = compactControls,
            isSwitchGroupEnabled = controlsEnabled,
            onSwitchGroupClick = onSwitchGroupClick,
            showFavorite = isMaConnected,
            isFavorite = false, // TODO: Track favorite state
            onFavoriteClick = onFavoriteClick,
            showPlayerButton = showPlayerButton,
            onPlayerClick = onPlayerClick,
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

        Spacer(modifier = Modifier.height(12.dp))

        // Queue button
        if (isMaConnected && showQueueButton) {
            QueueButton(onClick = onQueueClick, isActive = isQueueActive)
        }

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
    isMaConnected: Boolean,
    positionMs: Long,
    durationMs: Long,
    positionUpdatedAt: Long = 0L,
    onPreviousClick: () -> Unit,
    onPlayPauseClick: () -> Unit,
    onNextClick: () -> Unit,
    onSwitchGroupClick: () -> Unit,
    onFavoriteClick: () -> Unit,
    onVolumeChange: (Float) -> Unit,
    onQueueClick: () -> Unit,
    showQueueButton: Boolean = true,
    showSecondaryRow: Boolean = true,
    showPlayerButton: Boolean = false,
    onPlayerClick: () -> Unit = {},
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
                showSecondaryRow = showSecondaryRow,
                isSwitchGroupEnabled = controlsEnabled,
                onSwitchGroupClick = onSwitchGroupClick,
                showFavorite = isMaConnected,
                isFavorite = false,
                onFavoriteClick = onFavoriteClick,
                showPlayerButton = showPlayerButton,
                onPlayerClick = onPlayerClick,
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

            // Queue button (hidden when inline queue panel is visible on tablets)
            if (isMaConnected && showQueueButton) {
                Spacer(modifier = Modifier.height(8.dp))
                QueueButton(onClick = onQueueClick)
            }
        }
    }
}

/**
 * Tablet layout: Now Playing controls on left, inline queue panel on right.
 * Uses portrait-style layout for the controls column regardless of device orientation,
 * since the column is narrow enough that a vertical stack works best.
 */
@Composable
private fun NowPlayingWithQueuePanel(
    metadata: TrackMetadata,
    groupName: String,
    artworkSource: ArtworkSource?,
    isBuffering: Boolean,
    isPlaying: Boolean,
    controlsEnabled: Boolean,
    volume: Float,
    accentColor: Color?,
    isMaConnected: Boolean,
    positionMs: Long,
    durationMs: Long,
    positionUpdatedAt: Long = 0L,
    onPreviousClick: () -> Unit,
    onPlayPauseClick: () -> Unit,
    onNextClick: () -> Unit,
    onSwitchGroupClick: () -> Unit,
    onFavoriteClick: () -> Unit,
    onVolumeChange: (Float) -> Unit,
    queueViewModel: QueueViewModel,
    onBrowseLibrary: () -> Unit,
    showPlayerButton: Boolean = false,
    onPlayerClick: () -> Unit = {},
    queueVisible: Boolean = true,
    modifier: Modifier = Modifier
) {
    val formFactor = LocalFormFactor.current
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    Row(modifier = modifier.fillMaxSize()) {
        // Left column: Now Playing controls (expands to fill when queue is hidden)
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
        ) {
            if (!queueVisible && isLandscape) {
                // Queue hidden in landscape: use side-by-side layout so controls fit vertically
                NowPlayingLandscape(
                    metadata = metadata,
                    groupName = groupName,
                    artworkSource = artworkSource,
                    isBuffering = isBuffering,
                    isPlaying = isPlaying,
                    controlsEnabled = controlsEnabled,
                    volume = volume,
                    accentColor = accentColor,
                    isMaConnected = isMaConnected,
                    positionMs = positionMs,
                    durationMs = durationMs,
                    positionUpdatedAt = positionUpdatedAt,
                    onPreviousClick = onPreviousClick,
                    onPlayPauseClick = onPlayPauseClick,
                    onNextClick = onNextClick,
                    onSwitchGroupClick = onSwitchGroupClick,
                    onFavoriteClick = onFavoriteClick,
                    onVolumeChange = onVolumeChange,
                    onQueueClick = {},
                    showQueueButton = false,
                    showSecondaryRow = true,
                    showPlayerButton = showPlayerButton,
                    onPlayerClick = onPlayerClick
                )
            } else {
                // Queue visible or portrait: portrait-style vertical stack fits the narrow column
                NowPlayingPortrait(
                    metadata = metadata,
                    groupName = groupName,
                    artworkSource = artworkSource,
                    isBuffering = isBuffering,
                    isPlaying = isPlaying,
                    controlsEnabled = controlsEnabled,
                    volume = volume,
                    accentColor = accentColor,
                    isMaConnected = isMaConnected,
                    positionMs = positionMs,
                    durationMs = durationMs,
                    positionUpdatedAt = positionUpdatedAt,
                    onPreviousClick = onPreviousClick,
                    onPlayPauseClick = onPlayPauseClick,
                    onNextClick = onNextClick,
                    onSwitchGroupClick = onSwitchGroupClick,
                    onFavoriteClick = onFavoriteClick,
                    onVolumeChange = onVolumeChange,
                    onQueueClick = {},
                    showQueueButton = false,
                    albumArtFraction = 0.5f,
                    compactControls = queueVisible,
                    showPlayerButton = showPlayerButton,
                    onPlayerClick = onPlayerClick
                )
            }
        }

        // Queue sidebar (animated)
        AnimatedVisibility(
            visible = queueVisible,
            enter = slideInHorizontally { it },
            exit = slideOutHorizontally { it }
        ) {
            Row(modifier = Modifier.fillMaxHeight()) {
                // Vertical divider
                VerticalDivider(
                    modifier = Modifier
                        .fillMaxHeight()
                        .padding(vertical = 16.dp),
                    color = MaterialTheme.colorScheme.outlineVariant
                )

                // Inline Queue
                Box(
                    modifier = Modifier
                        .width(AdaptiveDefaults.browseQueueSidebarWidth(formFactor))
                        .fillMaxHeight()
                ) {
                    QueueSheetContent(
                        viewModel = queueViewModel,
                        onBrowseLibrary = onBrowseLibrary,
                        currentTrackTitle = metadata.title
                    )
                }
            }
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
            isMaConnected = true,
            positionMs = 45000,
            durationMs = 354000,
            onPreviousClick = {},
            onPlayPauseClick = {},
            onNextClick = {},
            onSwitchGroupClick = {},
            onFavoriteClick = {},
            onVolumeChange = {},
            onQueueClick = {}
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
            isMaConnected = false,
            positionMs = 120000,
            durationMs = 482000,
            onPreviousClick = {},
            onPlayPauseClick = {},
            onNextClick = {},
            onSwitchGroupClick = {},
            onFavoriteClick = {},
            onVolumeChange = {},
            onQueueClick = {}
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
            isMaConnected = false,
            positionMs = 0,
            durationMs = 0,
            onPreviousClick = {},
            onPlayPauseClick = {},
            onNextClick = {},
            onSwitchGroupClick = {},
            onFavoriteClick = {},
            onVolumeChange = {},
            onQueueClick = {}
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
            isMaConnected = true,
            positionMs = 45000,
            durationMs = 354000,
            onPreviousClick = {},
            onPlayPauseClick = {},
            onNextClick = {},
            onSwitchGroupClick = {},
            onFavoriteClick = {},
            onVolumeChange = {},
            onQueueClick = {}
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
            isMaConnected = true,
            positionMs = 45000,
            durationMs = 354000,
            onPreviousClick = {},
            onPlayPauseClick = {},
            onNextClick = {},
            onSwitchGroupClick = {},
            onFavoriteClick = {},
            onVolumeChange = {},
            onQueueClick = {}
        )
    }
}
