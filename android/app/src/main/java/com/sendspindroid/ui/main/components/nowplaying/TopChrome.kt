package com.sendspindroid.ui.main.components.nowplaying

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import androidx.compose.animation.animateColorAsState
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sendspindroid.ui.theme.NpInterFamily
import java.util.Calendar
import java.util.Locale

private val StatusGreen = Color(0xFF7EE07E)
private val StatusAmber = Color(0xFFF5A524)
private val ChromeFg = Color(0xFFFFFFFF).copy(alpha = 0.70f)
private val ChromeFgFaint = Color(0xFFFFFFFF).copy(alpha = 0.55f)

@Composable
fun SourceBadge(
    paused: Boolean,
    groupLabel: String,
    isBuffering: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val dotColor by animateColorAsState(
        targetValue = if (paused) StatusAmber else StatusGreen,
        animationSpec = tween(durationMillis = 500),
        label = "np-dot-color",
    )
    // Buffering breath: the dot scales 0.85<->1.15 and the halo alpha rides
    // along, so the badge reads as "actively working" without changing color
    // or label vocabulary. Driven by a single infinite transition that only
    // ticks when isBuffering is true (the .takeIf gate keeps the targets
    // identical at rest so animateFloatAsState idles).
    val pulseTransition = rememberInfiniteTransition(label = "np-badge-pulse")
    val pulseScale by pulseTransition.animateFloat(
        initialValue = if (isBuffering) 0.85f else 1f,
        targetValue = if (isBuffering) 1.18f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1100, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "np-badge-pulse-scale",
    )
    val pulseAlpha by pulseTransition.animateFloat(
        initialValue = if (isBuffering) 0.55f else 1f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1100, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "np-badge-pulse-alpha",
    )
    val label = buildString {
        append(
            when {
                isBuffering -> "Buffering"
                paused -> "Paused"
                else -> "Now Playing"
            }
        )
        if (groupLabel.isNotBlank()) {
            append(" · ")
            append(groupLabel)
        }
    }

    // Use Modifier.background for the glow halo rather than a Canvas-style
    // drawCircle(brush=...): the Shield Tegra renderer can drop the gradient
    // and paint black; Modifier.background with CircleShape is the safe path.
    // Default Brush.radialGradient auto-fits center / radius to the bounding
    // box, matching the previous size.minDimension/2 layout.
    //
    // Two pre-built halo brushes (one per status) and a hard switch on
    // `paused`. Keying the brush on the animated dotColor would re-allocate
    // a radial gradient (and its backing Shader) on every frame of the
    // 500ms color tween — ~30 Shader allocations per play/pause toggle on
    // the Shield Tegra. The inner dot still animates via dotColor so the
    // status transition reads smoothly; the 20dp halo's hard color switch
    // is imperceptible against that.
    val glowBrushGreen = remember {
        Brush.radialGradient(
            colors = listOf(StatusGreen.copy(alpha = 0.55f), Color.Transparent),
        )
    }
    val glowBrushAmber = remember {
        Brush.radialGradient(
            colors = listOf(StatusAmber.copy(alpha = 0.55f), Color.Transparent),
        )
    }
    val glowBrush = if (paused) glowBrushAmber else glowBrushGreen
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier.size(20.dp),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(20.dp)
                    .graphicsLayer {
                        scaleX = pulseScale
                        scaleY = pulseScale
                        alpha = pulseAlpha
                    }
                    .background(glowBrush, CircleShape),
            )
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .graphicsLayer {
                        scaleX = pulseScale
                        scaleY = pulseScale
                    }
                    .background(dotColor, CircleShape),
            )
        }
        Text(
            text = label.uppercase(Locale.getDefault()),
            fontFamily = NpInterFamily,
            fontSize = 16.sp,
            fontWeight = FontWeight.W500,
            letterSpacing = 2.5.sp,
            color = ChromeFg,
        )
    }
}

/**
 * Returns a State<Calendar> that updates on every system minute tick and on
 * time/timezone changes. Uses ACTION_TIME_TICK (fires on each minute boundary)
 * so the displayed minute is always within ~1s of the wall clock.
 */
@Composable
internal fun rememberCurrentTime(): State<Calendar> {
    val state = remember { mutableStateOf(Calendar.getInstance()) }
    val context = LocalContext.current
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                state.value = Calendar.getInstance()
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(receiver, filter)
        }
        state.value = Calendar.getInstance()
        onDispose { context.unregisterReceiver(receiver) }
    }
    return state
}

@Composable
fun NowPlayingClock(modifier: Modifier = Modifier) {
    val now by rememberCurrentTime()
    val hour = now.get(Calendar.HOUR_OF_DAY).toString().padStart(2, '0')
    val minute = now.get(Calendar.MINUTE).toString().padStart(2, '0')
    val weekday = now.getDisplayName(Calendar.DAY_OF_WEEK, Calendar.LONG, Locale.getDefault()).orEmpty()
    val month = now.getDisplayName(Calendar.MONTH, Calendar.SHORT, Locale.getDefault()).orEmpty()
    val day = now.get(Calendar.DAY_OF_MONTH)

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.End,
    ) {
        Text(
            text = "$hour:$minute",
            fontFamily = NpInterFamily,
            fontSize = 42.sp,
            fontWeight = FontWeight.W300,
            letterSpacing = (-0.5).sp,
            lineHeight = 42.sp,
            color = ChromeFg,
            textAlign = TextAlign.End,
        )
        Text(
            text = "$weekday · $month $day".uppercase(Locale.getDefault()),
            modifier = Modifier.padding(top = 8.dp),
            fontFamily = NpInterFamily,
            fontSize = 18.sp,
            fontWeight = FontWeight.W500,
            letterSpacing = 2.sp,
            color = ChromeFgFaint,
            textAlign = TextAlign.End,
        )
    }
}
