package com.sendspindroid.ui.main.components.nowplaying

import android.os.SystemClock
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.ui.draw.clip
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sendspindroid.ui.theme.NpMonoFamily
import kotlinx.coroutines.isActive
import java.util.Locale

private val RailFg = Color(0xFFFAF6F0)
private val RailFgDim = Color(0xFFFAF6F0).copy(alpha = 0.58f)
private val RailBarBg = Color(0xFFFAF6F0).copy(alpha = 0.14f)
private val RailHeadRing = Color(0xFFFAF6F0).copy(alpha = 0.7f)

@Composable
fun ProgressRail(
    positionMs: Long,
    durationMs: Long,
    positionUpdatedAt: Long,
    isPlaying: Boolean,
    trackNumber: Int,
    trackTotal: Int,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    val paused = !isPlaying
    val safeDuration = durationMs.coerceAtLeast(1L)

    // Allocate all remember slots unconditionally so the slot table is stable
    // across durationMs <= 0 transitions. The original early return at the
    // function head re-keyed displayPositionMs/anchor/playSince every time
    // durationMs dipped to 0 (e.g., transient metadata refresh) -- tearing
    // down interpolation state and forcing a fresh snap from server values
    // on the next non-zero frame.
    var anchorPositionMs by remember { mutableLongStateOf(positionMs) }
    var anchorTime by remember { mutableLongStateOf(positionUpdatedAt) }
    var displayPositionMs by remember { mutableLongStateOf(positionMs) }
    // Tracks the moment isPlaying last flipped false -> true. On resume we
    // re-anchor (anchorPositionMs <- displayPositionMs, anchorTime <- playSince)
    // so the bar continues forward from the frozen pause value instead of
    // snapping back to the server's last anchor and ticking forward from there.
    // The next server/state will replace this local anchor with the authoritative
    // one.
    var playSince by remember { mutableLongStateOf(0L) }
    var wasPlaying by remember { mutableStateOf(isPlaying) }

    LaunchedEffect(positionMs, positionUpdatedAt) {
        anchorPositionMs = positionMs
        anchorTime = positionUpdatedAt
        // Snap displayPositionMs only when actively playing OR when the new
        // server position represents a clear track change (positionMs reset
        // near 0 while displayPositionMs was deep into the previous track).
        // While paused mid-track the server's snapshot of "where the track
        // currently is" routinely trails local interpolation by a beat, and
        // snapping would visibly jerk the playhead backward right as the
        // pause animation is firing. Preserve the local value; the resume
        // path re-anchors to displayPositionMs so the bar continues forward
        // from where the user saw it freeze.
        val isTrackChange = positionMs < 2000L && displayPositionMs > 5000L
        if (isPlaying || isTrackChange) {
            displayPositionMs = positionMs
        }
    }

    // Single effect that owns the resume re-anchor AND the per-frame
    // interpolation loop. Previously these were two LaunchedEffects, both
    // keyed on isPlaying. On a pause->play flip, the first effect would write
    // new anchor/playSince values, but the second effect's restart captured
    // the OLD anchor/playSince at composition time -- so for one frame the
    // interpolation loop ran with stale anchors and the playhead could lurch
    // forward by however long we were paused. Merging the work eliminates
    // the inter-effect race.
    LaunchedEffect(isPlaying, anchorPositionMs, anchorTime, durationMs) {
        // Apply the resume re-anchor synchronously at the top of the effect
        // so the per-frame loop below sees fresh values.
        val localPlaySince = if (isPlaying && !wasPlaying) {
            val now = SystemClock.elapsedRealtime()
            playSince = now
            // Re-anchor to the value the rail showed during pause so the
            // interpolation loop continues forward from there. Without this,
            // resume snaps the bar backward to the last server anchor and
            // re-ticks the elapsed-since-pause delta.
            anchorPositionMs = displayPositionMs
            anchorTime = now
            wasPlaying = true
            now
        } else {
            wasPlaying = isPlaying
            playSince
        }
        // anchorTime == 0L means the VM hasn't applied a real server frame yet.
        // Without this guard, `elapsed = elapsedRealtime() - 0` is device uptime
        // and the bar snaps to durationMs on first render.
        if (anchorTime <= 0L) {
            displayPositionMs = anchorPositionMs
            return@LaunchedEffect
        }
        if (!isPlaying) {
            // Pause: freeze displayPositionMs at its current value rather than
            // snapping back to anchorPositionMs. The interpolation loop has been
            // advancing past the anchor; snapping back would jerk the bar
            // visibly. The next server-pushed position (the effect above
            // re-anchors on positionMs / positionUpdatedAt) will re-sync the
            // bar to the authoritative server state.
            return@LaunchedEffect
        }
        // Clamp the elapsed reference to whichever is more recent: the server's
        // anchorTime, or the most recent local resume moment. Prevents the
        // pause duration from being added to displayPositionMs during the
        // brief window between "user pressed play locally" and "server sends
        // a fresh server/state with the resume position".
        val timeZero = maxOf(anchorTime, localPlaySince)
        while (isActive) {
            withFrameMillis { }
            val elapsed = SystemClock.elapsedRealtime() - timeZero
            // Use safeDuration (>= 1L) so a malformed durationMs (e.g., 0 or
            // negative) doesn't trip coerceIn's range precondition and crash
            // the interpolation loop.
            displayPositionMs = (anchorPositionMs + elapsed).coerceIn(0L, safeDuration)
        }
    }

    // No-op render when duration is unknown (live stream, or the first server
    // frame before track_duration arrives). The rail's math floors safeDuration
    // to 1L which otherwise pins the bar at 100% with "0:00 / 0:00" labels --
    // visually broken. The rail reappears once the server delivers a real
    // duration; remember slots above stay stable across this gate.
    if (durationMs <= 0L) return

    val remainingMs = (durationMs - displayPositionMs).coerceAtLeast(0L)
    val progress = (displayPositionMs.toFloat() / safeDuration).coerceIn(0f, 1f)

    val elapsedColor by animateColorAsState(
        targetValue = if (paused) RailFgDim else RailFg,
        animationSpec = tween(durationMillis = 400),
        label = "np-elapsed-color",
    )
    // State-change drivers. 0f = paused, 1f = playing. Split into two so each
    // transition can own its own duration (matches the CSS spec).
    val playheadAnim by animateFloatAsState(
        targetValue = if (paused) 0f else 1f,
        animationSpec = tween(durationMillis = 400),
        label = "np-playhead-anim",
    )
    val fillAnim by animateFloatAsState(
        targetValue = if (paused) 0f else 1f,
        animationSpec = tween(durationMillis = 500),
        label = "np-fill-anim",
    )

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                StatusGlyph(paused = paused, color = elapsedColor)
                Text(
                    text = formatRailTime(displayPositionMs),
                    fontFamily = NpMonoFamily,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.W600,
                    letterSpacing = 0.5.sp,
                    color = elapsedColor,
                )
            }
            Text(
                text = "−" + formatRailTime(remainingMs),
                fontFamily = NpMonoFamily,
                fontSize = 20.sp,
                fontWeight = FontWeight.W500,
                letterSpacing = 0.5.sp,
                color = RailFgDim,
            )
        }

        Box(modifier = Modifier.padding(top = 14.dp).fillMaxWidth().height(14.dp)) {
            RailBar(
                progress = progress,
                accent = accent,
                playheadAlpha = playheadAnim,
                fillAlpha = fillAnim,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Row(
            modifier = Modifier.padding(top = 10.dp).fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "Total ${formatRailTime(durationMs)}".uppercase(Locale.getDefault()),
                fontFamily = NpMonoFamily,
                fontSize = 18.sp,
                fontWeight = FontWeight.W600,
                letterSpacing = 2.sp,
                color = RailFgDim,
            )
            if (trackTotal > 0 && trackNumber > 0) {
                Text(
                    text = "$trackNumber of $trackTotal".uppercase(Locale.getDefault()),
                    fontFamily = NpMonoFamily,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.W600,
                    letterSpacing = 2.sp,
                    color = RailFgDim,
                )
            }
        }
    }
}

