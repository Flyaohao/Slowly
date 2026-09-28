package com.couple.translator.core.ui.theme

import androidx.compose.animation.core.CubicBezierEasing

/**
 * 动效时长/曲线令牌（全局 UI/UX 方案 G1）。
 *
 * 页面不要再散写 tween(300) / tween(240) —— 统一从这里取，保证
 * 「同一类转场在任何页面时长一致、手感一致」。存量替换只动常量不动结构。
 *
 * spring 统一规范：dampingRatio = 0.85（轻微回弹不夸张）、stiffness 用
 * Compose 预设（Spring.StiffnessMedium / MediumLow）。
 */
object AppMotion {
    /** 微交互：角标闪烁、指示器淡出、次级淡入 */
    const val fast = 180

    /** 标准过渡：内容色切换、卡片展开 */
    const val normal = 240

    /** 整屏转场：Tab 切换、压栈推入 */
    const val slow = 300

    /** 缓出曲线：入场用，起速快收速慢，比线性/Accelerate 更「跟手」 */
    val EaseOut = CubicBezierEasing(0.2f, 0f, 0f, 1f)

    /** 缓入缓出：位移类往返（如滑块回弹） */
    val EaseInOut = CubicBezierEasing(0.4f, 0f, 0.2f, 1f)

    /** 统一 spring 阻尼：1.0 是纯临界阻尼，0.85 留一丝回弹的「灵动感」 */
    const val SpringDamping = 0.85f
}
