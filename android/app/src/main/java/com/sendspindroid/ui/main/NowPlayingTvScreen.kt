package com.sendspindroid.ui.main

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
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
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sendspindroid.R
import com.sendspindroid.ui.adaptive.AdaptiveDefaults
import com.sendspindroid.ui.adaptive.LocalFormFactor
import com.sendspindroid.ui.adaptive.TvInitialFocus
import com.sendspindroid.ui.adaptive.overscanSafe
import com.sendspindroid.ui.adaptive.tvFocusable
import com.sendspindroid.ui.main.components.AlbumArtCard
import com.sendspindroid.ui.main.components.PlaybackControls
import com.sendspindroid.ui.main.components.TvTrackProgressBar
import com.sendspindroid.ui.queue.QueueSheetContent
import com.sendspindroid.ui.queue.QueueViewModel

/**
 * TV cinematic layout: Large album art left, metadata + controls right.
 * Toggleable queue sidebar slides in from the right.
 * No volume slider (TV remote handles volume). Visual progress bar.
 * D-pad focus management with focus rings on all interactive elements.
 */
@Composable
internal fun NowPlayingTv(
    metadata: TrackMetadata,
    groupName: String,
    artworkSource: ArtworkSource?,
    isBuffering: Boolean,
    isPlaying: Boolean,
    controlsEnabled: Boolean,
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
    queueViewModel: QueueViewModel?,
    onBrowseLibrary: () -> Unit,
    showPlayerButton: Boolean = false,
    onPlayerClick: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val formFactor = LocalFormFactor.current
    android.util.Log.d("NP-TV", "render meta=$metadata")
    var queueVisible by rememberSaveable { mutableStateOf(false) }
    val playFocusRequester = remember { FocusRequester() }
    val queueToggleFocusRequester = remember { FocusRequester() }

    // Auto-focus Play button on first composition
    TvInitialFocus(playFocusRequester)

    // Back handler to close queue sidebar
    BackHandler(enabled = queueVisible) {
        queueVisible = false
    }

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
                // Track number slug (above title)
                if (metadata.albumTrack > 0) {
                    Text(
                        text = stringResource(R.string.track_number_label, metadata.albumTrack),
                        fontSize = AdaptiveDefaults.captionTextSize(formFactor),
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        letterSpacing = 4.sp,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }

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

                // Artist
                if (metadata.artist.isNotEmpty()) {
                    Text(
                        text = metadata.artist,
                        fontSize = AdaptiveDefaults.bodyTextSize(formFactor),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                // Album · Year
                val albumLine = when {
                    metadata.album.isNotEmpty() && metadata.year > 0 ->
                        "${metadata.album} · ${metadata.year}"
                    metadata.album.isNotEmpty() -> metadata.album + " no year"
                    else -> ""
                }
                if (albumLine.isNotEmpty()) {
                    Text(
                        text = albumLine,
                        fontSize = AdaptiveDefaults.bodyTextSize(formFactor),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                // Album artist (only when different from artist)
                if (metadata.albumArtist.isNotEmpty() && metadata.albumArtist != metadata.artist) {
                    Text(
                        text = stringResource(R.string.album_artist_label, metadata.albumArtist),
                        fontSize = AdaptiveDefaults.captionTextSize(formFactor),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                // Queue position: "5 of 12 in queue"
                val queueLine = when {
                    metadata.queueTrack > 0 && metadata.totalTracks > 0 ->
                        stringResource(
                            R.string.queue_position_label,
                            metadata.queueTrack,
                            metadata.totalTracks
                        )
                    metadata.totalTracks > 0 ->
                        stringResource(R.string.queue_total_label, metadata.totalTracks)
                    else -> ""
                }
                if (queueLine.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = queueLine,
                        fontSize = AdaptiveDefaults.captionTextSize(formFactor),
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
                    onSwitchGroupClick = onSwitchGroupClick,
                    showFavorite = isMaConnected,
                    isFavorite = false,
                    onFavoriteClick = onFavoriteClick
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Secondary Controls Row: Switch Group, Favorite, Queue Toggle
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

                    Spacer(modifier = Modifier.width(12.dp))

                    // Favorite
                    if (isMaConnected) {
                        FilledTonalIconButton(
                            onClick = onFavoriteClick,
                            modifier = Modifier
                                .size(secondarySize)
                                .tvFocusable()
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_favorite_border),
                                contentDescription = stringResource(R.string.accessibility_favorite_track),
                                modifier = Modifier.size(24.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        Spacer(modifier = Modifier.width(12.dp))
                    }

                    // Speaker / Group button (MA only)
                    if (showPlayerButton) {
                        FilledTonalIconButton(
                            onClick = onPlayerClick,
                            modifier = Modifier
                                .size(secondarySize)
                                .tvFocusable()
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_speaker_group),
                                contentDescription = stringResource(R.string.accessibility_player_button),
                                modifier = Modifier.size(24.dp)
                            )
                        }

                        Spacer(modifier = Modifier.width(12.dp))
                    }

                    // Queue Toggle
                    if (queueViewModel != null) {
                        FilledTonalIconButton(
                            onClick = { queueVisible = !queueVisible },
                            modifier = Modifier
                                .size(secondarySize)
                                .tvFocusable(focusRequester = queueToggleFocusRequester)
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_queue_music),
                                contentDescription = stringResource(R.string.accessibility_queue_button),
                                modifier = Modifier.size(24.dp),
                                tint = if (queueVisible)
                                    MaterialTheme.colorScheme.primary
                                else
                                    MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }

        // Queue Sidebar (slides in from right)
        AnimatedVisibility(
            visible = queueVisible && queueViewModel != null,
            enter = slideInHorizontally { it },
            exit = slideOutHorizontally { it }
        ) {
            Row(modifier = Modifier.fillMaxHeight()) {
                VerticalDivider(
                    modifier = Modifier
                        .fillMaxHeight()
                        .padding(vertical = 16.dp),
                    color = MaterialTheme.colorScheme.outlineVariant
                )

                Box(
                    modifier = Modifier
                        .width(400.dp)
                        .fillMaxHeight()
                ) {
                    queueViewModel?.let { vm ->
                        QueueSheetContent(
                            viewModel = vm,
                            onBrowseLibrary = onBrowseLibrary,
                            currentTrackTitle = metadata.title
                        )
                    }
                }
            }
        }
    }
}
