package com.couple.translator.core.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val LightColorScheme = lightColorScheme(
    primary = Accent,
    onPrimary = White,
    primaryContainer = AccentLight,
    onPrimaryContainer = AccentDark,
    secondary = TextSecondary,
    onSecondary = White,
    background = Background,
    onBackground = TextPrimary,
    surface = Surface,
    onSurface = TextPrimary,
    surfaceVariant = BorderLight,
    onSurfaceVariant = TextSecondary,
    error = ErrorRed,
    onError = White,
    outline = BorderDark,
)

private val DarkColorScheme = darkColorScheme(
    primary = Accent,
    onPrimary = White,
    primaryContainer = AccentDark,
    onPrimaryContainer = AccentLight,
    secondary = TextTertiary,
    onSecondary = Black,
    background = Black,
    onBackground = White,
    surface = Color(0xFF1C1C1E),
    onSurface = White,
    surfaceVariant = Color(0xFF2C2C2E),
    onSurfaceVariant = TextTertiary,
    error = ErrorRed,
    onError = White,
    outline = BorderDark,
)

@Composable
fun CoupleTranslatorTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.background.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content,
    )
}