@Composable
private fun RailBar(
    progress: Float,
    accent: Color,
    playheadAlpha: Float,
    fillAlpha: Float,
    modifier: Modifier = Modifier,
) {
    // Use Modifier.background for the gradient fill instead of Canvas.drawRoundRect
    // with a brush. On the Shield TV GPU path, Canvas-shader brushes were falling
    // back to solid black; Modifier.background takes a different rendering path
    // that renders gradients reliably.
    val barShape = RoundedCornerShape(50)
    BoxWithConstraints(
        modifier = modifier,
        contentAlignment = Alignment.CenterStart,
    ) {
        val barWidthPx = constraints.maxWidth.toFloat()
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(barShape)
                .background(RailBarBg),
        )
        if (progress > 0f) {
            // RailBar recomposes ~60Hz during interpolation; remember the two
            // gradient brushes so shader allocation isn't on the frame budget.
            val playingBrush = remember(accent) {
                Brush.horizontalGradient(listOf(accent, Color.White))
            }
            val pausedBrush = remember {
                Brush.horizontalGradient(listOf(RailFgDim, RailFgDim))
            }
            val fillBrush = if (fillAlpha >= 0.5f) playingBrush else pausedBrush
            Box(
                modifier = Modifier
                    .fillMaxWidth(progress)
                    .height(4.dp)
                    .clip(barShape)
                    .background(fillBrush),
            )
        }

        // Playhead stays in Canvas — it's a flat white/stroked pill, no
        // brush involved, so the Canvas path is fine for it.
        Canvas(modifier = Modifier.fillMaxWidth().fillMaxHeight()) {
            val headWidth = 16.dp.toPx()
            val headHeight = 6.dp.toPx()
            val headRadius = headHeight / 2f
            val halfW = headWidth / 2f
            val halfH = headHeight / 2f
            val progressWidth = barWidthPx * progress
            val headX = progressWidth.coerceIn(halfW, size.width - halfW)
            val headY = size.height / 2f

            if (playheadAlpha > 0f) {
                drawRoundRect(
                    color = Color(0xFFFFFFFF).copy(alpha = playheadAlpha),
                    topLeft = Offset(headX - halfW, headY - halfH),
                    size = Size(headWidth, headHeight),
                    cornerRadius = CornerRadius(headRadius),
                )
            }
            if (playheadAlpha < 1f) {
                // Color.copy(alpha) replaces, doesn't multiply -- preserve the
                // 0.7f base by multiplying explicitly so the stroked ring at
                // full pause renders at 70% alpha, matching the rest of the
                // dim-on-pause treatment.
                drawRoundRect(
                    color = RailHeadRing.copy(alpha = 0.7f * (1f - playheadAlpha)),
                    topLeft = Offset(headX - halfW, headY - halfH),
                    size = Size(headWidth, headHeight),
                    cornerRadius = CornerRadius(headRadius),
                    style = Stroke(width = 1.5.dp.toPx()),
                )
            }
        }
    }
}

