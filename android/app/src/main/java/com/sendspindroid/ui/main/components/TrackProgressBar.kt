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
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
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
import com.sendspindroid.ui.adaptive.tvFocusable
import com.sendspindroid.ui.theme.SendSpinTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.util.Locale

/**
 * Track progress bar with elapsed / total timestamps beneath it.
 *
 * When [seekable], the bar is a slider: dragging previews the position and
 * releasing calls [onSeek] once. Left and Right on the focused slider step
 * as on [TvTrackProgressBar], with one [onSeek] once the presses stop.
 * Otherwise it is a read-only bar.
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
    // Where the user dragged to
    val seek = rememberPendingSeek(positionMs, positionUpdatedAt)

    val shownPositionMs = seek.positionMs ?: displayPositionMs
    val barColor = accentColor ?: MaterialTheme.colorScheme.primary
    val barDescription = stringResource(R.string.track_position)
    val focusManager = LocalFocusManager.current
    val view = LocalView.current
    val stepOnArrows = rememberArrowKeySeek(seek, displayPositionMs, durationMs, onSeek)

    Column(modifier = modifier.fillMaxWidth()) {
        if (seekable) {
            Slider(
                value = shownPositionMs.toFloat(),
                onValueChange = {
                    seek.positionMs = it.toLong()
                    seek.awaitingServer = false
                },
                // One seek on release: each one restarts the stream for the group.
                onValueChangeFinished = {
                    seek.positionMs?.let { target ->
                        onSeek(target)
                        seek.awaitingServer = true
                    }
                },
                valueRange = 0f..durationMs.toFloat(),
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = barDescription }
                    .upDownMovesFocus(focusManager, view)
                    .then(stepOnArrows),
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
 * How far one press of Left or Right on a progress bar, or of the remote's
 * fast-forward or rewind key, moves the track.
 */
internal const val SEEK_STEP_MS = 10_000L

/**
 * How long a progress bar waits after the last Left or Right press before it
 * sends the seek.
 */
private const val SEEK_STEP_SETTLE_MS = 800L

/**
 * TV variant with visual LinearProgressIndicator + timestamps below.
 * Provides at-a-glance progress for the 10-foot viewing experience.
 *
 * When [seekable], the bar takes focus and Left and Right move the position
 * by [SEEK_STEP_MS]. The steps add up while the user keeps pressing, and
 * [onSeek] is called once when they stop. Otherwise the bar is read-only and
 * the D-pad passes over it.
 */
@Composable
fun TvTrackProgressBar(
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
    // Where the user stepped to
    val seek = rememberPendingSeek(positionMs, positionUpdatedAt)
    val stepOnArrows = rememberArrowKeySeek(seek, displayPositionMs, durationMs, onSeek)

    val shownPositionMs = seek.positionMs ?: displayPositionMs
    val progress = (shownPositionMs.toFloat() / durationMs).coerceIn(0f, 1f)
    val trackColor = accentColor ?: MaterialTheme.colorScheme.primary
    val formFactor = LocalFormFactor.current
    val barHeight = AdaptiveDefaults.progressBarHeight(formFactor)
    val barCornerRadius = AdaptiveDefaults.progressBarCornerRadius(formFactor)
    val barDescription = stringResource(R.string.track_position)

    val stepOnDpad = if (seekable) {
        stepOnArrows
            // Not scaled: a full-width bar would grow past the timestamps.
            .tvFocusable(shape = RoundedCornerShape(barCornerRadius), focusScale = 1f)
    } else {
        Modifier
    }

    Column(modifier = modifier.fillMaxWidth()) {
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier
                .fillMaxWidth()
                .height(barHeight)
                .then(stepOnDpad)
                .clip(RoundedCornerShape(barCornerRadius))
                .semantics { contentDescription = barDescription },
            color = trackColor,
            trackColor = MaterialTheme.colorScheme.surfaceVariant
        )

        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = formatTime(shownPositionMs),
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
 * A position the user picked on the bar. It is shown in place of the track
 * position while they pick it and, once sent, until the server reports the
 * result.
 */
@Stable
private class PendingSeek {
    var positionMs by mutableStateOf<Long?>(null)

    /** [positionMs] went to the server, which has not reported since. */
    var awaitingServer by mutableStateOf(false)
}

@Composable
private fun rememberPendingSeek(positionMs: Long, positionUpdatedAt: Long): PendingSeek {
    val seek = remember { PendingSeek() }

    LaunchedEffect(positionMs, positionUpdatedAt) {
        if (seek.awaitingServer) {
            seek.positionMs = null
            seek.awaitingServer = false
        }
    }

    // A server that ignores the seek sends nothing: go back to its position.
    LaunchedEffect(seek.awaitingServer) {
        if (seek.awaitingServer) {
            delay(SEEK_HOLD_MS)
            seek.positionMs = null
            seek.awaitingServer = false
        }
    }
    return seek
}

/**
 * Left and Right move [seek] by [SEEK_STEP_MS]. The steps add up while the
 * user keeps pressing, and [onSeek] is called once when they stop.
 *
 * Put the returned modifier on the bar, before its focus target. It takes
 * both keys on key down and on key up, so a material3 Slider, which from 1.4
 * steps on them and finishes its change on each key up, never sees them.
 */
@Composable
private fun rememberArrowKeySeek(
    seek: PendingSeek,
    displayPositionMs: Long,
    durationMs: Long,
    onSeek: (Long) -> Unit
): Modifier {
    val currentOnSeek by rememberUpdatedState(onSeek)
    var steps by remember { mutableIntStateOf(0) }

    // A press restarts the wait, so a run of presses is one seek: each seek
    // restarts the stream for the group. Keyed on the presses, not the
    // position: presses can bring it back to the one that already went out.
    LaunchedEffect(steps) {
        if (steps == 0) return@LaunchedEffect
        delay(SEEK_STEP_SETTLE_MS)
        val target = seek.positionMs ?: return@LaunchedEffect
        currentOnSeek(target)
        seek.awaitingServer = true
    }

    return Modifier.onPreviewKeyEvent { event ->
        val step = when (event.key) {
            Key.DirectionRight -> SEEK_STEP_MS
            Key.DirectionLeft -> -SEEK_STEP_MS
            else -> return@onPreviewKeyEvent false
        }
        if (event.type == KeyEventType.KeyDown) {
            val from = seek.positionMs ?: displayPositionMs
            val to = (from + step).coerceIn(0L, durationMs)
            // A press against the start or the end moves nothing, so it
            // sends nothing and leaves a seek that went out waiting for the
            // server's answer.
            if (to != from) {
                seek.positionMs = to
                seek.awaitingServer = false
                steps++
            }
        }
        true
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
