package com.sendspindroid.ui.adaptive

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.dp
import com.sendspindroid.ui.theme.focusRing
import com.sendspindroid.ui.tv.LocalTvMenu

/** Horizontal TV safe-area margin (Android TV layout guidance: 48dp). */
val TvOverscanHorizontal = 48.dp

/** Vertical TV safe-area margin (Android TV layout guidance: 27dp). */
val TvOverscanVertical = 27.dp

/**
 * TV focus treatment for a control that is focusable on its own, such as a
 * Button, an IconButton or anything with Modifier.clickable:
 * - a ring in the theme's focusRing colour (fork), drawn just outside the control
 *   in its own [shape], so it shows against a filled primary button too
 * - a scale of [focusScale] while focused
 *
 * It adds no focus target of its own. Placed before the control's modifiers,
 * onFocusChanged observes the control's focus target, so each control stays a
 * single stop for the D-pad and OK reaches its click handler at once.
 *
 * On other form factors it only attaches [focusRequester].
 *
 * @param focusRequester Optional FocusRequester for programmatic focus control
 * @param shape The control's shape, which the ring follows
 * @param focusScale Scale factor when focused (1.0 = no scale)
 */
fun Modifier.tvFocusRing(
    focusRequester: FocusRequester? = null,
    shape: Shape = RoundedCornerShape(12.dp),
    focusScale: Float = 1.1f
): Modifier = composed {
    val withRequester = if (focusRequester != null) this.focusRequester(focusRequester) else this
    if (LocalFormFactor.current != FormFactor.TV) {
        return@composed withRequester
    }

    var isFocused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (isFocused) focusScale else 1f,
        label = "tv_focus_scale"
    )
    // Fork (hw48): the home theme's lit focus colour
    val ringColor = MaterialTheme.colorScheme.focusRing

    withRequester
        .scale(scale)
        .drawWithContent {
            drawContent()
            if (isFocused) {
                val gap = RingGap.toPx()
                val width = RingWidth.toPx()
                val inset = gap + width / 2
                val outline = shape.createOutline(
                    Size(size.width + inset * 2, size.height + inset * 2),
                    layoutDirection,
                    this
                )
                translate(-inset, -inset) {
                    drawOutline(outline, ringColor, style = Stroke(width))
                }
            }
        }
        .onFocusChanged { isFocused = it.isFocused }
}

private val RingGap = 2.dp
private val RingWidth = 3.dp

/**
 * TV focus treatment for an element that is not otherwise focusable, such as
 * the album art card: it adds a focus target as well as the ring and scale of
 * [tvFocusRing]. Do not use it on something clickable, which already has a
 * focus target; that would make the element two D-pad stops. Use
 * [tvFocusRing] there.
 *
 * On other form factors it only attaches [focusRequester] and adds focusable().
 */
fun Modifier.tvFocusable(
    focusRequester: FocusRequester? = null,
    shape: Shape = RoundedCornerShape(12.dp),
    focusScale: Float = 1.05f
): Modifier = this
    .tvFocusRing(focusRequester = focusRequester, shape = shape, focusScale = focusScale)
    .focusable()

/**
 * Requests focus once for TV initial focus: on first composition, or when
 * [canFocus] first becomes true.
 * Use this to auto-focus the primary control (e.g., Play button) when a TV screen appears.
 *
 * @param focusRequester The FocusRequester to request focus on
 * @param canFocus Whether the control can take focus yet. A disabled control
 *   cannot, and the request waits until it can.
 */
@Composable
fun TvInitialFocus(focusRequester: FocusRequester, canFocus: Boolean = true) {
    val formFactor = LocalFormFactor.current
    if (formFactor == FormFactor.TV) {
        var done by remember { mutableStateOf(false) }
        LaunchedEffect(canFocus) {
            if (canFocus && !done) done = focusRequester.requestFocus()
        }
    }
}

/**
 * Fork: park initial focus on an invisible 1dp anchor for a passive screen
 * that has no interactive elements of its own, so the host Activity still
 * receives D-pad key events (e.g. BACK), and D-pad toward the TV side menu
 * opens it. Place it inside the screen's root container.
 */
@Composable
fun TvPassiveFocusAnchor() {
    val focusAnchor = remember { FocusRequester() }
    // Inside TvShell: D-pad toward the side menu goes to it. The anchor has
    // no size, so Compose's own search from it finds nothing there.
    val menu = LocalTvMenu.current
    TvInitialFocus(focusAnchor)
    Box(
        modifier = Modifier
            .size(1.dp)
            .focusRequester(focusAnchor)
            .focusProperties { if (menu != null) start = menu }
            .focusable(),
    )
}

/**
 * For an element that is focusable only some of the time: when it stops being
 * focusable while it has focus, focus moves to [fallback]. Compose drops focus
 * to nothing when the focused element goes, which leaves a remote with nothing
 * on screen to act on.
 *
 * Put the returned modifier on the element, before its focus target, or on a
 * container of it. [canFocus] is whether the element is focusable now.
 */
@Composable
fun rememberFocusFallback(canFocus: Boolean, fallback: FocusRequester): Modifier {
    var hasFocus by remember { mutableStateOf(false) }
    val canFocusNow by rememberUpdatedState(canFocus)
    LaunchedEffect(canFocus) {
        if (!canFocus && hasFocus) {
            hasFocus = false
            fallback.requestFocus()
        }
    }
    // The focus change that the element's own removal causes is ignored:
    // that loss is what the effect above looks for.
    return remember { Modifier.onFocusChanged { if (canFocusNow) hasFocus = it.hasFocus } }
}

/**
 * Returns focus to [target] on a TV when a group of controls that are
 * disabled together, such as the transport controls while nothing plays, is
 * enabled again. A disabled control cannot keep focus, so the focus the group
 * had is lost when it is disabled, and nothing else on the screen may take
 * it. Focus that left the group while it was enabled is not taken back. Put
 * the returned modifier on the group.
 */
@Composable
fun rememberFocusReturn(enabled: Boolean, target: FocusRequester): Modifier {
    val isTv = LocalFormFactor.current == FormFactor.TV
    var hadFocus by remember { mutableStateOf(false) }
    val enabledNow by rememberUpdatedState(enabled)
    LaunchedEffect(enabled) {
        if (isTv && enabled && hadFocus) target.requestFocus()
    }
    // The focus change that disabling the group causes is ignored: it is the
    // loss this gives back.
    return remember { Modifier.onFocusChanged { if (enabledNow) hadFocus = it.hasFocus } }
}

/**
 * Applies the TV safe-area margins ([TvOverscanHorizontal] at the sides,
 * [TvOverscanVertical] at the top and bottom) on TV devices.
 * On non-TV devices, applies no padding.
 *
 * Use this on the outermost container of each screen to ensure
 * content is visible within the TV's safe display area.
 */
fun Modifier.overscanSafe(): Modifier = composed {
    val formFactor = LocalFormFactor.current
    if (formFactor == FormFactor.TV) {
        this.padding(horizontal = TvOverscanHorizontal, vertical = TvOverscanVertical)
    } else {
        this
    }
}

/**
 * Applies overscan-safe horizontal padding only on TV devices (48dp left/right).
 * Useful when vertical overscan is handled by scroll containers.
 */
fun Modifier.overscanSafeHorizontal(): Modifier = composed {
    val formFactor = LocalFormFactor.current
    if (formFactor == FormFactor.TV) {
        this.padding(horizontal = TvOverscanHorizontal)
    } else {
        this
    }
}
