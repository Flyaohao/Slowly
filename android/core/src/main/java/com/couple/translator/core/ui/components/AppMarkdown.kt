package com.couple.translator.core.ui.components

import android.os.Build
import android.widget.TextView
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView
import com.couple.translator.core.ui.theme.AppTextPrimary
import io.noties.markwon.Markwon
import kotlin.math.roundToInt

/**
 * Markdown 文本渲染（Markwon）。
 *
 * 用在「AI 原始回复需要按 Markdown 呈现」的地方：模型输出本来就带 `**加粗**`、
 * `- 列表`、`## 小标题`，用普通 Text 渲染会把标记符号原样暴露给用户。
 *
 * 两个实现细节：
 *
 * - **Markwon 实例缓存在 `View.tag` 上**：`Markwon.create()` 每次都会重建一整套
 *   plugin 链，而 Compose 的重组可能让 `update` 被反复调用。缓存后只在首次解析。
 *   用 `applicationContext` 创建，避免长生命周期对象持有 Activity。
 * - **颜色从外面读进来再闭包捕获**：`App*` 是 `@Composable` getter，不能出现在
 *   `factory`/`update` 这类非 Composable lambda 里，所以先在 Composable 作用域
 *   取值再传进去——这也是全项目的统一写法。
 *
 * P-C1 §0.4：行高从「乘在字体自然行高上」改为**与流式同口径的绝对行高**
 * （`textSizeSp × lineHeightRatio`，等价于 AiStreamingText 的 `lineHeight`）。
 * 旧实现 `setLineSpacing(0f, 1.4f)` 的 1.4 是乘在 Roboto 自然行高（≈1.17×字号）
 * 上的——16sp 实测终稿行距 112px，流式 `lineHeight=22.4sp` 只有 86px，
 * 流式→终稿切换时整段行距跳 26px。见 P-C1 §0.4 像素实测。
 */
@Composable
fun AppMarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
    textSizeSp: Float = 15f,
    color: Color = AppTextPrimary,
    lineHeightRatio: Float = 1.4f,
) {
    val textColor = color

    AndroidView(
        factory = { context ->
            TextView(context).apply {
                setTextColor(textColor.toArgb())
                applyAbsoluteLineHeight(textSizeSp, lineHeightRatio)
            }
        },
        update = { view ->
            view.setTextColor(textColor.toArgb())
            view.applyAbsoluteLineHeight(textSizeSp, lineHeightRatio)

            val markwon = view.tag as? Markwon
                ?: Markwon.create(view.context.applicationContext).also { view.tag = it }
            markwon.setMarkdown(view, markdown.trim())
        },
        modifier = modifier.fillMaxWidth(),
    )
}

/**
 * 把行距钉成 `sizeSp × ratio` sp 的**绝对值**（基线到基线），与流式
 * [AiStreamingText] 的 `lineHeight = (sp * 1.4f).sp` 同口径。
 *
 * - API 28+：[TextView.setLineHeight] 直接吃像素行高（先归一 multiplier，
 *   避免两套间距叠乘）。
 * - API 26/27（minSdk）：`setLineSpacing(extra, 1f)`，extra 反算成
 *   `desired − fontSpacing`，落点同样是 desired。
 */
private fun TextView.applyAbsoluteLineHeight(sizeSp: Float, ratio: Float) {
    val scaledDensity = resources.displayMetrics.scaledDensity
    val desiredPx = sizeSp * ratio * scaledDensity
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        setLineSpacing(0f, 1f)
        setLineHeight(desiredPx.roundToInt())
    } else {
        val fontMetrics = paint.fontMetrics
        val naturalPx = fontMetrics.descent - fontMetrics.ascent
        setLineSpacing(desiredPx - naturalPx, 1f)
    }
}
