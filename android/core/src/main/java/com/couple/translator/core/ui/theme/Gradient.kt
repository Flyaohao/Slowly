package com.couple.translator.core.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush

/**
 * 品牌渐变画笔（S-B）。
 *
 * 使用范围受控（2026-09-28 S5 拍板）：仅允许三处——
 * ① 主视觉卡（首页顶卡、关系页页头底色）② AppAccentButton ③ 空态插画底。
 * 粉橙渐变铺开即廉价，留白才是高级感；页面需要新场景先改本注释再使用。
 *
 * 两端取自 AppColors.gradientStart/gradientEnd，深浅色主题自动切换，
 * 且都压得住白字（对比度 ≥4.1:1）。
 */
val AppPrimaryGradient: Brush
    @Composable @ReadOnlyComposable
    get() = Brush.linearGradient(
        colors = listOf(AppGradientStart, AppGradientEnd),
        // 135° 对角线：左上亮、右下沉，比纯竖直更「活」
        start = Offset(0f, 0f),
        end = Offset(1f, 1f),
    )

/**
 * 软渐变：accentContainer → accentFaint 的极淡过渡。
 *
 * 用于「需要一点品牌氛围但不许抢正文」的小面积底：军师头像徽标、空态插画容器。
 * 深浅色主题自动切换；上面叠深色文字/图标（AppAccent/AppWarm），不要叠白。
 */
val AppSoftGradient: Brush
    @Composable @ReadOnlyComposable
    get() = Brush.linearGradient(
        colors = listOf(AppAccentLight, AppAccentFaint),
        start = Offset(0f, 0f),
        end = Offset(1f, 1f),
    )
