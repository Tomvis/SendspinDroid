package com.sendspindroid.ui.main.components.nowplaying

import android.graphics.Bitmap
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
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
import kotlin.random.Random
import kotlinx.coroutines.delay

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
        IdleBlobs(accent = accent)
        IdleVignette()
        IdleGrain()

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
    val t1 by transition.animateFloat(
        0f, 1f, infiniteRepeatable(tween(38_000, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "np-idle-blob1",
    )
    val t2 by transition.animateFloat(
        0f, 1f, infiniteRepeatable(tween(46_000, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "np-idle-blob2",
    )
    val t3 by transition.animateFloat(
        0f, 1f, infiniteRepeatable(tween(52_000, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "np-idle-blob3",
    )
    val t4 by transition.animateFloat(
        0f, 1f, infiniteRepeatable(tween(60_000, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "np-idle-blob4",
    )

    Box(modifier = Modifier.fillMaxSize()) {
        Blob(
            color = accent.copy(alpha = 0.33f),
            size = 600.dp,
            offsetX = (-150).dp,
            offsetY = (-100).dp,
            driftX = 48.dp * t1,
            driftY = 36.dp * t1,
            scale = 1f + 0.1f * t1,
        )
        Blob(
            color = accent.copy(alpha = 0.20f),
            size = 500.dp,
            alignment = Alignment.TopEnd,
            offsetX = 125.dp,
            offsetY = 150.dp,
            driftX = -30.dp * t2,
            driftY = -20.dp * t2,
            scale = 1f - 0.08f * t2,
        )
        Blob(
            color = BlobTintA,
            size = 450.dp,
            alignment = Alignment.BottomCenter,
            offsetX = 0.dp,
            offsetY = 125.dp,
            driftX = -18.dp * t3,
            driftY = 22.dp * t3,
            scale = 1.05f,
        )
        Blob(
            color = BlobTintB,
            size = 350.dp,
            alignment = Alignment.Center,
            offsetX = 10.dp,
            offsetY = (-40).dp,
            driftX = 21.dp * t4,
            driftY = -17.dp * t4,
            scale = 0.9f + 0.18f * t4,
        )
    }
}

@Composable
private fun Blob(
    color: Color,
    size: Dp,
    offsetX: Dp,
    offsetY: Dp,
    driftX: Dp,
    driftY: Dp,
    scale: Float,
    alignment: Alignment = Alignment.TopStart,
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = alignment,
    ) {
        Box(
            modifier = Modifier
                .offset(x = offsetX + driftX, y = offsetY + driftY)
                .size(size)
                .graphicsLayer { scaleX = scale; scaleY = scale }
                .drawWithCache {
                    val brush = Brush.radialGradient(
                        colors = listOf(color, Color.Transparent),
                        center = Offset(this.size.width / 2f, this.size.height / 2f),
                        radius = this.size.minDimension / 2f,
                    )
                    onDrawBehind {
                        drawRect(brush = brush)
                    }
                },
        )
    }
}

@Composable
private fun IdleVignette() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .drawWithCache {
                val brush = Brush.radialGradient(
                    colorStops = arrayOf(
                        0.35f to Color.Transparent,
                        1.0f to Color.Black,
                    ),
                    center = Offset(size.width * 0.5f, size.height * 0.5f),
                    radius = maxOf(size.width, size.height) * 0.8f,
                )
                onDrawBehind {
                    drawRect(brush = brush)
                }
            },
    )
}

@Composable
private fun IdleGrain() {
    val grain = remember { buildGrain(tileSize = 256, seed = 7L) }
    val brush = remember(grain) {
        ShaderBrush(ImageShader(grain, TileMode.Repeated, TileMode.Repeated))
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .drawWithCache {
                onDrawBehind {
                    drawRect(brush = brush, alpha = 0.05f, blendMode = BlendMode.Overlay)
                }
            },
    )
}

private fun buildGrain(tileSize: Int, seed: Long): ImageBitmap {
    val bitmap = Bitmap.createBitmap(tileSize, tileSize, Bitmap.Config.ARGB_8888)
    val pixels = IntArray(tileSize * tileSize)
    val random = Random(seed)
    for (i in pixels.indices) {
        val v = random.nextInt(0, 256)
        pixels[i] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
    }
    bitmap.setPixels(pixels, 0, tileSize, 0, 0, tileSize, tileSize)
    return bitmap.asImageBitmap()
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
    var now by remember { mutableStateOf(Calendar.getInstance()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(15_000L)
            now = Calendar.getInstance()
        }
    }
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
    val pulseA by transition.animateFloat(
        initialValue = 0.95f,
        targetValue = 1.06f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2400, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "np-colon-a",
    )
    val pulseB by transition.animateFloat(
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
            scale = pulseA,
        )
        ColonDot(
            color = accent,
            alignment = androidx.compose.ui.BiasAlignment(0f, 0.36f),
            scale = pulseB,
        )
    }
}

@Composable
private fun ColonDot(
    color: Color,
    alignment: Alignment,
    scale: Float,
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = alignment,
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }
                .drawWithCache {
                    val glow = Brush.radialGradient(
                        colors = listOf(color.copy(alpha = 0.6f), Color.Transparent),
                        center = Offset(size.width / 2f, size.height / 2f),
                        radius = size.minDimension,
                    )
                    onDrawBehind {
                        drawCircle(brush = glow, radius = size.minDimension)
                        drawCircle(color = color, radius = size.minDimension / 2f)
                    }
                },
        )
    }
}

@Composable
private fun HorizonTagline() {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .height(1.dp)
                .drawWithCache {
                    val brush = Brush.horizontalGradient(
                        colors = listOf(Color.Transparent, IdleFgFaint, Color.Transparent),
                    )
                    onDrawBehind { drawRect(brush = brush) }
                },
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
                .drawWithCache {
                    val brush = Brush.horizontalGradient(
                        colors = listOf(Color.Transparent, IdleFgFaint, Color.Transparent),
                    )
                    onDrawBehind { drawRect(brush = brush) }
                },
        )
    }
}
