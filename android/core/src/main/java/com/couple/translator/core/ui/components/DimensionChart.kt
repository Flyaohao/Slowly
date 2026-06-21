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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    accentColor: Color = Color(0xFF6C5CE7),
    gridColor: Color = Color(0xFFE0E0E0),
) {
    val animProgress = remember { Animatable(0f) }
    LaunchedEffect(dimensions) {
        animProgress.snapTo(0f)
        animProgress.animateTo(1f, animationSpec = tween(800))
    }

    val density = LocalDensity.current
    val textSizePx = with(density) { 11.sp.toPx() }
    val labelColor = android.graphics.Color.parseColor("#888888")

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

            // Draw labels
            for (i in 0 until count) {
                val (_, label, score) = dimensions[i]
                val angle = startAngle + angleStep * i
                val labelRadius = radius + 28f
                val x = centerX + labelRadius * cos(angle)
                val y = centerY + labelRadius * sin(angle)

                drawContext.canvas.nativeCanvas.apply {
                    val paint = android.graphics.Paint().apply {
                        color = labelColor
                        textSize = textSizePx
                        textAlign = when {
                            cos(angle) > 0.3f -> android.graphics.Paint.Align.LEFT
                            cos(angle) < -0.3f -> android.graphics.Paint.Align.RIGHT
                            else -> android.graphics.Paint.Align.CENTER
                        }
                        isAntiAlias = true
                    }
                    val textY = y + when {
                        sin(angle) > 0.5f -> textSizePx * 0.4f
                        sin(angle) < -0.5f -> -textSizePx * 0.3f
                        else -> textSizePx * 0.15f
                    }
                    drawText(label, x, textY, paint)

                    // Score value below label
                    val scorePaint = android.graphics.Paint().apply {
                        color = android.graphics.Color.parseColor("#6C5CE7")
                        textSize = textSizePx * 0.9f
                        textAlign = paint.textAlign
                        isAntiAlias = true
                        isFakeBoldText = true
                    }
                    drawText("${score.toInt()}", x, textY + textSizePx * 1.2f, scorePaint)
                }
            }
        }
    }
}
