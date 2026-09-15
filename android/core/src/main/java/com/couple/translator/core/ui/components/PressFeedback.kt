package com.couple.translator.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer

/**
 * 统一的按压反馈：按下缩小到 0.97，松手用轻微回弹还原。
 *
 * 替代默认水波纹 —— 卡片 / 列表项这种大面积元素上，涟漪会显得脏，
 * 轻微缩放更接近原生手感，而且不改变任何布局尺寸。
 *
 * ⚠️ 必须写在 modifier 链的**最前面**：
 * ```
 * Modifier.pressFeedback { ... }.clip(shape).background(color)
 * ```
 * 因为 graphicsLayer 只对链中它之后的节点生效，写在后面就只会缩放内容、不缩放卡片底。
 */
@Composable
fun Modifier.pressFeedback(
    enabled: Boolean = true,
    pressedScale: Float = 0.97f,
    onClick: () -> Unit,
): Modifier {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled) pressedScale else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium,
        ),
        label = "pressScale",
    )
    return this
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .clickable(
            interactionSource = interaction,
            indication = null,
            enabled = enabled,
            onClick = onClick,
        )
}
