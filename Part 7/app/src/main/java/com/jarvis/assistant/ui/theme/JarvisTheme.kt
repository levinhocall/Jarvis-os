package com.jarvis.assistant.ui.theme

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
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.toColorInt

@Immutable
data class JarvisColors(
    val background: Color,
    val backgroundElevated: Color,
    val surface: Color,
    val surfaceCard: Color,
    val cardBorder: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textMuted: Color,
    val accent: Color,
    val accentSecondary: Color,
    val statusGreen: Color,
    val statusAmber: Color,
    val statusRed: Color,
    val statusPink: Color,
    val isDark: Boolean
)

object JarvisSpacing {
    val xxs: Dp = 4.dp
    val xs: Dp = 8.dp
    val sm: Dp = 12.dp
    val md: Dp = 16.dp
    val lg: Dp = 24.dp
    val xl: Dp = 32.dp
}

private val VoidDarkColors = JarvisColors(
    background = Color(0xFF05080F),
    backgroundElevated = Color(0xFF09101E),
    surface = Color(0xFF0D1628),
    surfaceCard = Color(0xFF111C33),
    cardBorder = Color(0xFF1E3254),
    textPrimary = Color(0xFFF4F7FC),
    textSecondary = Color(0xFFCBD5E1),
    textMuted = Color(0xFF8395B0),
    accent = Color(0xFF22D3EE),
    accentSecondary = Color(0xFF3B82F6),
    statusGreen = Color(0xFF10B981),
    statusAmber = Color(0xFFF59E0B),
    statusRed = Color(0xFFEF4444),
    statusPink = Color(0xFFEC4899),
    isDark = true
)

private val CrimsonDarkColors = VoidDarkColors.copy(
    background = Color(0xFF0B0306),
    backgroundElevated = Color(0xFF14070D),
    surface = Color(0xFF1C0A12),
    surfaceCard = Color(0xFF230D17),
    cardBorder = Color(0xFF4B1A2E),
    accent = Color(0xFFFF4D6D),
    accentSecondary = Color(0xFFF97316)
)

private val LightDayColors = JarvisColors(
    background = Color(0xFFF3F6FB),
    backgroundElevated = Color(0xFFFFFFFF),
    surface = Color(0xFFFFFFFF),
    surfaceCard = Color(0xFFEAF0F8),
    cardBorder = Color(0xFFCFD9E8),
    textPrimary = Color(0xFF0F172A),
    textSecondary = Color(0xFF334155),
    textMuted = Color(0xFF64748B),
    accent = Color(0xFF0284C7),
    accentSecondary = Color(0xFF2563EB),
    statusGreen = Color(0xFF059669),
    statusAmber = Color(0xFFD97706),
    statusRed = Color(0xFFDC2626),
    statusPink = Color(0xFFDB2777),
    isDark = false
)

private val SunsetWarmColors = LightDayColors.copy(
    background = Color(0xFFFFF4EE),
    surfaceCard = Color(0xFFFEE8DC),
    cardBorder = Color(0xFFF4CDBB),
    accent = Color(0xFFEA580C),
    accentSecondary = Color(0xFFDB2777)
)

val LocalJarvisColors = staticCompositionLocalOf { VoidDarkColors }

private val JarvisTypography = Typography(
    displaySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 28.sp,
        lineHeight = 34.sp,
        letterSpacing = (-0.4).sp
    ),
    headlineSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 26.sp
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 22.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.4.sp
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 14.sp,
        letterSpacing = 0.5.sp
    )
)

private val JarvisShapes = Shapes(
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp)
)

fun resolveJarvisColors(
    themeKey: String,
    customHex: String = "#35C8FF",
    systemDark: Boolean = true
): JarvisColors {
    val effective = if (themeKey == "system") {
        if (systemDark) "dark" else "light"
    } else {
        themeKey
    }
    return when (effective) {
        "dark", "midnight" -> VoidDarkColors
        "red", "crimson" -> CrimsonDarkColors
        "sunset" -> SunsetWarmColors
        "custom" -> {
            val parsed = runCatching { Color(customHex.toColorInt()) }.getOrNull()
            if (parsed == null) VoidDarkColors
            else if (parsed.luminance() > 0.55f) {
                LightDayColors.copy(background = parsed.copy(alpha = 1f), accent = VoidDarkColors.accent)
            } else {
                VoidDarkColors.copy(accent = parsed)
            }
        }
        "light" -> LightDayColors
        else -> VoidDarkColors
    }
}

@Composable
fun JarvisTheme(
    themeKey: String = "dark",
    customHex: String = "#35C8FF",
    systemDark: Boolean = true,
    content: @Composable () -> Unit
) {
    val tokens = resolveJarvisColors(themeKey = themeKey, customHex = customHex, systemDark = systemDark)
    val colorScheme = if (tokens.isDark) {
        darkColorScheme(
            primary = tokens.accent,
            secondary = tokens.accentSecondary,
            background = tokens.background,
            surface = tokens.surface,
            surfaceVariant = tokens.surfaceCard,
            onPrimary = Color(0xFF04101B),
            onBackground = tokens.textPrimary,
            onSurface = tokens.textPrimary,
            onSurfaceVariant = tokens.textSecondary,
            outline = tokens.cardBorder,
            error = tokens.statusRed
        )
    } else {
        lightColorScheme(
            primary = tokens.accent,
            secondary = tokens.accentSecondary,
            background = tokens.background,
            surface = tokens.surface,
            surfaceVariant = tokens.surfaceCard,
            onPrimary = Color.White,
            onBackground = tokens.textPrimary,
            onSurface = tokens.textPrimary,
            onSurfaceVariant = tokens.textSecondary,
            outline = tokens.cardBorder,
            error = tokens.statusRed
        )
    }
    CompositionLocalProvider(LocalJarvisColors provides tokens) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = JarvisTypography,
            shapes = JarvisShapes,
            content = content
        )
    }
}
