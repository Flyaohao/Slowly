package com.couple.translator.core.ui.components

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight

/**
 * 行内 Markdown 解析结果（纯数据，不依赖 compose，便于单测）。
 *
 * P-A §2.3：流式期间不能上 Markwon（AndroidView 每次重组 setMarkdown 会掉帧，
 * 未闭合的 `**` 还会让文字忽粗忽细）。只处理 `**粗体**` / `*斜体*` / `` `code` ``，
 * **未闭合标记按字面显示、绝不吞字符**——这是防抖动的关键。
 */
internal data class InlineSpan(val start: Int, val end: Int, val kind: Kind) {
    enum class Kind { BOLD, ITALIC, CODE }
}

/** 纯函数：扫描行内标记，返回区间列表（不修改原文）。 */
internal fun scanInlineMarkdown(text: String): List<InlineSpan> {
    if (text.isEmpty()) return emptyList()
    val spans = mutableListOf<InlineSpan>()
    val n = text.length
    var i = 0
    while (i < n) {
        when {
            // **bold** —— 成对且中间非空
            text[i] == '*' && i + 1 < n && text[i + 1] == '*' -> {
                val close = text.indexOf("**", i + 2)
                if (close > i + 2) {
                    spans.add(InlineSpan(i, close + 2, InlineSpan.Kind.BOLD))
                    i = close + 2
                } else {
                    i += 2 // 未闭合：跳过两个 *，按字面保留
                }
            }
            // *italic* —— 单星，非 bold 的一部分
            text[i] == '*' -> {
                val close = text.indexOf('*', i + 1)
                if (close > i + 1 && (close + 1 >= n || text[close + 1] != '*')) {
                    spans.add(InlineSpan(i, close + 1, InlineSpan.Kind.ITALIC))
                    i = close + 1
                } else {
                    i += 1
                }
            }
            // `code`
            text[i] == '`' -> {
                val close = text.indexOf('`', i + 1)
                if (close > i + 1) {
                    spans.add(InlineSpan(i, close + 1, InlineSpan.Kind.CODE))
                    i = close + 1
                } else {
                    i += 1
                }
            }
            else -> i += 1
        }
    }
    return spans
}

/**
 * 供 Compose 使用：把扫描结果映射成 [AnnotatedString]，**并消费掉标记符**。
 *
 * P-A §0.2：此前基于原文建 Builder、只上样式，`**加粗**` 的星号会原样显示，
 * 而终稿走 Markwon 渲染是不带星号的——流式→终稿切换时星号闪没。
 * 现在按段重建：区间外原文（含未闭合标记）逐字保留，区间内剥掉两侧
 * `**` / `*` / `` ` `` 只给内容上样式。[scanInlineMarkdown] 的区间语义不变
 * （含标记），既有单测的 `substring(s.start + 2, s.end - 2)` 继续成立；
 * 无标记文本直接返回原文，逐字节等价。
 *
 * 调用方若要光标 `▍`，自行拼接在返回值之后。
 */
fun parseInlineMarkdown(text: String): AnnotatedString {
    if (text.isEmpty()) return AnnotatedString("")
    val spans = scanInlineMarkdown(text)
    if (spans.isEmpty()) return AnnotatedString(text) // 无标记：逐字节等价

    val builder = AnnotatedString.Builder()
    var cursor = 0
    for (span in spans) {
        // 区间之前的部分（含未闭合标记）原样保留——绝不吞字符
        if (span.start > cursor) {
            builder.append(text.substring(cursor, span.start))
        }
        // 消费两侧标记符，只给中间内容上样式
        val marker = when (span.kind) {
            InlineSpan.Kind.BOLD -> 2 // "**"
            InlineSpan.Kind.ITALIC, InlineSpan.Kind.CODE -> 1 // "*" / "`"
        }
        val style = when (span.kind) {
            InlineSpan.Kind.BOLD -> SpanStyle(fontWeight = FontWeight.Bold)
            InlineSpan.Kind.ITALIC -> SpanStyle(fontStyle = FontStyle.Italic)
            InlineSpan.Kind.CODE -> SpanStyle(fontFamily = FontFamily.Monospace)
        }
        val contentStart = span.start + marker
        val contentEnd = span.end - marker
        val from = builder.length
        builder.append(text.substring(contentStart, contentEnd))
        builder.addStyle(style, from, builder.length)
        cursor = span.end
    }
    if (cursor < text.length) {
        builder.append(text.substring(cursor))
    }
    return builder.toAnnotatedString()
}
