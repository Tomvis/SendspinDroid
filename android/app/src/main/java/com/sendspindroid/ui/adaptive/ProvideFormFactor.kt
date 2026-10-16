package com.sendspindroid.ui.adaptive

import androidx.compose.material.ripple.RippleAlpha
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.RippleConfiguration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * Provides [formFactor] to [content] through [LocalFormFactor].
 *
 * On a TV it also makes focus visible on every Material3 control. Their own
 * focus treatment is a state layer of 10% opacity (material3 1.4), which
 * cannot be told apart across a room, and Material3 draws it with its own
 * ripple rather than LocalIndication, so the ripple configuration is the one
 * place that reaches them all.
 */
@Composable
fun ProvideFormFactor(formFactor: FormFactor, content: @Composable () -> Unit) {
    if (formFactor == FormFactor.TV) {
        CompositionLocalProvider(
            LocalFormFactor provides formFactor,
            LocalRippleConfiguration provides TvRippleConfiguration,
            content = content
        )
    } else {
        CompositionLocalProvider(LocalFormFactor provides formFactor, content = content)
    }
}

/**
 * [ProvideFormFactor] for the screens outside MainActivity, which only need to
 * tell a TV from everything else. They lay out the same on every other device,
 * so that is PHONE, as it was before they provided a form factor at all.
 */
@Composable
fun ProvideTvFormFactor(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val isTv = remember(context) { context.isTvDevice() }
    ProvideFormFactor(if (isTv) FormFactor.TV else FormFactor.PHONE, content)
}

/** The Material3 defaults, with the focused layer at 30% instead of 10%. */
private val TvRippleConfiguration = RippleConfiguration(
    rippleAlpha = RippleAlpha(
        draggedAlpha = 0.16f,
        focusedAlpha = 0.30f,
        hoveredAlpha = 0.08f,
        pressedAlpha = 0.10f
    )
)
