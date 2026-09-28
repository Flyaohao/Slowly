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
    /** 比 accentContainer 更淡一档：大色块（主视觉卡片）底，避免与图标底糊在一起 */
    val accentFaint: Color,
    /** 静默区块底：提示条、次要信息条。比 surface 沉、比 background 亮 */
    val surfaceMuted: Color,
    /** 第二强调色（暖橙）：用于「对方」一侧的头像、双色标签 */
    val warm: Color,
    /** 暖色的浅色容器 */
    val warmContainer: Color,
    /** 叠在 accent 之上的前景色 */
    val onAccent: Color,
    /** 错误 / 危险操作 */
    val error: Color,
    /** 错误容器底：安全警示卡（滥用/自伤档）的浅底，深色为暗红底 */
    val errorContainer: Color,
    /** 警示色（琥珀橙）：情绪提醒等「注意但不危险」档 */
    val warning: Color,
    /** 警示容器底：情绪提醒/表达提醒档的浅底 */
    val warningContainer: Color,
    /** 成功 / 已完成 */
    val success: Color,
    /** 品牌渐变起点（较亮一端）：主按钮、选中滑块、空态插画底 */
    val gradientStart: Color,
    /** 品牌渐变终点（较深一端，保证白字对比度） */
    val gradientEnd: Color,
    /** 柔光阴影色：黑色低透明度，深色下需更高 alpha 才可感知 */
    val shadow: Color,
    /** 当前是否深色模式，供 Canvas 绘制等无法使用语义色的场景分支 */
    val isDark: Boolean,
)

internal val LightAppColors = AppColors(
    // 带 2% 粉调的暖白：肉眼几乎无感，但整屏氛围从「办公」变「柔软」
    background = Color(0xFFFBF7F5),
    surface = Color(0xFFFFFFFF),
    textPrimary = Color(0xFF171717),
    textSecondary = Color(0xFF5C5C5C),
    textTertiary = Color(0xFFA1A1A1),
    border = Color(0xFFECEAE7),
    track = Color(0xFFECEAE7),
    // 比 0xFFBE185D 亮一档：按钮/选中态更「鲜」而不刺眼
    accent = Color(0xFFC9195F),
    accentContainer = Color(0xFFFCE7F3),
    accentFaint = Color(0xFFFCF0F4),
    surfaceMuted = Color(0xFFF3F1EE),
    warm = Color(0xFFB45309),
    warmContainer = Color(0xFFFDEBD8),
    onAccent = Color(0xFFFFFFFF),
    error = Color(0xFFDC2626),
    errorContainer = Color(0xFFFFEBEE),
    warning = Color(0xFFE65100),
    warningContainer = Color(0xFFFFF3E0),
    success = Color(0xFF16A34A),
    // 亮玫瑰 → 品牌粉：可见的渐变，两端都压得住白字
    gradientStart = Color(0xFFDB2777),
    gradientEnd = Color(0xFFC9195F),
    shadow = Color(0x14000000),
    isDark = false,
)

internal val DarkAppColors = AppColors(
    // 深色不用纯黑，避免 OLED 上的「黑洞感」与滚动拖影，同时保留原有的 iOS 观感；
    // 加一丝暖（红调）与浅色模式的暖白呼应
    background = Color(0xFF121011),
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
    // 大色块底：比 accentContainer 再暗一档，深色下大面积极易显得刺眼
    accentFaint = Color(0xFF2A1420),
    surfaceMuted = Color(0xFF26262A),
    warm = Color(0xFFF0A868),
    warmContainer = Color(0xFF3A2410),
    onAccent = Color(0xFFFFFFFF),
    error = Color(0xFFEF4444),
    errorContainer = Color(0xFF3A1A1E),
    warning = Color(0xFFFFB74D),
    warningContainer = Color(0xFF3A2A12),
    success = Color(0xFF22C55E),
    // 深色下渐变整体提亮一档，避免暗底上发闷
    gradientStart = Color(0xFFE62E7E),
    gradientEnd = Color(0xFFC2185B),
    // 深色下黑阴影需要更高 alpha 才能被感知
    shadow = Color(0x33000000),
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

val AppAccentFaint: Color
    @Composable @ReadOnlyComposable get() = LocalAppColors.current.accentFaint

val AppSurfaceMuted: Color
    @Composable @ReadOnlyComposable get() = LocalAppColors.current.surfaceMuted

val AppWarm: Color
    @Composable @ReadOnlyComposable get() = LocalAppColors.current.warm

val AppWarmLight: Color
    @Composable @ReadOnlyComposable get() = LocalAppColors.current.warmContainer

val AppOnAccent: Color
    @Composable @ReadOnlyComposable get() = LocalAppColors.current.onAccent

val AppErrorRed: Color
    @Composable @ReadOnlyComposable get() = LocalAppColors.current.error

val AppErrorContainer: Color
    @Composable @ReadOnlyComposable get() = LocalAppColors.current.errorContainer

val AppWarning: Color
    @Composable @ReadOnlyComposable get() = LocalAppColors.current.warning

val AppWarningContainer: Color
    @Composable @ReadOnlyComposable get() = LocalAppColors.current.warningContainer

val AppSuccessGreen: Color
    @Composable @ReadOnlyComposable get() = LocalAppColors.current.success

val AppGradientStart: Color
    @Composable @ReadOnlyComposable get() = LocalAppColors.current.gradientStart

val AppGradientEnd: Color
    @Composable @ReadOnlyComposable get() = LocalAppColors.current.gradientEnd

val AppShadow: Color
    @Composable @ReadOnlyComposable get() = LocalAppColors.current.shadow

val AppIsDark: Boolean
    @Composable @ReadOnlyComposable get() = LocalAppColors.current.isDark
