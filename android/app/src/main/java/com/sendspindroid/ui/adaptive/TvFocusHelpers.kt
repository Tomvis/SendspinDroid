package com.sendspindroid.ui.adaptive

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * TV focus modifier that adds:
 * - Focusable behavior for D-pad navigation
 * - Visible focus ring (3dp border in primary color) when focused
 * - Subtle scale animation (1.05x) on focus for visual feedback
 *
 * Use this on all interactive elements (buttons, cards, list items) on TV.
 * On non-TV devices, this just adds focusable() without visual effects.
 *
 * @param focusRequester Optional FocusRequester for programmatic focus control
 * @param borderWidth Width of the focus ring border
 * @param cornerRadius Corner radius of the focus ring
 * @param focusScale Scale factor when focused (1.0 = no scale)
 * @param interactionSource The interaction source of a focus target the caller
 *   already owns -- i.e. the exact instance it passes to its own `clickable`,
 *   `selectable` or `focusable`. Supplying it collapses the element to a single
 *   focus target: the helper reads focus from that source and installs no focus
 *   target of its own.
 *
 *   This is not required for interactive elements in general. Most call sites
 *   here stack the helper's `focusable()` over an inner `clickable` -- the Card
 *   in `ServerListItem`, the IconButtons in `PlaybackControls`,
 *   `QueueSheetContent` and `PlayerSheetContent` -- and D-pad OK reaches the
 *   click handler normally. The parameter exists for one observed failure: the
 *   TV overflow overlay's menu item, where the stacked outer focus target took
 *   focus (ring lit) while the inner `clickable` kept the OK / DPAD_CENTER
 *   handler, so pressing OK did nothing. Reach for it when an element shows
 *   that symptom, or when it is focused programmatically inside a transient
 *   overlay, where the stacking has bitten us before.
 *
 *   Leave `null` otherwise, including for plain, non-interactive elements that
 *   need this helper to make them focusable in the first place.
 */
fun Modifier.tvFocusable(
    focusRequester: FocusRequester? = null,
    borderWidth: Dp = 3.dp,
    cornerRadius: Dp = 12.dp,
    focusScale: Float = 1.05f,
    interactionSource: MutableInteractionSource? = null
): Modifier = composed {
    val formFactor = LocalFormFactor.current
    val withRequester = this
        .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)

    if (formFactor != FormFactor.TV) {
        // No ring off-TV; only become a focus target when the caller isn't one.
        return@composed if (interactionSource != null) withRequester else withRequester.focusable()
    }

    if (interactionSource != null) {
        // Caller owns the one and only focus target -- we just paint the ring.
        val isFocused by interactionSource.collectIsFocusedAsState()
        return@composed withRequester.tvFocusRing(isFocused, borderWidth, cornerRadius, focusScale)
    }

    var isFocused by remember { mutableStateOf(false) }
    withRequester
        .tvFocusRing(isFocused, borderWidth, cornerRadius, focusScale)
        .onFocusChanged { isFocused = it.isFocused }
        .focusable()
}

/**
 * Draws the shared TV focus ring (scale + border) for an element whose focus
 * state has already been resolved. Split out of [tvFocusable] so that both the
 * self-focusing path and the caller-owned-focus-target path render an identical
 * ring from one place.
 */
@Composable
private fun Modifier.tvFocusRing(
    isFocused: Boolean,
    borderWidth: Dp,
    cornerRadius: Dp,
    focusScale: Float
): Modifier {
    val scale by animateFloatAsState(
        targetValue = if (isFocused) focusScale else 1f,
        label = "tv_focus_scale"
    )
    val borderColor = if (isFocused) {
        MaterialTheme.colorScheme.primary
    } else {
        Color.Transparent
    }
    return this
        .scale(scale)
        .border(
            width = borderWidth,
            color = borderColor,
            shape = RoundedCornerShape(cornerRadius)
        )
}

/**
 * Counter that wrapper composables increment whenever a transient overlay (e.g.
 * the TV overflow menu) is dismissed and focus needs to be reclaimed by the
 * underlying screen. [TvInitialFocus] keys on this so the anchor re-requests
 * focus instead of being stranded after the overlay tears down.
 *
 * Default is `0`; the harness only acts on changes from the initial value.
 */
val LocalTvFocusReclaimToken = compositionLocalOf { 0 }

/**
 * Requests focus on first composition for TV initial focus, and again whenever
 * [LocalTvFocusReclaimToken] changes (e.g. after a TV overflow overlay closes).
 * Use this to auto-focus the primary control (e.g., Play button) when a TV
 * screen appears and to recover focus after transient overlays dismiss.
 *
 * @param focusRequester The FocusRequester to trigger on first composition
 */
@Composable
fun TvInitialFocus(focusRequester: FocusRequester) {
    val formFactor = LocalFormFactor.current
    val reclaim = LocalTvFocusReclaimToken.current
    if (formFactor == FormFactor.TV) {
        LaunchedEffect(reclaim) {
            focusRequester.requestFocus()
        }
    }
}

/**
 * Park initial focus on an invisible 1dp anchor for a passive screen that has
 * no interactive elements of its own, so the host Activity still receives D-pad
 * key events (e.g. BACK). Creates the anchor, requests focus via [TvInitialFocus],
 * and emits the focusable Box at the call site -- place it inside the screen's
 * root container.
 */
@Composable
fun TvPassiveFocusAnchor() {
    val focusAnchor = remember { FocusRequester() }
    TvInitialFocus(focusAnchor)
    Box(
        modifier = Modifier
            .size(1.dp)
            .focusRequester(focusAnchor)
            .focusable(),
    )
}

/**
 * Applies overscan-safe padding on TV devices (48dp all sides).
 * On non-TV devices, applies no padding.
 *
 * Use this on the outermost container of each screen to ensure
 * content is visible within the TV's safe display area.
 */
fun Modifier.overscanSafe(): Modifier = composed {
    val formFactor = LocalFormFactor.current
    if (formFactor == FormFactor.TV) {
        this.padding(48.dp)
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
        this.padding(horizontal = 48.dp)
    } else {
        this
    }
}
