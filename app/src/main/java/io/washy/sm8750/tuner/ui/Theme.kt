package io.washy.sm8750.tuner.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

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

private val LightScheme = lightColorScheme(
    primary = Color(0xFF4858CE),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDDE1FF),
    onPrimaryContainer = Color(0xFF17256B),
    secondary = Color(0xFF565E80),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFDDE2FF),
    onSecondaryContainer = Color(0xFF202B54),
    background = Color(0xFFF9F7FF),
    surface = Color(0xFFF9F7FF),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF1EFFF),
    surfaceContainer = Color(0xFFECEAF9),
    surfaceContainerHigh = Color(0xFFE6E4F5),
    surfaceContainerHighest = Color(0xFFDFDDF1),
    onSurface = Color(0xFF202136),
    onSurfaceVariant = Color(0xFF54566F),
    outline = Color(0xFF787A92),
    outlineVariant = Color(0xFFCACBE0),
)

private val DarkScheme = darkColorScheme(
    primary = Color(0xFFBDC4FF),
    onPrimary = Color(0xFF19246B),
    primaryContainer = Color(0xFF303B83),
    onPrimaryContainer = Color(0xFFE1E4FF),
    secondary = Color(0xFFBFC6EA),
    onSecondary = Color(0xFF292F50),
    secondaryContainer = Color(0xFF3E4567),
    onSecondaryContainer = Color(0xFFE0E6FF),
    background = Color(0xFF141523),
    surface = Color(0xFF141523),
    surfaceContainerLowest = Color(0xFF10111E),
    surfaceContainerLow = Color(0xFF1B1D2D),
    surfaceContainer = Color(0xFF222438),
    surfaceContainerHigh = Color(0xFF2B2D45),
    surfaceContainerHighest = Color(0xFF363853),
    onSurface = Color(0xFFE7E5F6),
    onSurfaceVariant = Color(0xFFC5C5DC),
    outline = Color(0xFF9092AB),
    outlineVariant = Color(0xFF43465D),
)

private val TunerShapes = Shapes(
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(30.dp),
)

private val BaseTypography = Typography()
private val TunerTypography = Typography(
    headlineLarge = BaseTypography.headlineLarge.copy(fontWeight = FontWeight.Medium),
    titleLarge = BaseTypography.titleLarge.copy(fontWeight = FontWeight.Medium),
    titleMedium = BaseTypography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
)

@Composable
internal fun TunerTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    CompositionLocalProvider(LocalExtendedColors provides if (dark) DarkExtended else LightExtended) {
        MaterialTheme(
            colorScheme = if (dark) DarkScheme else LightScheme,
            shapes = TunerShapes,
            typography = TunerTypography,
            content = content,
        )
    }
}
