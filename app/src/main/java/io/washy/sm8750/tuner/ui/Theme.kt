package io.washy.sm8750.tuner.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle

@Immutable
internal data class TunerExtendedColors(
    val positive: Color,
    val onPositive: Color,
    val positiveContainer: Color,
    val warning: Color,
    val warningContainer: Color,
    val info: Color,
    val infoContainer: Color,
    val danger: Color,
    val dangerContainer: Color,
)

private val LightExtended = TunerExtendedColors(
    positive = Color(0xFF136F46),
    onPositive = Color.White,
    positiveContainer = Color(0xFFD6F5E3),
    warning = Color(0xFF9A5000),
    warningContainer = Color(0xFFFFE8CC),
    info = Color(0xFF0E6B74),
    infoContainer = Color(0xFFD2F1F4),
    danger = Color(0xFFB3261E),
    dangerContainer = Color(0xFFFCDAD7),
)

private val DarkExtended = TunerExtendedColors(
    positive = Color(0xFF7BE0A8),
    onPositive = Color(0xFF063922),
    positiveContainer = Color(0xFF144B32),
    warning = Color(0xFFFFC168),
    warningContainer = Color(0xFF4F3200),
    info = Color(0xFF6DD9E3),
    infoContainer = Color(0xFF12464B),
    danger = Color(0xFFFF897D),
    dangerContainer = Color(0xFF5A1611),
)

internal val LocalExtendedColors = staticCompositionLocalOf { LightExtended }

internal object TunerThemeTokens {
    val extended: TunerExtendedColors
        @Composable get() = LocalExtendedColors.current
}

internal fun TextStyle.tabular(): TextStyle = copy(fontFeatureSettings = "tnum")

@Composable
internal fun TunerTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val scheme = if (dark) {
        darkColorScheme(
            primary = Color(0xFF7BE0A8),
            onPrimary = Color(0xFF063922),
            primaryContainer = Color(0xFF144B32),
            onPrimaryContainer = Color(0xFFBBF7D3),
            secondary = Color(0xFF9CD4BE),
            onSecondary = Color(0xFF0C372A),
            secondaryContainer = Color(0xFF1F4D3D),
            onSecondaryContainer = Color(0xFFB8F0DA),
            background = Color(0xFF0E1310),
            surface = Color(0xFF121814),
            surfaceContainerLowest = Color(0xFF0B0F0C),
            surfaceContainerLow = Color(0xFF171E19),
            surfaceContainer = Color(0xFF1D2620),
            surfaceContainerHigh = Color(0xFF253028),
            surfaceContainerHighest = Color(0xFF2E3B32),
            onSurface = Color(0xFFE4ECE6),
            onSurfaceVariant = Color(0xFFB4C3B8),
            outlineVariant = Color(0xFF334037),
        )
    } else {
        lightColorScheme(
            primary = Color(0xFF136F46),
            onPrimary = Color.White,
            primaryContainer = Color(0xFFD6F5E3),
            onPrimaryContainer = Color(0xFF073B23),
            secondary = Color(0xFF2D6551),
            onSecondary = Color.White,
            secondaryContainer = Color(0xFFD3ECE0),
            onSecondaryContainer = Color(0xFF113F2F),
            background = Color(0xFFF4F6F3),
            surface = Color(0xFFF4F6F3),
            surfaceContainerLowest = Color.White,
            surfaceContainerLow = Color(0xFFEEF2EC),
            surfaceContainer = Color(0xFFE5ECE5),
            surfaceContainerHigh = Color(0xFFDDE6DD),
            surfaceContainerHighest = Color(0xFFD4DFD5),
            onSurface = Color(0xFF152019),
            onSurfaceVariant = Color(0xFF47574C),
            outlineVariant = Color(0xFFC5D2C7),
        )
    }
    CompositionLocalProvider(LocalExtendedColors provides if (dark) DarkExtended else LightExtended) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
