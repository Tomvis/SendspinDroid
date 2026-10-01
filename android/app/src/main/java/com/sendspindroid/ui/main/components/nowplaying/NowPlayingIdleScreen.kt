package com.sendspindroid.ui.main.components.nowplaying

import androidx.annotation.StringRes
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sendspindroid.R
import com.sendspindroid.model.AppConnectionState
import com.sendspindroid.ui.adaptive.TvPassiveFocusAnchor
import com.sendspindroid.ui.adaptive.overscanSafe
import com.sendspindroid.ui.theme.NpCream
import com.sendspindroid.ui.theme.NpCreamDim
import com.sendspindroid.ui.theme.NpRubikFamily
import com.sendspindroid.ui.theme.NpTabular
import java.util.Calendar
import java.util.Locale

private val IdleBase = Color(0xFF16222A) // home night (HW-48)
private val IdleFg = NpCream
private val IdleFgDim = NpCreamDim
private val IdleFgFaint = NpCream.copy(alpha = 0.35f)
private val IdleFgError = Color(0xFFFF8AA0) // home dark alarm
private val BlobTintA = Color(0xFF587E8D).copy(alpha = 0.4f)
private val BlobTintB = Color(0xFFBAD2DE).copy(alpha = 0.30f)

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
 *
 * [dotLabel] and [wordmarkLabel] are string-resource ids rather than resolved
 * text, because an enum constructor is not a composable scope. Each surface
 * resolves its own label with `stringResource` at the point it draws it.
 */
