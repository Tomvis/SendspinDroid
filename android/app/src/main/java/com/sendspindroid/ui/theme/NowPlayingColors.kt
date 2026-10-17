package com.sendspindroid.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/*
 * The TV Now Playing surfaces (focus screen, idle screen, progress rail) in the person's home theme, light
 * or dark (HW-125). Every foreground there is a [NpCream].copy(alpha = ...), so the palette moves in one
 * place instead of via a literal per component file.
 */

/** Text and strokes: the theme's onBackground (the old fixed cream was dark slate's). */
val NpCream: Color
    @Composable get() = MaterialTheme.colorScheme.onBackground

/** Secondary cream used for supporting copy on both the focus and idle screens. */
val NpCreamDim: Color
    @Composable get() = NpCream.copy(alpha = 0.60f)

/** Under the blurred cover and the album art: the deepest surface. */
val NpBase: Color
    @Composable get() = MaterialTheme.colorScheme.surfaceContainerLowest

/** The vignette's edge: black in dark mode, the background in light, so the text keeps its contrast. */
val NpVignette: Color
    @Composable get() = if (LocalIsDarkTheme.current) Color.Black else MaterialTheme.colorScheme.background

/**
 * How much of the blurred cover shows over [NpBase]. Less in light mode: dark text needs a light wash,
 * and most covers are darker than a light background.
 */
val NpCoverAlpha: Float
    @Composable get() = if (LocalIsDarkTheme.current) 0.7f else 0.45f
