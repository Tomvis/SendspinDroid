package com.sendspindroid.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/*
 * Home theme (HW-48, homelab-stacks theme/): slate house = structure, mist = surface,
 * cyan = "on right now" only. Fork-only file so upstream merges stay clean; the base
 * M3 roles live in Color.kt / res/values{,-night}/colors.xml.
 */

// Lit role: the only cyan. Light mode uses the dark teal for strokes/text on mist.
private val HomeLitLight = Color(0xFF0A6A60)
private val HomeLitDark = Color(0xFF14D9C4)

/** Fills the M3 surface-container roles the base schemes leave at baseline purple. */
fun ColorScheme.withHomeSurfaces(dark: Boolean): ColorScheme = if (dark) {
    copy(
        surfaceBright = Color(0xFF2E434E),
        surfaceDim = Color(0xFF16222A),
        surfaceContainerLowest = Color(0xFF111B21),
        surfaceContainerLow = Color(0xFF1A2830),
        surfaceContainer = Color(0xFF1F2F38),
        surfaceContainerHigh = Color(0xFF24353F),
        surfaceContainerHighest = Color(0xFF283B45),
    )
} else {
    copy(
        surfaceBright = Color(0xFFF6F9F9),
        surfaceDim = Color(0xFFD2DDE0),
        surfaceContainerLowest = Color(0xFFFFFFFF),
        surfaceContainerLow = Color(0xFFFFFFFF),
        surfaceContainer = Color(0xFFF3F7F7),
        surfaceContainerHigh = Color(0xFFE2EAEC),
        surfaceContainerHighest = Color(0xFFDCE6E8),
    )
}

/** TV D-pad focus ring: lit cyan reads as "focused/active" at 10 feet in both modes. */
val ColorScheme.focusRing: Color
    @Composable get() = if (LocalIsDarkTheme.current) HomeLitDark else HomeLitLight

/** Controls are tight (6dp), containers soft (20dp). */
val HomeShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(6.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(20.dp),
)
