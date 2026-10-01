package com.sendspindroid.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Shared foreground token for the TV Now Playing surfaces (focus screen, idle
 * screen, progress rail). Every cream text/stroke colour on those screens is a
 * [NpCream].copy(alpha = ...) of this value, so the palette moves in one place
 * instead of via a hex literal repeated per component file.
 */
val NpCream = Color(0xFFEAF0F0) // home mist (HW-48)

/** Secondary cream used for supporting copy on both the focus and idle screens. */
val NpCreamDim = NpCream.copy(alpha = 0.60f)
