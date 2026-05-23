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
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
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
import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import com.sendspindroid.ui.adaptive.TvInitialFocus
import com.sendspindroid.ui.adaptive.overscanSafe
import com.sendspindroid.ui.theme.NpFrauncesFamily
import com.sendspindroid.ui.theme.NpInterFamily
import java.util.Calendar
import java.util.Locale

private val IdleBase = Color(0xFF07060D)
private val IdleFg = Color(0xFFFAF6F0)
private val IdleFgDim = Color(0xFFFAF6F0).copy(alpha = 0.60f)
private val IdleFgFaint = Color(0xFFFAF6F0).copy(alpha = 0.35f)
private val BlobTintA = Color(0xFF6A4D9A).copy(alpha = 0.4f)
private val BlobTintB = Color(0xFFD98C58).copy(alpha = 0.67f)

@Composable
fun NowPlayingIdleScreen(
    accent: Color,
    groupLabel: String,
    modifier: Modifier = Modifier,
) {
    val focusAnchor = remember { FocusRequester() }
    TvInitialFocus(focusAnchor)

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
            IdleVignette()
            IdleGrain()
        }

        Box(
            modifier = Modifier
                .size(1.dp)
                .focusRequester(focusAnchor)
                .focusable(),
        )

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
                StandbyBadge(groupLabel = groupLabel)
                Wordmark(accent = accent)
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
private fun IdleVignette() {
    // BoxWithConstraints reads the layout size so the brush radius can be
    // computed once and applied via Modifier.background. Shield Tegra
    // renders drawRect(brush=...) as black; Modifier.background works.
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        val brush = remember(w, h) {
            Brush.radialGradient(
                colorStops = arrayOf(
                    0.35f to Color.Transparent,
                    1.0f to Color.Black,
                ),
                center = Offset(w * 0.5f, h * 0.5f),
                radius = maxOf(w, h) * 0.8f,
            )
        }
        Box(modifier = Modifier.fillMaxSize().background(brush))
    }
}

@Composable
private fun IdleGrain() {
    val grain = SharedGrainBitmap
    val brush = remember(grain) {
        ShaderBrush(ImageShader(grain, TileMode.Repeated, TileMode.Repeated))
    }
    // See GrainOverlay (AmbientBg.kt) for why this uses Modifier.background
    // rather than drawRect inside drawWithCache. Same Shield rendering caveat.
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(brush = brush, alpha = 0.012f),
    )
}

@Composable
private fun StandbyBadge(groupLabel: String) {
    val label = buildString {
        append("Standby")
        if (groupLabel.isNotBlank()) {
            append(" · ")
            append(groupLabel)
        }
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .drawWithCache {
                    onDrawBehind {
                        drawCircle(color = IdleFgFaint)
                    }
                },
        )
        Text(
            text = label.uppercase(Locale.getDefault()),
            fontFamily = NpInterFamily,
            fontSize = 16.sp,
            fontWeight = FontWeight.W500,
            letterSpacing = 2.5.sp,
            color = IdleFgDim,
        )
    }
}

@Composable
private fun Wordmark(accent: Color) {
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
            text = "Audio · Ready".uppercase(Locale.getDefault()),
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
    val now by rememberCurrentTime()
    val hour = now.get(Calendar.HOUR_OF_DAY).toString().padStart(2, '0')
    val minute = now.get(Calendar.MINUTE).toString().padStart(2, '0')
    val weekday = now.getDisplayName(Calendar.DAY_OF_WEEK, Calendar.LONG, Locale.getDefault()).orEmpty()
    val month = now.getDisplayName(Calendar.MONTH, Calendar.LONG, Locale.getDefault()).orEmpty()
    val day = now.get(Calendar.DAY_OF_MONTH)
    val year = now.get(Calendar.YEAR)

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = hour,
                fontFamily = NpFrauncesFamily,
                fontSize = 240.sp,
                lineHeight = 240.sp,
                fontWeight = FontWeight.W300,
                letterSpacing = (-8).sp,
                color = IdleFg,
            )
            PulsingColon(accent = accent)
            Text(
                text = minute,
                fontFamily = NpFrauncesFamily,
                fontSize = 240.sp,
                lineHeight = 240.sp,
                fontWeight = FontWeight.W300,
                letterSpacing = (-8).sp,
                color = IdleFg,
            )
        }
        Text(
            text = "$weekday · $month $day, $year".uppercase(Locale.getDefault()),
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
