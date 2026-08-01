package com.sendspindroid.ui.main.components.nowplaying

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sendspindroid.model.AppConnectionState
import com.sendspindroid.ui.adaptive.TvPassiveFocusAnchor
import com.sendspindroid.ui.adaptive.overscanSafe
import com.sendspindroid.ui.theme.NpCream
import com.sendspindroid.ui.theme.NpCreamDim
import com.sendspindroid.ui.theme.NpFrauncesFamily
import com.sendspindroid.ui.theme.NpInterFamily
import java.util.Calendar
import java.util.Locale

private val IdleBase = Color(0xFF07060D)
private val IdleFg = NpCream
private val IdleFgDim = NpCreamDim
private val IdleFgFaint = NpCream.copy(alpha = 0.35f)
private val IdleFgError = Color(0xFFE57373) // muted coral, doesn't fight cream/accent
private val BlobTintA = Color(0xFF6A4D9A).copy(alpha = 0.4f)
private val BlobTintB = Color(0xFFD98C58).copy(alpha = 0.67f)

/**
 * View-model for the idle screen's status surfaces (standby badge + wordmark
 * subtitle). The two surfaces share the same five-state machine so the badge
 * dot and wordmark subtitle always agree. Every per-status difference is a
 * constructor argument, so adding a sixth state is a single edit here rather
 * than a new arm in each surface's `when`.
 *
 * [dotColor] is null for the states whose dot picks up the live accent colour.
 * [pulses] drives the breathing dot the focus screen uses while the link isn't
 * healthy.
 */
private enum class IdleStatus(
    val dotLabel: String,
    val wordmarkLabel: String,
    val pulses: Boolean,
    val dotColor: Color?,
) {
    READY("Standby", "Audio · Ready", pulses = false, dotColor = IdleFgFaint),
    CONNECTING("Connecting", "Linking · Audio", pulses = true, dotColor = null),
    RECONNECTING("Reconnecting", "Searching · Audio", pulses = true, dotColor = null),
    OFFLINE("Offline", "No Server", pulses = false, dotColor = IdleFgFaint),
    ERROR("Disconnected", "Connection Lost", pulses = true, dotColor = IdleFgError),
}

private fun AppConnectionState?.toIdleStatus(): IdleStatus = when (this) {
    null -> IdleStatus.READY
    is AppConnectionState.Connected -> IdleStatus.READY
    is AppConnectionState.Connecting -> IdleStatus.CONNECTING
    is AppConnectionState.Reconnecting -> IdleStatus.RECONNECTING
    is AppConnectionState.Error -> IdleStatus.ERROR
    AppConnectionState.ServerList -> IdleStatus.OFFLINE
}

@Composable
fun NowPlayingIdleScreen(
    accent: Color,
    groupLabel: String,
    connectionState: AppConnectionState? = null,
    modifier: Modifier = Modifier,
) {
    val status = connectionState.toIdleStatus()

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(IdleBase),
    ) {
        // Force the ambient stack into an offscreen compositing layer so the
        // alpha-blended grain composites against a stable backdrop. Without
        // this, on the Shield/Tegra GPU the blend reads back from whatever
        // was in the framebuffer that frame, which manifests as random flicker.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen },
        ) {
            IdleBlobs(accent = accent)
            Vignette(radiusFactor = 0.8f)
            GrainOverlay()
        }

        TvPassiveFocusAnchor()

        Column(
            modifier = Modifier
                .fillMaxSize()
                .overscanSafe(),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                StandbyBadge(groupLabel = groupLabel, status = status, accent = accent)
                Wordmark(accent = accent, status = status)
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                HeroClock(accent = accent)
            }

            HorizonTagline()
        }
    }
}

