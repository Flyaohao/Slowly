package com.couple.translator.core.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.couple.translator.core.ui.theme.AppAccent
import com.couple.translator.core.ui.theme.AppBorderLight
import com.couple.translator.core.ui.theme.AppTextTertiary
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.PI

/**
 * 雷达图：展示多维度分数
 * @param dimensions 维度列表 (key, label, score 0-100)
 */
@Composable
fun DimensionRadarChart(
    dimensions: List<Triple<String, String, Float>>,
    modifier: Modifier = Modifier,
    // 2026-09-28 去AI味 P-1：紫色是色板外的历史遗留，统一回品牌 accent。
    accentColor: Color = AppAccent,
    gridColor: Color = AppBorderLight,
) {
    val animProgress = remember { Animatable(0f) }
    LaunchedEffect(dimensions) {
        animProgress.snapTo(0f)
        animProgress.animateTo(1f, animationSpec = tween(800))
    }

    val density = LocalDensity.current
    val textSizePx = with(density) { 11.sp.toPx() }
    val labelColor = AppTextTertiary.toArgb()
    val accentArgb = accentColor.toArgb()

    Box(modifier = modifier.fillMaxWidth()) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(280.dp),
        ) {
            val centerX = size.width / 2
            val centerY = size.height / 2
            val radius = minOf(centerX, centerY) * 0.62f
            val count = dimensions.size
            if (count < 3) return@Canvas

            val angleStep = 2f * PI.toFloat() / count
            val startAngle = -PI.toFloat() / 2f // 从正上方开始

            // Draw concentric grid polygons (20%, 40%, 60%, 80%, 100%)
            for (level in 1..5) {
                val r = radius * level / 5f
                val gridPath = Path()
                for (i in 0 until count) {
                    val angle = startAngle + angleStep * i
                    val x = centerX + r * cos(angle)
                    val y = centerY + r * sin(angle)
                    if (i == 0) gridPath.moveTo(x, y) else gridPath.lineTo(x, y)
                }
                gridPath.close()
                drawPath(gridPath, gridColor, style = Stroke(width = 1f))
            }

            // Draw axis lines
            for (i in 0 until count) {
                val angle = startAngle + angleStep * i
                drawLine(
                    gridColor,
                    Offset(centerX, centerY),
                    Offset(centerX + radius * cos(angle), centerY + radius * sin(angle)),
                    strokeWidth = 1f,
                )
            }

            // Draw data polygon (animated)
            val dataPath = Path()
            val pointCount = (count * animProgress.value).toInt().coerceAtLeast(0)
            for (i in 0 until pointCount) {
                val (_, _, score) = dimensions[i]
                val r = radius * (score / 100f).coerceIn(0f, 1f)
                val angle = startAngle + angleStep * i
                val x = centerX + r * cos(angle)
                val y = centerY + r * sin(angle)
                if (i == 0) dataPath.moveTo(x, y) else dataPath.lineTo(x, y)
            }
            if (pointCount >= 3) {
                dataPath.close()
                // Fill
                drawPath(
                    dataPath,
                    accentColor.copy(alpha = 0.15f),
                    style = Fill,
                )
                // Stroke
                drawPath(
                    dataPath,
                    accentColor,
                    style = Stroke(width = 2.5f, cap = StrokeCap.Round),
                )
            }

            // Draw data points
            for (i in 0 until pointCount) {
                val (_, _, score) = dimensions[i]
                val r = radius * (score / 100f).coerceIn(0f, 1f)
                val angle = startAngle + angleStep * i
                val x = centerX + r * cos(angle)
                val y = centerY + r * sin(angle)
                drawCircle(accentColor, radius = 4f, center = Offset(x, y))
                drawCircle(Color.White, radius = 2f, center = Offset(x, y))
            }

            // Draw labels（带防重叠：与已放置标签的包围盒相交时，朝圆心方向逐行错开）
            // 为什么需要：维度数多时底部相邻两个标签的 cos 一正一负，会被分别判成
            // LEFT / RIGHT 对齐，两段文字相向延伸，在圆的正下方叠字。
            val labelRadius = radius + textSizePx * 2.2f
            val rowHeight = textSizePx * 2.8f
            val placedRects = mutableListOf<android.graphics.RectF>()
            for (i in 0 until count) {
                val (_, label, score) = dimensions[i]
                val angle = startAngle + angleStep * i
                val cosA = cos(angle)
                val align = when {
                    cosA > 0.3f -> android.graphics.Paint.Align.LEFT
                    cosA < -0.3f -> android.graphics.Paint.Align.RIGHT
                    else -> android.graphics.Paint.Align.CENTER
                }
                val x = centerX + labelRadius * cosA
                var anchorY = centerY + labelRadius * sin(angle)
                // 底部标签向上挪、顶部标签向下挪，错行后不会跑出画布
                val inward = if (sin(angle) >= 0f) -rowHeight else rowHeight

                var guard = 0
                while (guard < 4 &&
                    placedRects.any { it.overlaps(labelBounds(x, anchorY, label, align, textSizePx)) }
                ) {
                    anchorY += inward
                    guard++
                }
                placedRects.add(labelBounds(x, anchorY, label, align, textSizePx))

                drawContext.canvas.nativeCanvas.apply {
                    val paint = android.graphics.Paint().apply {
                        color = labelColor
                        textSize = textSizePx
                        textAlign = align
                        isAntiAlias = true
                    }
                    val textY = anchorY + when {
                        sin(angle) > 0.5f -> textSizePx * 0.4f
                        sin(angle) < -0.5f -> -textSizePx * 0.3f
                        else -> textSizePx * 0.15f
                    }
                    drawText(label, x, textY, paint)

                    // Score value below label
                    val scorePaint = android.graphics.Paint().apply {
                        color = accentArgb
                        textSize = textSizePx * 0.9f
                        textAlign = align
                        isAntiAlias = true
                        isFakeBoldText = true
                    }
                    drawText("${score.toInt()}", x, textY + textSizePx * 1.2f, scorePaint)
                }
            }
        }
    }
}

/**
 * 标签的包围盒，用于相邻标签的防重叠检测。
 * 中文按「1 个字符宽度 ≈ textSize」估算并留 5% 余量；纵向覆盖「标签 + 下方分数」两行。
 */
private fun labelBounds(
    x: Float,
    y: Float,
    label: String,
    align: android.graphics.Paint.Align,
    textSizePx: Float,
): android.graphics.RectF {
    val width = label.length * textSizePx * 1.05f
    val left = when (align) {
        android.graphics.Paint.Align.LEFT -> x
        android.graphics.Paint.Align.RIGHT -> x - width
        else -> x - width / 2f
    }
    return android.graphics.RectF(left, y - textSizePx, left + width, y + textSizePx * 1.7f)
}

/** RectF.intersect() 会就地修改自身，这里用纯判断版本。 */
private fun android.graphics.RectF.overlaps(other: android.graphics.RectF): Boolean =
    left < other.right && other.left < right && top < other.bottom && other.top < bottom
