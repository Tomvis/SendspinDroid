package com.sendspindroid.ui.main.components

import android.os.SystemClock
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sendspindroid.R
import com.sendspindroid.model.trackPositionMs
import com.sendspindroid.ui.adaptive.AdaptiveDefaults
import com.sendspindroid.ui.adaptive.FormFactor
import com.sendspindroid.ui.adaptive.LocalFormFactor
import com.sendspindroid.ui.theme.SendSpinTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.util.Locale

/**
 * Track progress bar with elapsed / total timestamps beneath it.
 *
 * When [seekable], the bar is a slider: dragging previews the position and
 * releasing calls [onSeek] once. Otherwise it is a read-only bar.
 *
 * Advances the position at the server's [playbackSpeed] (1000 = normal,
 * 0 = paused) from the last server position update. Hidden when duration is
 * unknown (<=0).
 */
@Composable
fun TrackProgressBar(
    positionMs: Long,
    durationMs: Long,
    playbackSpeed: Int,
    accentColor: Color?,
    positionUpdatedAt: Long = SystemClock.elapsedRealtime(),
    seekable: Boolean = false,
    onSeek: (Long) -> Unit = {},
    modifier: Modifier = Modifier
) {
    if (durationMs <= 0) return
    val displayPositionMs =
        rememberTrackPositionMs(positionMs, durationMs, playbackSpeed, positionUpdatedAt)

    // Where the user dragged to. Shown instead of the interpolated position
    // during the drag and, after release, until the server reports the result.
    var scrubPositionMs by remember { mutableStateOf<Long?>(null) }
    var awaitingServer by remember { mutableStateOf(false) }

    LaunchedEffect(positionMs, positionUpdatedAt) {
        if (awaitingServer) {
            scrubPositionMs = null
            awaitingServer = false
        }
    }

    // A server that ignores the seek sends nothing: go back to its position.
    LaunchedEffect(awaitingServer) {
        if (awaitingServer) {
            delay(SEEK_HOLD_MS)
            scrubPositionMs = null
            awaitingServer = false
        }
    }

    val shownPositionMs = scrubPositionMs ?: displayPositionMs
    val barColor = accentColor ?: MaterialTheme.colorScheme.primary
    val barDescription = stringResource(R.string.track_position)

    Column(modifier = modifier.fillMaxWidth()) {
        if (seekable) {
            Slider(
                value = shownPositionMs.toFloat(),
                onValueChange = {
                    scrubPositionMs = it.toLong()
                    awaitingServer = false
                },
                // One seek on release: each one restarts the stream for the group.
                onValueChangeFinished = {
                    scrubPositionMs?.let { target ->
                        onSeek(target)
                        awaitingServer = true
                    }
                },
                valueRange = 0f..durationMs.toFloat(),
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = barDescription },
                colors = SliderDefaults.colors(
                    thumbColor = barColor,
                    activeTrackColor = barColor,
                    inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant
                )
            )
        } else {
            // No thumb when the server does not offer seek, so the bar never
            // looks draggable.
            LinearProgressIndicator(
                progress = { (shownPositionMs.toFloat() / durationMs).coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
                    .semantics { contentDescription = barDescription },
                color = barColor,
                trackColor = MaterialTheme.colorScheme.surfaceVariant
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Text(
                text = "${formatTime(shownPositionMs)} / ${formatTime(durationMs)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** How long a released scrub position is shown while the server has not answered. */
private const val SEEK_HOLD_MS = 2000L

/**
 * TV variant with visual LinearProgressIndicator + timestamps below.
 * Provides at-a-glance progress for the 10-foot viewing experience.
 */
@Composable
fun TvTrackProgressBar(
    positionMs: Long,
    durationMs: Long,
    playbackSpeed: Int,
    accentColor: Color?,
    positionUpdatedAt: Long = SystemClock.elapsedRealtime(),
    modifier: Modifier = Modifier
) {
    if (durationMs <= 0) return
    val displayPositionMs =
        rememberTrackPositionMs(positionMs, durationMs, playbackSpeed, positionUpdatedAt)

    val progress = (displayPositionMs.toFloat() / durationMs).coerceIn(0f, 1f)
    val trackColor = accentColor ?: MaterialTheme.colorScheme.primary
    val formFactor = LocalFormFactor.current
    val barHeight = AdaptiveDefaults.progressBarHeight(formFactor)
    val barCornerRadius = AdaptiveDefaults.progressBarCornerRadius(formFactor)

    Column(modifier = modifier.fillMaxWidth()) {
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier
                .fillMaxWidth()
                .height(barHeight)
                .clip(RoundedCornerShape(barCornerRadius)),
            color = trackColor,
            trackColor = MaterialTheme.colorScheme.surfaceVariant
        )

        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = formatTime(displayPositionMs),
                fontSize = 16.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = formatTime(durationMs),
                fontSize = 16.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * The current track position, re-read every 250ms while the server reports a
 * non-zero playback speed.
 *
 * Extrapolated from [positionUpdatedAt] (the monotonic time the server
 * position was received), so a composable that is destroyed and recreated
 * (e.g. navigating away from Now Playing and back) picks up where the track
 * really is. Keyed on the timestamp too: two tracks in a row can both report
 * the same position.
 */
@Composable
private fun rememberTrackPositionMs(
    positionMs: Long,
    durationMs: Long,
    playbackSpeed: Int,
    positionUpdatedAt: Long
): Long {
    fun now() = trackPositionMs(
        anchorPositionMs = positionMs,
        elapsedMs = SystemClock.elapsedRealtime() - positionUpdatedAt,
        playbackSpeed = playbackSpeed,
        durationMs = durationMs
    )

    var displayPositionMs by remember { mutableLongStateOf(now()) }
    LaunchedEffect(positionMs, durationMs, playbackSpeed, positionUpdatedAt) {
        displayPositionMs = now()
        if (playbackSpeed == 0) return@LaunchedEffect
        while (isActive) {
            delay(250)
            displayPositionMs = now()
        }
    }
    return displayPositionMs
}

/**
 * Formats milliseconds as M:SS or H:MM:SS.
 */
internal fun formatTime(ms: Long): String {
    if (ms < 0) return "0:00"
    val totalSeconds = ms / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    // Locale.ROOT pins ASCII digits + ":" separator across all locales
    // (some locales would otherwise emit Arabic-Indic digits or NBSP separators).
    return if (hours > 0) {
        String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.ROOT, "%d:%02d", minutes, seconds)
    }
}

// ============================================================================
// Previews
// ============================================================================

@Preview(showBackground = true, widthDp = 360)
@Composable
private fun TrackProgressBarPreview() {
    SendSpinTheme {
        TrackProgressBar(
            positionMs = 45000,
            durationMs = 210000,
            playbackSpeed = 1000,
            accentColor = null,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
    }
}

@Preview(showBackground = true, widthDp = 360)
@Composable
private fun TrackProgressBarLongTrackPreview() {
    SendSpinTheme {
        TrackProgressBar(
            positionMs = 3725000,
            durationMs = 7200000,
            playbackSpeed = 0,
            accentColor = null,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
    }
}
