package com.couple.translator.core.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.couple.translator.core.ui.theme.AppAccent
import com.couple.translator.core.ui.theme.AppTrack

/**
 * 水平分数条（0-100 满值口径，与画像维度 bands 一致）。
 *
 * 规则：凡是「分数 / 进度 / 置信度」这类数值，一律用它或 [AppRingProgress] 呈现，
 * 禁止用纯文字叙述数值——那是「AI 味」排版的最大来源。
 *
 * 进入页面时从 0 动画到目标值，给数据一个「被测量出来」的过程感。
 *
 * @param score 当前值（口径同 [max]，默认 0-100）
 * @param max   满值，默认 100
 */
@Composable
fun AppScoreBar(
    score: Float,
    modifier: Modifier = Modifier,
    max: Float = 100f,
    color: Color = AppAccent,
    trackColor: Color = AppTrack,
    height: Dp = 8.dp,
    animate: Boolean = true,
) {
    val target = if (max <= 0f) 0f else (score / max).coerceIn(0f, 1f)
    val progress = remember { Animatable(0f) }
    LaunchedEffect(target) {
        if (animate) {
            progress.snapTo(0f)
            progress.animateTo(target, animationSpec = tween(700))
        } else {
            progress.snapTo(target)
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(percent = 50))
            .background(trackColor),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(fraction = progress.value)
                .height(height)
                .clip(RoundedCornerShape(percent = 50))
                .background(color),
        )
    }
}

/**
 * 环形进度，中心留 slot 放数字或短标签。
 *
 * 与 [AppScoreBar] 的分工：单值聚焦（置信度、亲密度）用环，多维对比用条。
 * 尺寸统一用 [size] 控制，[modifier] 只留给外层布局（weight/padding 等）。
 */
@Composable
fun AppRingProgress(
    progress: Float,
    modifier: Modifier = Modifier,
    size: Dp = 72.dp,
    strokeWidth: Dp = 8.dp,
    color: Color = AppAccent,
    trackColor: Color = AppTrack,
    animate: Boolean = true,
    content: @Composable BoxScope.() -> Unit = {},
) {
    val target = progress.coerceIn(0f, 1f)
    val animated = remember { Animatable(0f) }
    LaunchedEffect(target) {
        if (animate) {
            animated.snapTo(0f)
            animated.animateTo(target, animationSpec = tween(700))
        } else {
            animated.snapTo(target)
        }
    }

    Box(modifier = modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.size(size)) {
            val stroke = strokeWidth.toPx()
            val inset = stroke / 2f
            val arcSize = Size(this.size.width - stroke, this.size.height - stroke)
            val topLeft = Offset(inset, inset)
            drawArc(
                color = trackColor,
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
            if (animated.value > 0f) {
                drawArc(
                    color = color,
                    startAngle = -90f,
                    sweepAngle = 360f * animated.value,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                )
            }
        }
        content()
    }
}
