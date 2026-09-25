package com.couple.translator.core.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 行内 Markdown 扫描器（P-A §2.3）。
 *
 * 只打 [scanInlineMarkdown] 纯函数——若 JVM 单测里 AnnotatedString 载入异常，
 * parseInlineMarkdown 由真机验收兜底（任务单允许）。
 */
class MarkdownInlineTest {

    @Test
    fun `空文本返回空 span`() {
        assertEquals(emptyList<InlineSpan>(), scanInlineMarkdown(""))
    }

    @Test
    fun `无标记文本返回空列表 - 逐字节等价的前提`() {
        assertEquals(emptyList<InlineSpan>(), scanInlineMarkdown("普通一句话，没有标记"))
        assertEquals(emptyList<InlineSpan>(), scanInlineMarkdown("a # b *  c `"))
    }

    @Test
    fun `粗体命中区间正确`() {
        val spans = scanInlineMarkdown("前 **加粗** 后")
        assertEquals(1, spans.size)
        val s = spans[0]
        assertEquals(InlineSpan.Kind.BOLD, s.kind)
        assertEquals("加粗", "前 **加粗** 后".substring(s.start + 2, s.end - 2))
    }

    @Test
    fun `未闭合粗体不吞字符 - 按字面显示`() {
        // "**未完" → 扫描器不得产出 span，原文保持不变（防流式抖动的关键）
        val text = "这是 **未完"
        assertEquals(emptyList<InlineSpan>(), scanInlineMarkdown(text))
        // 原文仍在（调用方直接显示 text）
        assertTrue(text.contains("**"))
    }

    @Test
    fun `斜体与行内代码分别命中`() {
        val italics = scanInlineMarkdown("这是 *斜体* 文字")
        assertEquals(1, italics.size)
        assertEquals(InlineSpan.Kind.ITALIC, italics[0].kind)

        val code = scanInlineMarkdown("运行 `gradle build` 即可")
        assertEquals(1, code.size)
        assertEquals(InlineSpan.Kind.CODE, code[0].kind)
    }

    @Test
    fun `混排时区间不重叠`() {
        val text = "先 **粗** 再 `码` 后 *斜*"
        val spans = scanInlineMarkdown(text)
        assertEquals(3, spans.size)
        // 按 start 排序后相邻区间不交叉
        val sorted = spans.sortedBy { it.start }
        for (i in 0 until sorted.lastIndex) {
            assertTrue(
                "区间重叠：${sorted[i]} vs ${sorted[i + 1]}",
                sorted[i].end <= sorted[i + 1].start,
            )
        }
    }
}
