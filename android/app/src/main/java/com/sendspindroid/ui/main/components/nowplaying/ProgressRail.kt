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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import kotlinx.coroutines.delay
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

    var anchorPositionMs by remember { mutableLongStateOf(positionMs) }
    var anchorTime by remember { mutableLongStateOf(positionUpdatedAt) }
    var displayPositionMs by remember { mutableLongStateOf(positionMs) }

    LaunchedEffect(positionMs, positionUpdatedAt) {
        anchorPositionMs = positionMs
        anchorTime = positionUpdatedAt
        displayPositionMs = positionMs
    }

    LaunchedEffect(isPlaying, anchorPositionMs, anchorTime, durationMs) {
        if (!isPlaying) {
            displayPositionMs = anchorPositionMs
            return@LaunchedEffect
        }
        while (isActive) {
            delay(250L)
            val elapsed = SystemClock.elapsedRealtime() - anchorTime
            displayPositionMs = (anchorPositionMs + elapsed).coerceIn(0L, durationMs)
        }
    }

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
                fontSize = 13.sp,
                fontWeight = FontWeight.W600,
                letterSpacing = 2.sp,
                color = RailFgDim,
            )
            if (trackTotal > 0 && trackNumber > 0) {
                Text(
                    text = "$trackNumber of $trackTotal".uppercase(Locale.getDefault()),
                    fontFamily = NpMonoFamily,
                    fontSize = 13.sp,
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
            val fillBrush = if (fillAlpha >= 0.5f) {
                Brush.horizontalGradient(listOf(accent, Color.White))
            } else {
                Brush.horizontalGradient(listOf(RailFgDim, RailFgDim))
            }
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
                drawRoundRect(
                    color = RailHeadRing.copy(alpha = 1f - playheadAlpha),
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
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}
