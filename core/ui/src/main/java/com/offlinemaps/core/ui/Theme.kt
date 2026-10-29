package com.offlinemaps.core.ui

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import com.offlinemaps.core.common.MapTheme

/**
 * Colour tokens chosen for outdoor legibility: high contrast on labels, muted map furniture so
 * controls stay readable over any map background, and a single accent for actions.
 */
private val AccentLight = Color(0xFF0B6E4F)
private val AccentDark = Color(0xFF3DDC97)

private val LightScheme = lightColorScheme(
    primary = AccentLight,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD7F0E4),
    onPrimaryContainer = Color(0xFF04281C),
    secondary = Color(0xFF35506B),
    onSecondary = Color.White,
    surface = Color(0xFFFFFDF8),
    onSurface = Color(0xFF1B1C19),
    surfaceVariant = Color(0xFFECEAE3),
    onSurfaceVariant = Color(0xFF45483F),
    outline = Color(0xFF767A6E),
    error = Color(0xFFB3261E),
    onError = Color.White,
    scrim = Color(0x99000000),
)

private val DarkScheme = darkColorScheme(
    primary = AccentDark,
    onPrimary = Color(0xFF00382A),
    primaryContainer = Color(0xFF00513D),
    onPrimaryContainer = Color(0xFFB6F2DA),
    secondary = Color(0xFFA8C8E8),
    onSecondary = Color(0xFF0C2036),
    surface = Color(0xFF14171C),
    onSurface = Color(0xFFE4E6E1),
    surfaceVariant = Color(0xFF242A31),
    onSurfaceVariant = Color(0xFFC2C7C0),
    outline = Color(0xFF8C918A),
    error = Color(0xFFF2B8B5),
    onError = Color(0xFF601410),
    scrim = Color(0xCC000000),
)

private val AppTypography = Typography(
    displaySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 32.sp,
        lineHeight = 38.sp,
        letterSpacing = (-0.5).sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        letterSpacing = (-0.2).sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 22.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.1.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 14.sp,
        letterSpacing = 0.4.sp,
    ),
)

@Composable
fun OfflineMapsTheme(theme: MapTheme = MapTheme.LIGHT, content: @Composable () -> Unit) {
    val scheme = if (theme == MapTheme.DARK) DarkScheme else LightScheme
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = theme == MapTheme.LIGHT
                isAppearanceLightNavigationBars = theme == MapTheme.LIGHT
            }
        }
    }
    MaterialTheme(colorScheme = scheme, typography = AppTypography, content = content)
}

/** Respects the system setting when the user has not chosen a theme explicitly. */
@Composable
fun defaultTheme(): MapTheme = if (isSystemInDarkTheme()) MapTheme.DARK else MapTheme.LIGHT
