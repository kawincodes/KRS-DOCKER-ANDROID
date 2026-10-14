package com.krstech.dockers.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.krstech.dockers.engine.ThemeMode

/**
 * shadcn/ui design tokens ("zinc" preset) ported to Compose. Components in `ui/components`
 * read these via [ShadTheme] instead of Material's color roles, so the app looks like shadcn
 * while still being able to use Material3 scaffolding (sheets, nav bar, snackbars).
 */
@Immutable
data class ShadColors(
    val background: Color,
    val foreground: Color,
    val card: Color,
    val cardForeground: Color,
    val popover: Color,
    val popoverForeground: Color,
    val primary: Color,
    val primaryForeground: Color,
    val secondary: Color,
    val secondaryForeground: Color,
    val muted: Color,
    val mutedForeground: Color,
    val accent: Color,
    val accentForeground: Color,
    val destructive: Color,
    val destructiveForeground: Color,
    val border: Color,
    val input: Color,
    val ring: Color,
    // semantic extras
    val success: Color,
    val warning: Color,
    val info: Color,
    val isDark: Boolean,
)

val ShadLight = ShadColors(
    background = Color(0xFFFFFFFF), foreground = Color(0xFF09090B),
    card = Color(0xFFFFFFFF), cardForeground = Color(0xFF09090B),
    popover = Color(0xFFFFFFFF), popoverForeground = Color(0xFF09090B),
    primary = Color(0xFF18181B), primaryForeground = Color(0xFFFAFAFA),
    secondary = Color(0xFFF4F4F5), secondaryForeground = Color(0xFF18181B),
    muted = Color(0xFFF4F4F5), mutedForeground = Color(0xFF71717A),
    accent = Color(0xFFF4F4F5), accentForeground = Color(0xFF18181B),
    destructive = Color(0xFFEF4444), destructiveForeground = Color(0xFFFAFAFA),
    border = Color(0xFFE4E4E7), input = Color(0xFFE4E4E7), ring = Color(0xFF18181B),
    success = Color(0xFF16A34A), warning = Color(0xFFD97706), info = Color(0xFF2563EB),
    isDark = false,
)

val ShadDark = ShadColors(
    background = Color(0xFF09090B), foreground = Color(0xFFFAFAFA),
    card = Color(0xFF09090B), cardForeground = Color(0xFFFAFAFA),
    popover = Color(0xFF09090B), popoverForeground = Color(0xFFFAFAFA),
    primary = Color(0xFFFAFAFA), primaryForeground = Color(0xFF18181B),
    secondary = Color(0xFF27272A), secondaryForeground = Color(0xFFFAFAFA),
    muted = Color(0xFF27272A), mutedForeground = Color(0xFFA1A1AA),
    accent = Color(0xFF27272A), accentForeground = Color(0xFFFAFAFA),
    destructive = Color(0xFFDC2626), destructiveForeground = Color(0xFFFAFAFA),
    border = Color(0xFF27272A), input = Color(0xFF27272A), ring = Color(0xFFD4D4D8),
    success = Color(0xFF22C55E), warning = Color(0xFFF59E0B), info = Color(0xFF3B82F6),
    isDark = true,
)

object ShadRadius {
    val sm = RoundedCornerShape(4.dp)
    val md = RoundedCornerShape(6.dp)
    val lg = RoundedCornerShape(8.dp)
    val xl = RoundedCornerShape(12.dp)
    val full = RoundedCornerShape(999.dp)
}

val LocalShadColors = staticCompositionLocalOf { ShadDark }

object ShadTheme {
    val colors: ShadColors
        @Composable get() = LocalShadColors.current
}

private fun ShadColors.toMaterial(): ColorScheme = if (isDark) darkColorScheme(
    primary = primary, onPrimary = primaryForeground, secondary = secondary, onSecondary = secondaryForeground,
    background = background, onBackground = foreground, surface = card, onSurface = cardForeground,
    surfaceVariant = muted, onSurfaceVariant = mutedForeground, outline = border, outlineVariant = border,
    error = destructive, onError = destructiveForeground, surfaceContainer = card, surfaceContainerHigh = secondary,
    surfaceContainerLow = background, surfaceContainerHighest = secondary, surfaceContainerLowest = background,
    primaryContainer = secondary, onPrimaryContainer = foreground, secondaryContainer = secondary, onSecondaryContainer = foreground,
    tertiary = info, onTertiary = Color.White, inverseSurface = foreground, inverseOnSurface = background, scrim = Color.Black,
) else lightColorScheme(
    primary = primary, onPrimary = primaryForeground, secondary = secondary, onSecondary = secondaryForeground,
    background = background, onBackground = foreground, surface = card, onSurface = cardForeground,
    surfaceVariant = muted, onSurfaceVariant = mutedForeground, outline = border, outlineVariant = border,
    error = destructive, onError = destructiveForeground, surfaceContainer = card, surfaceContainerHigh = secondary,
    surfaceContainerLow = background, surfaceContainerHighest = secondary, surfaceContainerLowest = background,
    primaryContainer = secondary, onPrimaryContainer = foreground, secondaryContainer = secondary, onSecondaryContainer = foreground,
    tertiary = info, onTertiary = Color.White, inverseSurface = foreground, inverseOnSurface = background, scrim = Color.Black,
)

private val ShadTypography = Typography(
    headlineLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 28.sp, lineHeight = 34.sp, letterSpacing = (-0.5).sp),
    headlineMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 28.sp, letterSpacing = (-0.4).sp),
    headlineSmall = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 18.sp, lineHeight = 24.sp, letterSpacing = (-0.3).sp),
    titleLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 17.sp, lineHeight = 22.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 20.sp),
    titleSmall = TextStyle(fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp),
    bodyLarge = TextStyle(fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp),
    labelMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp),
    labelSmall = TextStyle(fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 14.sp, letterSpacing = 0.2.sp),
)

val MonoStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 17.sp)

@Composable
fun KRSDockersTheme(mode: ThemeMode = ThemeMode.SYSTEM, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val colors = if (dark) ShadDark else ShadLight
    CompositionLocalProvider(LocalShadColors provides colors) {
        MaterialTheme(colorScheme = colors.toMaterial(), typography = ShadTypography, content = content)
    }
}