@Composable
private fun StatusGlyph(paused: Boolean, color: Color) {
    // Crossfade play triangle and pause bars over 300ms, matching the CSS
    // transition on the spec's <svg opacity=…>.
    val t by animateFloatAsState(
        targetValue = if (paused) 1f else 0f,
        animationSpec = tween(durationMillis = 300),
        label = "np-glyph-t",
    )
    Canvas(modifier = Modifier.size(width = 14.dp, height = 16.dp)) {
        // Play triangle — fades out as t -> 1.
        if (t < 1f) {
            val path = Path().apply {
                moveTo(2.dp.toPx(), 1.5.dp.toPx())
                lineTo(12.5.dp.toPx(), 8.dp.toPx())
                lineTo(2.dp.toPx(), 14.5.dp.toPx())
                close()
            }
            drawPath(path = path, color = color.copy(alpha = (1f - t) * 0.85f))
        }
        // Pause bars — fade in as t -> 1.
        if (t > 0f) {
            val barWidth = 3.2.dp.toPx()
            val barHeight = 12.dp.toPx()
            val topY = 2.dp.toPx()
            drawRoundRect(
                color = color.copy(alpha = t),
                topLeft = Offset(2.dp.toPx(), topY),
                size = Size(barWidth, barHeight),
                cornerRadius = CornerRadius(0.4.dp.toPx()),
            )
            drawRoundRect(
                color = color.copy(alpha = t),
                topLeft = Offset(8.8.dp.toPx(), topY),
                size = Size(barWidth, barHeight),
                cornerRadius = CornerRadius(0.4.dp.toPx()),
            )
        }
    }
}

private fun formatRailTime(ms: Long): String {
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
