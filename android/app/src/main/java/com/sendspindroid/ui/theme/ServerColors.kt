package com.sendspindroid.ui.theme

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import com.sendspindroid.sendspin.protocol.ColorState

/**
 * This scheme with the server's color@v1 palette in the roles Now Playing
 * reads, or null when the palette has no background for this mode.
 *
 * Only the pairs roles/color/v1.md guarantees are used, in its `server/state`
 * color object section. `background_dark`: "The server MUST ensure a minimum
 * WCAG contrast ratio of 4.5:1 with white text and with `on_dark` (if also
 * present)", and `on_dark` "4.5:1 with `background_dark` (if also present)
 * and with black text, so it can also serve as an alternative light
 * background"; light mode is the same with `background_light`, `on_light`,
 * black text and white. So text is white or black, `on_X` colors the
 * controls and secondary text, and the play button's icon is black or white
 * on it. `primary` and `accent` are "Not adjusted for contrast" and are not
 * used.
 *
 * The tonal buttons' container is the background moved away from `on_X`,
 * which only raises their contrast. Containers for menus and dialogs, error
 * and the inverse colors keep the Material scheme, whose text polarity is
 * the same.
 */
fun ColorScheme.withServerColors(colors: ColorState, dark: Boolean): ColorScheme? {
    val background = (if (dark) colors.backgroundDark else colors.backgroundLight)?.let(::Color) ?: return null
    val text = if (dark) Color.White else Color.Black
    val on = (if (dark) colors.onDark else colors.onLight)?.let(::Color) ?: text
    return copy(
        background = background,
        surface = background,
        onBackground = text,
        onSurface = text,
        primary = on,
        onPrimary = if (dark) Color.Black else Color.White,
        onSurfaceVariant = on,
        secondaryContainer = if (dark) mix(background, Color.Black, 0.35f) else mix(background, Color.White, 0.5f),
        onSecondaryContainer = on,
        // Inactive slider tracks and the empty album art: decoration, no text
        surfaceVariant = mix(background, on, 0.2f),
    )
}

/**
 * [color] moved [fraction] of the way to [toward], channel by channel in
 * sRGB, so a move toward black or white changes the luminance one way only.
 */
private fun mix(color: Color, toward: Color, fraction: Float) = Color(
    red = color.red + (toward.red - color.red) * fraction,
    green = color.green + (toward.green - color.green) * fraction,
    blue = color.blue + (toward.blue - color.blue) * fraction,
)

/**
 * [content] in the server's palette, mapped by [withServerColors] for the
 * app's own light or dark mode ([LocalIsDarkTheme]), or in the Material
 * scheme when [colors] is null or has no background for that mode.
 *
 * Each change blends over [BlendMillis]. roles/color/v1.md, "Scheduled color
 * updates": "Clients MAY ease into the pending update around its timestamp
 * (e.g. a color blend)." A palette already there when this is first shown
 * is used at once.
 *
 * @param blend False to show each change at once: for a frame around other
 *   screens too, such as the TV's around the server list, which has the
 *   Material colors from its first frame.
 */
@Composable
fun ServerColorTheme(colors: ColorState?, blend: Boolean = true, content: @Composable () -> Unit) {
    val base = MaterialTheme.colorScheme
    val server = colors?.let { base.withServerColors(it, LocalIsDarkTheme.current) }
    val target = server ?: base
    // Snapped while not blending, so a later blend starts from what is shown
    val spec: AnimationSpec<Color> = if (blend) tween(BlendMillis) else snap()
    val blended = base.copy(
        background = blendTo(target.background, spec),
        surface = blendTo(target.surface, spec),
        onBackground = blendTo(target.onBackground, spec),
        onSurface = blendTo(target.onSurface, spec),
        primary = blendTo(target.primary, spec),
        onPrimary = blendTo(target.onPrimary, spec),
        onSurfaceVariant = blendTo(target.onSurfaceVariant, spec),
        secondaryContainer = blendTo(target.secondaryContainer, spec),
        onSecondaryContainer = blendTo(target.onSecondaryContainer, spec),
        surfaceVariant = blendTo(target.surfaceVariant, spec),
    )
    CompositionLocalProvider(LocalServerColors provides (server != null)) {
        MaterialTheme(colorScheme = if (blend) blended else target, content = content)
    }
}

/**
 * Whether [ServerColorTheme] shows a server's palette here.
 *
 * The contrast roles/color/v1.md guarantees, in its `server/state` color
 * object section, is for the colors as sent: `on_dark` "4.5:1 with
 * `background_dark`", and `on_light` the same with `background_light`. Drawn
 * with alpha over the background they lose it, so what the Material colors
 * dim with alpha is drawn opaque here.
 */
val LocalServerColors = compositionLocalOf { false }

/** One of the roles [withServerColors] sets, on its way to [color]. */
@Composable
private fun blendTo(color: Color, spec: AnimationSpec<Color>): Color =
    animateColorAsState(color, spec, label = "serverColor").value

private const val BlendMillis = 600
