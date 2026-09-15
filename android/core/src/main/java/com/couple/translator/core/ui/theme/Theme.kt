package com.couple.translator.core.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

// ColorScheme 全部由 AppColors 派生，避免「MaterialTheme.colorScheme.* 已是深色、
// 而页面用的语义色还是浅色」这类两套色板打架的问题。
private val LightColorScheme = lightColorScheme(
    primary = LightAppColors.accent,
    onPrimary = LightAppColors.onAccent,
    primaryContainer = LightAppColors.accentContainer,
    onPrimaryContainer = LightAppColors.accent,
    secondary = LightAppColors.textSecondary,
    onSecondary = LightAppColors.onAccent,
    secondaryContainer = LightAppColors.accentContainer,
    onSecondaryContainer = LightAppColors.accent,
    tertiary = LightAppColors.accent,
    onTertiary = LightAppColors.onAccent,
    background = LightAppColors.background,
    onBackground = LightAppColors.textPrimary,
    surface = LightAppColors.surface,
    onSurface = LightAppColors.textPrimary,
    surfaceVariant = LightAppColors.border,
    onSurfaceVariant = LightAppColors.textSecondary,
    surfaceContainer = LightAppColors.surface,
    surfaceContainerLow = LightAppColors.surface,
    surfaceContainerHigh = LightAppColors.surface,
    surfaceContainerHighest = LightAppColors.surface,
    inverseSurface = LightAppColors.textPrimary,
    inverseOnSurface = LightAppColors.surface,
    error = LightAppColors.error,
    onError = LightAppColors.onAccent,
    errorContainer = LightAppColors.accentContainer,
    onErrorContainer = LightAppColors.error,
    outline = LightAppColors.border,
    outlineVariant = LightAppColors.border,
)

private val DarkColorScheme = darkColorScheme(
    primary = DarkAppColors.accent,
    onPrimary = DarkAppColors.onAccent,
    primaryContainer = DarkAppColors.accentContainer,
    onPrimaryContainer = DarkAppColors.textPrimary,
    secondary = DarkAppColors.textSecondary,
    onSecondary = DarkAppColors.background,
    secondaryContainer = DarkAppColors.accentContainer,
    onSecondaryContainer = DarkAppColors.textPrimary,
    tertiary = DarkAppColors.accent,
    onTertiary = DarkAppColors.onAccent,
    background = DarkAppColors.background,
    onBackground = DarkAppColors.textPrimary,
    surface = DarkAppColors.surface,
    onSurface = DarkAppColors.textPrimary,
    surfaceVariant = DarkAppColors.border,
    onSurfaceVariant = DarkAppColors.textSecondary,
    surfaceContainer = DarkAppColors.surface,
    surfaceContainerLow = DarkAppColors.surface,
    surfaceContainerHigh = DarkAppColors.surface,
    surfaceContainerHighest = DarkAppColors.border,
    inverseSurface = DarkAppColors.textPrimary,
    inverseOnSurface = DarkAppColors.background,
    error = DarkAppColors.error,
    onError = DarkAppColors.onAccent,
    errorContainer = DarkAppColors.accentContainer,
    onErrorContainer = DarkAppColors.textPrimary,
    outline = DarkAppColors.border,
    outlineVariant = DarkAppColors.border,
)

@Composable
fun CoupleTranslatorTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val appColors = if (darkTheme) DarkAppColors else LightAppColors
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            window.statusBarColor = appColors.background.toArgb()
            window.navigationBarColor = appColors.background.toArgb()
            WindowCompat.getInsetsController(window, view).apply {
                // 浅色模式用深色图标，深色模式用浅色图标
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    CompositionLocalProvider(LocalAppColors provides appColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            content = content,
        )
    }
}
