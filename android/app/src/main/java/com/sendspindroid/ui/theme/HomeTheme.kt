package com.sendspindroid.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import home.theme.HomeScheme
import home.theme.HomeThemes

/*
 * Home theme (HW-48; per person since HW-65, homelab-stacks theme/): every theme x light/dark as M3
 * roles, generated into home/theme/HomeThemes.kt; cyan "lit" is in no role. Fork-only file so upstream
 * merges stay clean. The XML side takes the same theme from HomeXmlTheme (HW-125).
 */

/** The person's theme (HomeThemeController) as M3 roles; generated catalog in home/theme/HomeThemes.kt. */
@Composable
fun homeColorScheme(dark: Boolean): ColorScheme {
    val theme = HomeThemes.byId(HomeThemeController.effective.collectAsState().value.theme)
    return (if (dark) theme.dark else theme.light).toColorScheme(dark)
}

/** TV D-pad focus ring: the theme's lit role reads as "focused/active" at 10 feet in both modes. */
val ColorScheme.focusRing: Color
    @Composable get() = Color(homeScheme().lit)

/** The theme's success role, for "online"/"connected" (statusOnline, HW-125). */
val ColorScheme.homeSuccess: Color
    @Composable get() = Color(homeScheme().success)

/** The theme's warning role, for "reconnecting"/"paused" (statusWarning, HW-125). */
val ColorScheme.homeWarning: Color
    @Composable get() = Color(homeScheme().warning)

/** The person's theme in the current light/dark mode. */
@Composable
private fun homeScheme(): HomeScheme {
    val theme = HomeThemes.byId(HomeThemeController.effective.collectAsState().value.theme)
    return if (LocalIsDarkTheme.current) theme.dark else theme.light
}

private fun HomeScheme.toColorScheme(dark: Boolean): ColorScheme =
    if (dark) {
        darkColorScheme(
            primary = Color(primary),
            onPrimary = Color(onPrimary),
            primaryContainer = Color(primaryContainer),
            onPrimaryContainer = Color(onPrimaryContainer),
            inversePrimary = Color(inversePrimary),
            secondary = Color(secondary),
            onSecondary = Color(onSecondary),
            secondaryContainer = Color(secondaryContainer),
            onSecondaryContainer = Color(onSecondaryContainer),
            tertiary = Color(tertiary),
            onTertiary = Color(onTertiary),
            tertiaryContainer = Color(tertiaryContainer),
            onTertiaryContainer = Color(onTertiaryContainer),
            background = Color(background),
            onBackground = Color(onBackground),
            surface = Color(surface),
            onSurface = Color(onSurface),
            surfaceVariant = Color(surfaceVariant),
            onSurfaceVariant = Color(onSurfaceVariant),
            surfaceTint = Color(surfaceTint),
            inverseSurface = Color(inverseSurface),
            inverseOnSurface = Color(inverseOnSurface),
            error = Color(error),
            onError = Color(onError),
            errorContainer = Color(errorContainer),
            onErrorContainer = Color(onErrorContainer),
            outline = Color(outline),
            outlineVariant = Color(outlineVariant),
            scrim = Color(scrim),
            surfaceBright = Color(surfaceBright),
            surfaceContainer = Color(surfaceContainer),
            surfaceContainerHigh = Color(surfaceContainerHigh),
            surfaceContainerHighest = Color(surfaceContainerHighest),
            surfaceContainerLow = Color(surfaceContainerLow),
            surfaceContainerLowest = Color(surfaceContainerLowest),
            surfaceDim = Color(surfaceDim),
        )
    } else {
        lightColorScheme(
            primary = Color(primary),
            onPrimary = Color(onPrimary),
            primaryContainer = Color(primaryContainer),
            onPrimaryContainer = Color(onPrimaryContainer),
            inversePrimary = Color(inversePrimary),
            secondary = Color(secondary),
            onSecondary = Color(onSecondary),
            secondaryContainer = Color(secondaryContainer),
            onSecondaryContainer = Color(onSecondaryContainer),
            tertiary = Color(tertiary),
            onTertiary = Color(onTertiary),
            tertiaryContainer = Color(tertiaryContainer),
            onTertiaryContainer = Color(onTertiaryContainer),
            background = Color(background),
            onBackground = Color(onBackground),
            surface = Color(surface),
            onSurface = Color(onSurface),
            surfaceVariant = Color(surfaceVariant),
            onSurfaceVariant = Color(onSurfaceVariant),
            surfaceTint = Color(surfaceTint),
            inverseSurface = Color(inverseSurface),
            inverseOnSurface = Color(inverseOnSurface),
            error = Color(error),
            onError = Color(onError),
            errorContainer = Color(errorContainer),
            onErrorContainer = Color(onErrorContainer),
            outline = Color(outline),
            outlineVariant = Color(outlineVariant),
            scrim = Color(scrim),
            surfaceBright = Color(surfaceBright),
            surfaceContainer = Color(surfaceContainer),
            surfaceContainerHigh = Color(surfaceContainerHigh),
            surfaceContainerHighest = Color(surfaceContainerHighest),
            surfaceContainerLow = Color(surfaceContainerLow),
            surfaceContainerLowest = Color(surfaceContainerLowest),
            surfaceDim = Color(surfaceDim),
        )
    }

/** Controls are tight (6dp), containers soft (20dp). */
val HomeShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(6.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(20.dp),
)
