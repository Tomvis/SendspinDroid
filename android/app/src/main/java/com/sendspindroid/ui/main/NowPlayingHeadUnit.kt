package com.sendspindroid.ui.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import com.sendspindroid.ui.adaptive.FormFactor
import com.sendspindroid.ui.main.components.AlbumArtCard
import com.sendspindroid.ui.main.components.TvTrackProgressBar

/**
 * Head unit Now Playing layout for large portrait car touchscreens.
 *
 * Vertical layout optimized for glanceable car use:
 * - Large album art at top
 * - Track info + visual progress bar with time labels
 * - Single row of 4 oversized controls: shuffle, prev, play, next
 */
@Composable
fun NowPlayingHeadUnit(
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
    val formFactor = FormFactor.HEADUNIT

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = AdaptiveDefaults.screenPadding(formFactor)),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(12.dp))

        // Album Art - large, centered
        AlbumArtCard(
            artworkSource = artworkSource,
            isBuffering = isBuffering,
            maxWidth = AdaptiveDefaults.albumArtMaxSize(formFactor),
            modifier = Modifier.fillMaxWidth(0.65f)
        )

        Spacer(modifier = Modifier.height(20.dp))

        // Track Title
        Text(
            text = metadata.title.ifEmpty { stringResource(R.string.not_playing) },
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            fontSize = AdaptiveDefaults.titleTextSize(formFactor),
            letterSpacing = (-0.02).sp,
            modifier = Modifier
                .fillMaxWidth()
                .semantics { liveRegion = LiveRegionMode.Polite }
        )

        Spacer(modifier = Modifier.height(4.dp))

        // Artist / Album
        val metadataText = buildHeadUnitMetadata(metadata.artist, metadata.album)
        if (metadataText.isNotEmpty()) {
            Text(
                text = metadataText,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontSize = AdaptiveDefaults.bodyTextSize(formFactor),
                modifier = Modifier.fillMaxWidth()
            )
        }

        // Group Name
        if (groupName.isNotEmpty()) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.group_label, groupName),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Visual progress bar with time labels
        TvTrackProgressBar(
            positionMs = positionMs,
            durationMs = durationMs,
            isPlaying = isPlaying,
            accentColor = accentColor,
            positionUpdatedAt = positionUpdatedAt,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Playback Controls -- single row: shuffle, prev, PLAY, next
        HeadUnitControls(
            isPlaying = isPlaying,
            controlsEnabled = controlsEnabled,
            accentColor = accentColor,
            onSwitchGroupClick = onSwitchGroupClick,
            onPreviousClick = onPreviousClick,
            onPlayPauseClick = onPlayPauseClick,
            onNextClick = onNextClick
        )

        Spacer(modifier = Modifier.height(16.dp))
    }
}

/**
 * Head unit control row: shuffle - prev - PLAY - next
 * All in a single row with oversized touch targets.
 */
@Composable
private fun HeadUnitControls(
    isPlaying: Boolean,
    controlsEnabled: Boolean,
    accentColor: Color?,
    onSwitchGroupClick: () -> Unit,
    onPreviousClick: () -> Unit,
    onPlayPauseClick: () -> Unit,
    onNextClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val playSize = 80.dp
    val transportSize = 64.dp
    val secondarySize = 54.dp
    val playIconSize = 36.dp
    val transportIconSize = 30.dp
    val secondaryIconSize = 24.dp
    val buttonAccent = accentColor ?: MaterialTheme.colorScheme.primary

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Shuffle / Switch Group
        IconButton(
            onClick = onSwitchGroupClick,
            enabled = controlsEnabled,
            modifier = Modifier.size(secondarySize)
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_shuffle),
                contentDescription = stringResource(R.string.accessibility_switch_group_button),
                modifier = Modifier.size(secondaryIconSize),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(modifier = Modifier.width(10.dp))

        // Previous
        IconButton(
            onClick = onPreviousClick,
            enabled = controlsEnabled,
            modifier = Modifier.size(transportSize)
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_skip_previous),
                contentDescription = stringResource(R.string.accessibility_previous_button),
                modifier = Modifier.size(transportIconSize),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(modifier = Modifier.width(10.dp))

        // Play/Pause - accent colored, largest button
        FilledIconButton(
            onClick = onPlayPauseClick,
            enabled = controlsEnabled,
            modifier = Modifier.size(playSize),
            shape = CircleShape,
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = buttonAccent,
                contentColor = Color.Black
            )
        ) {
            Icon(
                painter = painterResource(
                    if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play
                ),
                contentDescription = stringResource(R.string.accessibility_play_button),
                modifier = Modifier.size(playIconSize)
            )
        }

        Spacer(modifier = Modifier.width(10.dp))

        // Next
        IconButton(
            onClick = onNextClick,
            enabled = controlsEnabled,
            modifier = Modifier.size(transportSize)
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_skip_next),
                contentDescription = stringResource(R.string.accessibility_next_button),
                modifier = Modifier.size(transportIconSize),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun buildHeadUnitMetadata(artist: String, album: String): String {
    return buildString {
        if (artist.isNotEmpty()) append(artist)
        if (album.isNotEmpty()) {
            if (isNotEmpty()) append(" -- ")
            append(album)
        }
    }
}
