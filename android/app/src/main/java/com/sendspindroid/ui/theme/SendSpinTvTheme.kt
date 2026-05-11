package com.sendspindroid.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme as TvMaterialTheme
import androidx.tv.material3.darkColorScheme as tvDarkColorScheme
import androidx.tv.material3.lightColorScheme as tvLightColorScheme

/**
 * Wraps TV-only content in androidx.tv.material3.MaterialTheme, bridging the
 * ColorScheme from the surrounding [SendSpinTheme] so TV components inherit
 * the app's branding (including Material You dynamic colors on Android 12+).
 *
 * Nest this inside [SendSpinTheme] on the TV Now Playing surface. Material3
 * lookups (MaterialTheme.colorScheme.*) continue to work from the outer scope.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun SendSpinTvTheme(content: @Composable () -> Unit) {
    val m3 = MaterialTheme.colorScheme
    val isDark = LocalIsDarkTheme.current
    // Remember the bridged TV color scheme so downstream consumers of
    // MaterialTheme.colorScheme.* don't skip-fail on a fresh instance every
    // recompose. Keyed on the dark-mode flag and on the M3 scheme itself
    // (data class equality covers per-color changes from dynamic theming).
    val bridged = remember(isDark, m3) {
        val base = if (isDark) tvDarkColorScheme() else tvLightColorScheme()
        base.copy(
            primary = m3.primary,
            onPrimary = m3.onPrimary,
            primaryContainer = m3.primaryContainer,
            onPrimaryContainer = m3.onPrimaryContainer,
            secondary = m3.secondary,
            onSecondary = m3.onSecondary,
            secondaryContainer = m3.secondaryContainer,
            onSecondaryContainer = m3.onSecondaryContainer,
            tertiary = m3.tertiary,
            onTertiary = m3.onTertiary,
            tertiaryContainer = m3.tertiaryContainer,
            onTertiaryContainer = m3.onTertiaryContainer,
            background = m3.background,
            onBackground = m3.onBackground,
            surface = m3.surface,
            onSurface = m3.onSurface,
            surfaceVariant = m3.surfaceVariant,
            onSurfaceVariant = m3.onSurfaceVariant,
            error = m3.error,
            onError = m3.onError,
            errorContainer = m3.errorContainer,
            onErrorContainer = m3.onErrorContainer,
            border = m3.outline,
            borderVariant = m3.outlineVariant,
            scrim = m3.scrim
        )
    }
    TvMaterialTheme(colorScheme = bridged, content = content)
}
