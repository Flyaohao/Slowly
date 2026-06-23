package com.couple.translator.core.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// ============ 亮色模式颜色（基础常量） ============
val Background = Color(0xFFFAF9F7)
val Surface = Color(0xFFFFFFFF)
val TextPrimary = Color(0xFF171717)
val TextSecondary = Color(0xFF5C5C5C)
val TextTertiary = Color(0xFFA1A1A1)
val BorderLight = Color(0xFFECEAE7)
val BorderDark = Color(0xFFE4E2DF)
val Accent = Color(0xFFBE185D)
val AccentLight = Color(0xFFFCE7F3)
val AccentDark = Color(0xFF9D174D)
val ErrorRed = Color(0xFFDC2626)
val SuccessGreen = Color(0xFF16A34A)
val White = Color(0xFFFFFFFF)
val Black = Color(0xFF000000)

// ============ 深色模式颜色（私有常量） ============
private val DarkBackground = Color(0xFF000000)
private val DarkSurface = Color(0xFF1C1C1E)
private val DarkTextPrimary = Color(0xFFFFFFFF)
private val DarkTextSecondary = Color(0xFFB0B0B0)
private val DarkTextTertiary = Color(0xFF6C6C6C)
private val DarkBorderLight = Color(0xFF2C2C2E)
private val DarkAccentLight = Color(0xFF3D1526)

// ============ 深色模式感知的 Composable getter ============
// 页面统一使用这些 getter，而非直接引用上面的常量

val AppBackground: Color
    @Composable get() = if (isSystemInDarkTheme()) DarkBackground else Background

val AppSurface: Color
    @Composable get() = if (isSystemInDarkTheme()) DarkSurface else Surface

val AppTextPrimary: Color
    @Composable get() = if (isSystemInDarkTheme()) DarkTextPrimary else TextPrimary

val AppTextSecondary: Color
    @Composable get() = if (isSystemInDarkTheme()) DarkTextSecondary else TextSecondary

val AppTextTertiary: Color
    @Composable get() = if (isSystemInDarkTheme()) DarkTextTertiary else TextTertiary

val AppBorderLight: Color
    @Composable get() = if (isSystemInDarkTheme()) DarkBorderLight else BorderLight

val AppAccentLight: Color
    @Composable get() = if (isSystemInDarkTheme()) DarkAccentLight else AccentLight