@Composable
private fun IdleBlobs(accent: Color) {
    val transition = rememberInfiniteTransition(label = "np-idle-blobs")
    val t1 = transition.animateFloat(
        0f, 1f, infiniteRepeatable(tween(38_000, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "np-idle-blob1",
    )
    val t2 = transition.animateFloat(
        0f, 1f, infiniteRepeatable(tween(46_000, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "np-idle-blob2",
    )
    val t3 = transition.animateFloat(
        0f, 1f, infiniteRepeatable(tween(52_000, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "np-idle-blob3",
    )
    val t4 = transition.animateFloat(
        0f, 1f, infiniteRepeatable(tween(60_000, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "np-idle-blob4",
    )

    Box(modifier = Modifier.fillMaxSize()) {
        Blob(
            color = accent.copy(alpha = 0.33f),
            size = 600.dp,
            offsetX = (-150).dp,
            offsetY = (-100).dp,
            driftX = { 48.dp * t1.value },
            driftY = { 36.dp * t1.value },
            scale = { 1f + 0.1f * t1.value },
        )
        Blob(
            color = accent.copy(alpha = 0.20f),
            size = 500.dp,
            alignment = Alignment.TopEnd,
            offsetX = 125.dp,
            offsetY = 150.dp,
            driftX = { -30.dp * t2.value },
            driftY = { -20.dp * t2.value },
            scale = { 1f - 0.08f * t2.value },
        )
        Blob(
            color = BlobTintA,
            size = 450.dp,
            alignment = Alignment.BottomCenter,
            offsetX = 0.dp,
            offsetY = 125.dp,
            driftX = { -18.dp * t3.value },
            driftY = { 22.dp * t3.value },
            scale = { 1.05f },
        )
        Blob(
            color = BlobTintB,
            size = 350.dp,
            alignment = Alignment.Center,
            offsetX = 10.dp,
            offsetY = (-40).dp,
            driftX = { 21.dp * t4.value },
            driftY = { -17.dp * t4.value },
            scale = { 0.9f + 0.18f * t4.value },
        )
    }
}

@Composable
private fun Blob(
    color: Color,
    size: Dp,
    offsetX: Dp,
    offsetY: Dp,
    driftX: () -> Dp,
    driftY: () -> Dp,
    scale: () -> Float,
    alignment: Alignment = Alignment.TopStart,
) {
    // Use Modifier.background for the radial gradient rather than a Canvas-
    // style drawRect(brush=...): the Shield Tegra renderer drops gradients
    // composed inside drawWithCache/drawBehind and paints black. The default
    // Brush.radialGradient auto-fits center to the box center and radius to
    // size.minDimension/2 -- identical to the explicit values used before.
    // Keyed on color so the shader survives the infinite-transition recomposes
    // driving translation/scale below.
    val blobBrush = remember(color) {
        Brush.radialGradient(colors = listOf(color, Color.Transparent))
    }
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = alignment,
    ) {
        Box(
            modifier = Modifier
                .size(size)
                .graphicsLayer {
                    translationX = (offsetX + driftX()).toPx()
                    translationY = (offsetY + driftY()).toPx()
                    val s = scale()
                    scaleX = s
                    scaleY = s
                }
                .background(blobBrush),
        )
    }
}

@Composable
private fun StandbyBadge(
    groupLabel: String,
    status: IdleStatus,
    accent: Color,
) {
    // When the link isn't healthy, the dot does the same gentle breath
    // SourceBadge uses on the focus screen. Reusing the cadence makes the
    // two screens read as the same instrument in two states.
    val transition = rememberInfiniteTransition(label = "np-idle-badge")
    val pulseScale by transition.animateFloat(
        initialValue = if (status.pulses) 0.85f else 1f,
        targetValue = if (status.pulses) 1.18f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1100, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "np-idle-badge-scale",
    )
    val dotColor = status.dotColor ?: accent
    StatusBadge(
        label = badgeLabel(status = status.dotLabel, groupLabel = groupLabel),
        labelColor = IdleFgDim,
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .graphicsLayer {
                    scaleX = pulseScale
                    scaleY = pulseScale
                }
                .drawWithCache {
                    onDrawBehind {
                        drawCircle(color = dotColor)
                    }
                },
        )
    }
}

@Composable
private fun Wordmark(accent: Color, status: IdleStatus) {
    val annotated = buildAnnotatedString {
        withStyle(
            SpanStyle(
                fontStyle = FontStyle.Italic,
                fontWeight = FontWeight.W400,
                color = IdleFg,
            ),
        ) {
            append("Sendspin")
        }
        withStyle(
            SpanStyle(
                fontWeight = FontWeight.W500,
                color = accent,
            ),
        ) {
            append("Droid")
        }
    }
    Column(horizontalAlignment = Alignment.End) {
        Text(
            text = annotated,
            fontFamily = NpFrauncesFamily,
            fontSize = 32.sp,
            letterSpacing = (-0.5).sp,
            lineHeight = 32.sp,
        )
        Text(
            text = status.wordmarkLabel.uppercase(Locale.getDefault()),
            modifier = Modifier.padding(top = 8.dp),
            fontFamily = NpInterFamily,
            fontSize = 16.sp,
            fontWeight = FontWeight.W600,
            letterSpacing = 3.sp,
            color = IdleFgFaint,
            textAlign = TextAlign.End,
        )
    }
}

@Composable
private fun HeroClock(accent: Color) {
    val clock = rememberClockParts(monthStyle = Calendar.LONG)

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = clock.hour,
                fontFamily = NpFrauncesFamily,
                fontSize = 240.sp,
                lineHeight = 240.sp,
                fontWeight = FontWeight.W300,
                letterSpacing = (-8).sp,
                color = IdleFg,
            )
            PulsingColon(accent = accent)
            Text(
                text = clock.minute,
                fontFamily = NpFrauncesFamily,
                fontSize = 240.sp,
                lineHeight = 240.sp,
                fontWeight = FontWeight.W300,
                letterSpacing = (-8).sp,
                color = IdleFg,
            )
        }
        Text(
            text = "${clock.weekday} · ${clock.month} ${clock.day}, ${clock.year}"
                .uppercase(Locale.getDefault()),
            modifier = Modifier.padding(top = 32.dp),
            fontFamily = NpInterFamily,
            fontSize = 20.sp,
            fontWeight = FontWeight.W500,
            letterSpacing = 8.sp,
            color = IdleFgDim,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun PulsingColon(accent: Color) {
    val transition = rememberInfiniteTransition(label = "np-colon")
    val pulseA = transition.animateFloat(
        initialValue = 0.95f,
        targetValue = 1.06f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2400, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "np-colon-a",
    )
    val pulseB = transition.animateFloat(
        initialValue = 1.06f,
        targetValue = 0.95f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2400, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "np-colon-b",
    )

    Box(
        modifier = Modifier
            .padding(horizontal = 48.dp)
            .size(width = 48.dp, height = 220.dp),
    ) {
        ColonDot(
            color = accent,
            alignment = androidx.compose.ui.BiasAlignment(0f, -0.44f),
            scale = { pulseA.value },
        )
        ColonDot(
            color = accent,
            alignment = androidx.compose.ui.BiasAlignment(0f, 0.36f),
            scale = { pulseB.value },
        )
    }
}

@Composable
private fun ColonDot(
    color: Color,
    alignment: Alignment,
    scale: () -> Float,
) {
    // Glow goes through Modifier.background (Shield Tegra drops brushes
    // drawn inside Canvas, paints black). Wrap in a 72dp box so the
    // auto-fit radius (min/2 = 36dp) matches the original size.minDimension
    // glow radius -- visually equivalent. Keyed on color so the shader is
    // recycled across every pulse-driven recompose.
    val glowBrush = remember(color) {
        Brush.radialGradient(
            colors = listOf(color.copy(alpha = 0.6f), Color.Transparent),
        )
    }
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = alignment,
    ) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .graphicsLayer {
                    val s = scale()
                    scaleX = s
                    scaleY = s
                },
            contentAlignment = Alignment.Center,
        ) {
            // Glow halo: 72dp box with radial gradient that fades to
            // transparent at the box edge. Clipped to a circle so the
            // background fills a disc rather than a square.
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .background(glowBrush, CircleShape),
            )
            // Solid inner dot stays in Canvas -- flat color, no brush, no
            // Shield issue.
            Canvas(modifier = Modifier.size(36.dp)) {
                drawCircle(color = color, radius = size.minDimension / 2f)
            }
        }
    }
}

@Composable
private fun HorizonTagline() {
    // Use Modifier.background for the divider gradient rather than a Canvas-
    // style drawRect(brush=...) -- the Shield Tegra renderer can drop the
    // gradient and paint black; Modifier.background is the safe path.
    val dividerBrush = remember {
        Brush.horizontalGradient(
            colors = listOf(Color.Transparent, IdleFgFaint, Color.Transparent),
        )
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .height(1.dp)
                .background(dividerBrush),
        )
        Text(
            text = "silence, in its own key",
            fontFamily = NpFrauncesFamily,
            fontSize = 18.sp,
            fontStyle = FontStyle.Italic,
            fontWeight = FontWeight.W400,
            letterSpacing = 0.3.sp,
            color = IdleFgDim,
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .height(1.dp)
                .background(dividerBrush),
        )
    }
}
