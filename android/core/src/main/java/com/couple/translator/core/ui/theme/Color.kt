package com.couple.translator.core.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * 应用语义色板。
 *
 * 页面一律通过本文件底部的 Composable getter（AppBackground / AppSurface / AppTextPrimary ...）取色，
 * 禁止直接引用颜色字面量或下面的 Light/Dark 常量 —— 否则深色模式下会出现「浅底浅字」「白卡片浮在黑底上」。
 *
 * 亮色模式取色与历史值完全一致，因此本次重构不影响亮色视觉。
 */
@Immutable
data class AppColors(
    /** 页面根背景 */
    val background: Color,
    /** 卡片 / 表单 / 底部导航等前景容器 */
    val surface: Color,
    /** 正文主色 */
    val textPrimary: Color,
    /** 次级文字、说明文案 */
    val textSecondary: Color,
    /** 三级文字、占位符、未选中态 */
    val textTertiary: Color,
    /** 分隔线、描边 */
    val border: Color,
    /** 进度条 / 滑块的未填充轨道：需比 border 略亮，否则深色下会与卡片底色糊成一片 */
    val track: Color,
    /** 品牌主色：按钮填充、选中态、强调文字 */
    val accent: Color,
    /** 品牌主色的浅色容器：图标底、标签底、卡片底 */
    val accentContainer: Color,
    /** 叠在 accent 之上的前景色 */
    val onAccent: Color,
    /** 错误 / 危险操作 */
    val error: Color,
    /** 成功 / 已完成 */
    val success: Color,
    /** 当前是否深色模式，供 Canvas 绘制等无法使用语义色的场景分支 */
    val isDark: Boolean,
)

internal val LightAppColors = AppColors(
    background = Color(0xFFFAF9F7),
    surface = Color(0xFFFFFFFF),
    textPrimary = Color(0xFF171717),
    textSecondary = Color(0xFF5C5C5C),
    textTertiary = Color(0xFFA1A1A1),
    border = Color(0xFFECEAE7),
    track = Color(0xFFECEAE7),
    accent = Color(0xFFBE185D),
    accentContainer = Color(0xFFFCE7F3),
    onAccent = Color(0xFFFFFFFF),
    error = Color(0xFFDC2626),
    success = Color(0xFF16A34A),
    isDark = false,
)

internal val DarkAppColors = AppColors(
    // 深色不用纯黑，避免 OLED 上的「黑洞感」与滚动拖影，同时保留原有的 iOS 观感
    background = Color(0xFF0D0D0F),
    surface = Color(0xFF1C1C1E),
    textPrimary = Color(0xFFF5F5F7),
    textSecondary = Color(0xFFAEAEB2),
    textTertiary = Color(0xFF8E8E93),
    border = Color(0xFF2C2C2E),
    // 比 surface(#1C1C1E) 亮一档，保证「已填充/未填充」的比例能被看清
    track = Color(0xFF3A3A3C),
    // accent 在「深底上的强调文字」与「白字按钮底」之间取平衡点：两边对比度均 ≈ 4.1:1
    accent = Color(0xFFE62E7E),
    accentContainer = Color(0xFF3D1526),
    onAccent = Color(0xFFFFFFFF),
    error = Color(0xFFEF4444),
    success = Color(0xFF22C55E),
    isDark = true,
)

/**
 * 由 CoupleTranslatorTheme 注入。默认值兜底用于 @Preview 等未包裹主题的场景。
 */
internal val LocalAppColors = staticCompositionLocalOf { LightAppColors }

// ============ 页面取色入口（深/浅色自动切换） ============

val AppBackground: Color
    @Composable @ReadOnlyComposable get() = LocalAppColors.current.background

val AppSurface: Color
    @Composable @ReadOnlyComposable get() = LocalAppColors.current.surface

val AppTextPrimary: Color
    @Composable @ReadOnlyComposable get() = LocalAppColors.current.textPrimary

val AppTextSecondary: Color
    @Composable @ReadOnlyComposable get() = LocalAppColors.current.textSecondary

val AppTextTertiary: Color
    @Composable @ReadOnlyComposable get() = LocalAppColors.current.textTertiary

val AppBorderLight: Color
    @Composable @ReadOnlyComposable get() = LocalAppColors.current.border

val AppTrack: Color
    @Composable @ReadOnlyComposable get() = LocalAppColors.current.track

val AppAccent: Color
    @Composable @ReadOnlyComposable get() = LocalAppColors.current.accent

val AppAccentLight: Color
    @Composable @ReadOnlyComposable get() = LocalAppColors.current.accentContainer

val AppOnAccent: Color
    @Composable @ReadOnlyComposable get() = LocalAppColors.current.onAccent

val AppErrorRed: Color
    @Composable @ReadOnlyComposable get() = LocalAppColors.current.error

val AppSuccessGreen: Color
    @Composable @ReadOnlyComposable get() = LocalAppColors.current.success

val AppIsDark: Boolean
    @Composable @ReadOnlyComposable get() = LocalAppColors.current.isDark
