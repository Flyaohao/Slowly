package com.couple.translator.core.ui.components

import android.widget.TextView
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView
import com.couple.translator.core.ui.theme.AppTextPrimary
import io.noties.markwon.Markwon

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
 */
@Composable
fun AppMarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
    textSizeSp: Float = 15f,
    color: Color = AppTextPrimary,
    lineSpacingMultiplier: Float = 1.4f,
) {
    val textColor = color

    AndroidView(
        factory = { context ->
            TextView(context).apply {
                setTextColor(textColor.toArgb())
                textSize = textSizeSp
                setLineSpacing(0f, lineSpacingMultiplier)
            }
        },
        update = { view ->
            view.setTextColor(textColor.toArgb())
            view.textSize = textSizeSp
            view.setLineSpacing(0f, lineSpacingMultiplier)

            val markwon = view.tag as? Markwon
                ?: Markwon.create(view.context.applicationContext).also { view.tag = it }
            markwon.setMarkdown(view, markdown.trim())
        },
        modifier = modifier.fillMaxWidth(),
    )
}