private enum class IdleStatus(
    @param:StringRes val dotLabel: Int,
    @param:StringRes val wordmarkLabel: Int,
    val pulses: Boolean,
    val dotColor: Color?,
) {
    READY(R.string.np_tv_idle_standby, R.string.np_tv_idle_standby_sub, pulses = false, dotColor = IdleFgFaint),
    CONNECTING(R.string.np_tv_idle_connecting, R.string.np_tv_idle_connecting_sub, pulses = true, dotColor = null),
    RECONNECTING(R.string.np_tv_idle_reconnecting, R.string.np_tv_idle_reconnecting_sub, pulses = true, dotColor = null),
    OFFLINE(R.string.np_tv_idle_offline, R.string.np_tv_idle_offline_sub, pulses = false, dotColor = IdleFgFaint),
    ERROR(R.string.np_tv_idle_error, R.string.np_tv_idle_error_sub, pulses = true, dotColor = IdleFgError),
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

    // The four blobs are one art-directed composition, hand-placed to balance
    // the frame in design-canvas pixels. No size or offset below recurs anywhere
    // else or constrains another component, so none of them is a design token --
    // naming them would imply a reuse contract that does not exist. They read as
    // a set through the Blob() call signature instead.
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
        label = badgeLabel(status = stringResource(status.dotLabel), groupLabel = groupLabel),
        labelColor = IdleFgDim,
    ) {
        Box(
            modifier = Modifier
                .size(NowPlayingTvTokens.Dimen.StatusDot)
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
    // buildAnnotatedString takes a non-composable lambda, so both halves of the
    // wordmark have to be resolved before it. `wordmarkDroid` rather than
    // `accent` because that name is already the accent *colour* parameter.
    val wordmarkSendspin = stringResource(R.string.np_tv_wordmark_primary)
    val wordmarkDroid = stringResource(R.string.np_tv_wordmark_accent)
    val annotated = buildAnnotatedString {
        withStyle(
            SpanStyle(
                fontStyle = FontStyle.Italic,
                fontWeight = FontWeight.W400,
                color = IdleFg,
            ),
        ) {
            append(wordmarkSendspin)
        }
        withStyle(
            SpanStyle(
                fontWeight = FontWeight.W500,
                color = accent,
            ),
        ) {
            append(wordmarkDroid)
        }
    }
    Column(horizontalAlignment = Alignment.End) {
        Text(
            text = annotated,
            fontFamily = NpRubikFamily,
            fontSize = NowPlayingTvTokens.Type.Heading,
            letterSpacing = NowPlayingTvTokens.Type.TrackDisplay,
            lineHeight = NowPlayingTvTokens.Type.Heading,
        )
        Text(
            // Locale.ROOT, not getDefault(): the app ships a single English
            // string set, so on a Turkish-locale device getDefault() would
            // uppercase the i in "Audio" to a dotted I. See badgeLabel.
            text = stringResource(status.wordmarkLabel).uppercase(Locale.ROOT),
            modifier = Modifier.padding(top = NowPlayingTvTokens.Space.SublabelGap),
            fontFamily = NpRubikFamily,
            fontSize = NowPlayingTvTokens.Type.Caption,
            fontWeight = FontWeight.W600,
            letterSpacing = NowPlayingTvTokens.Type.TrackSublabel,
            color = IdleFgFaint,
            textAlign = TextAlign.End,
        )
    }
}

@Composable
private fun HeroClock(accent: Color) {
    val clock = rememberClockParts(monthStyle = Calendar.LONG)
    // Resolve the layout, then format it with Locale.ROOT. stringResource(id,
    // args) would format against the config locale instead, which emits
    // Arabic-Indic digits for the day and year on some devices -- the same trap
    // formatTime's Locale.ROOT guards against. The weekday and month words are
    // still genuinely localised; rememberClockParts pulls them off the platform.
    val dateFmt = stringResource(R.string.np_tv_date_long)

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = clock.hour,
                fontFamily = NpRubikFamily,
                style = NpTabular,
                fontSize = NowPlayingTvTokens.Type.HeroClock,
                lineHeight = NowPlayingTvTokens.Type.HeroClock,
                fontWeight = FontWeight.W300,
                letterSpacing = NowPlayingTvTokens.Type.TrackHeroClock,
                color = IdleFg,
            )
            PulsingColon(accent = accent)
            Text(
                text = clock.minute,
                fontFamily = NpRubikFamily,
                style = NpTabular,
                fontSize = NowPlayingTvTokens.Type.HeroClock,
                lineHeight = NowPlayingTvTokens.Type.HeroClock,
                fontWeight = FontWeight.W300,
                letterSpacing = NowPlayingTvTokens.Type.TrackHeroClock,
                color = IdleFg,
            )
        }
        Text(
            text = String.format(
                Locale.ROOT,
                dateFmt,
                clock.weekday,
                clock.month,
                clock.day,
                clock.year,
            ).uppercase(Locale.ROOT),
            modifier = Modifier.padding(top = NowPlayingTvTokens.Space.SectionGap),
            fontFamily = NpRubikFamily,
            fontSize = NowPlayingTvTokens.Type.Body,
            fontWeight = FontWeight.W500,
            letterSpacing = NowPlayingTvTokens.Type.TrackHeroDate,
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

    // Local geometry that centres the two dots against the 240px hero digits:
    // a fixed-width gutter between hour and minute, tall enough to hold both.
    // The 48.dp here is *not* Space.SideInset -- it is coincidentally the same
    // number and means something entirely different, so it stays inline.
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
    // drawn inside Canvas, paints black). Wrap in a Dimen.ColonGlow box so the
    // auto-fit radius (min/2, i.e. Dimen.ColonDot) matches the original
    // size.minDimension glow radius -- visually equivalent. Keyed on color so
    // the shader is recycled across every pulse-driven recompose.
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
                .size(NowPlayingTvTokens.Dimen.ColonGlow)
                .graphicsLayer {
                    val s = scale()
                    scaleX = s
                    scaleY = s
                },
            contentAlignment = Alignment.Center,
        ) {
            // Glow halo: a Dimen.ColonGlow box with a radial gradient that fades
            // to transparent at the box edge. Clipped to a circle so the
            // background fills a disc rather than a square.
            Box(
                modifier = Modifier
                    .size(NowPlayingTvTokens.Dimen.ColonGlow)
                    .background(glowBrush, CircleShape),
            )
            // Solid inner dot stays in Canvas -- flat color, no brush, no
            // Shield issue.
            Canvas(modifier = Modifier.size(NowPlayingTvTokens.Dimen.ColonDot)) {
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
        modifier = Modifier.fillMaxWidth().padding(top = NowPlayingTvTokens.Space.HorizonTop),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(NowPlayingTvTokens.Space.HorizonGap),
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .height(1.dp)
                .background(dividerBrush),
        )
        Text(
            text = stringResource(R.string.np_tv_idle_tagline),
            fontFamily = NpRubikFamily,
            fontSize = NowPlayingTvTokens.Type.Label,
            fontStyle = FontStyle.Italic,
            fontWeight = FontWeight.W400,
            // Sub-1sp optical nudge on one italic line -- not part of the
            // tracked-uppercase ladder in Type, so it stays inline.
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
