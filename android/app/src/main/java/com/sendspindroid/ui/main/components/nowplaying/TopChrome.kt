package com.sendspindroid.ui.main.components.nowplaying

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
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
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.util.lerp
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
    // Buffering breath: the dot scales 0.85<->1.18 and the halo alpha rides
    // along, so the badge reads as "actively working" without changing color
    // or label vocabulary.
    //
    // Driven by a single Animatable on a 0f..1f fraction and a LaunchedEffect
    // rather than rememberInfiniteTransition: the latter captures `initialValue`
    // once at first composition, so when isBuffering later flips false->true the
    // pulse would continue from its current value toward the end instead of
    // restarting from 0 -- the first half-cycle of every buffering start was
    // visibly off. With Animatable we snap the fraction to 0 and launch a fresh
    // infinite animation each time isBuffering goes true.
    //
    // Scale and alpha share identical 1100ms LinearEasing Reverse timing, so one
    // fraction drives both via lerp. The rest state (scale=1f, alpha=1f) is NOT
    // on the pulse's linear line, so both outputs are gated on isBuffering and
    // forced to 1f at rest -- the fraction value is then irrelevant.
    val pulseAnim = remember { Animatable(0f) }
    LaunchedEffect(isBuffering) {
        if (isBuffering) {
            pulseAnim.snapTo(0f)
            pulseAnim.animateTo(
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 1100, easing = LinearEasing),
                    repeatMode = RepeatMode.Reverse,
                ),
            )
        } else {
            pulseAnim.snapTo(0f)
        }
    }
    // Build the label once per state change and do NOT read pulseAnim.value at
    // composable scope -- reading it here would recompose the whole badge every
    // frame while buffering, rebuilding the string and re-running uppercase().
    // The per-frame fraction is read inside the dot/halo graphicsLayer lambdas
    // below, which run in the draw phase and don't invalidate composition.
    val label = remember(isBuffering, paused, groupLabel) {
        badgeLabel(
            status = when {
                isBuffering -> "Buffering"
                paused -> "Paused"
                else -> "Now Playing"
            },
            groupLabel = groupLabel,
        )
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
    StatusBadge(label = label, labelColor = ChromeFg, modifier = modifier) {
        Box(
            modifier = Modifier.size(20.dp),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(20.dp)
                    .graphicsLayer {
                        val frac = pulseAnim.value
                        val s = if (isBuffering) lerp(0.85f, 1.18f, frac) else 1f
                        scaleX = s
                        scaleY = s
                        alpha = if (isBuffering) lerp(0.55f, 1f, frac) else 1f
                    }
                    .background(glowBrush, CircleShape),
            )
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .graphicsLayer {
                        val s = if (isBuffering) lerp(0.85f, 1.18f, pulseAnim.value) else 1f
                        scaleX = s
                        scaleY = s
                    }
                    .background(dotColor, CircleShape),
            )
        }
    }
}

/**
 * Status dot + label row shared by the focus screen's [SourceBadge] and the
 * idle screen's standby badge, so the two read as the same instrument in two
 * states. Callers supply [dot] because the focus badge carries a glow halo the
 * idle badge doesn't; the row metrics and label typography are fixed here.
 */
@Composable
internal fun StatusBadge(
    label: String,
    labelColor: Color,
    modifier: Modifier = Modifier,
    dot: @Composable () -> Unit,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        dot()
        Text(
            text = label,
            fontFamily = NpInterFamily,
            fontSize = 16.sp,
            fontWeight = FontWeight.W500,
            letterSpacing = 2.5.sp,
            color = labelColor,
        )
    }
}

/** "NOW PLAYING · KITCHEN" -- the badge label form used by both screens. */
internal fun badgeLabel(status: String, groupLabel: String): String = buildString {
    append(status)
    if (groupLabel.isNotBlank()) {
        append(" · ")
        append(groupLabel)
    }
}.uppercase(Locale.getDefault())

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

/** Display-ready fields derived from [rememberCurrentTime]. */
internal class ClockParts(
    val hour: String,
    val minute: String,
    val weekday: String,
    val month: String,
    val day: Int,
    val year: Int,
)

/**
 * Derives the zero-padded 24h time and localised date fields from the shared
 * minute-tick clock. [monthStyle] is a `Calendar` display style -- the top-bar
 * clock uses SHORT, the idle hero clock LONG -- so a 12h/24h or locale-format
 * change lands in one place for both.
 */
@Composable
internal fun rememberClockParts(monthStyle: Int = Calendar.SHORT): ClockParts {
    val now by rememberCurrentTime()
    return ClockParts(
        hour = now.get(Calendar.HOUR_OF_DAY).toString().padStart(2, '0'),
        minute = now.get(Calendar.MINUTE).toString().padStart(2, '0'),
        weekday = now.getDisplayName(Calendar.DAY_OF_WEEK, Calendar.LONG, Locale.getDefault()).orEmpty(),
        month = now.getDisplayName(Calendar.MONTH, monthStyle, Locale.getDefault()).orEmpty(),
        day = now.get(Calendar.DAY_OF_MONTH),
        year = now.get(Calendar.YEAR),
    )
}

@Composable
fun NowPlayingClock(modifier: Modifier = Modifier) {
    val clock = rememberClockParts()

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.End,
    ) {
        Text(
            text = "${clock.hour}:${clock.minute}",
            fontFamily = NpInterFamily,
            fontSize = 42.sp,
            fontWeight = FontWeight.W300,
            letterSpacing = (-0.5).sp,
            lineHeight = 42.sp,
            color = ChromeFg,
            textAlign = TextAlign.End,
        )
        Text(
            text = "${clock.weekday} · ${clock.month} ${clock.day}".uppercase(Locale.getDefault()),
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
