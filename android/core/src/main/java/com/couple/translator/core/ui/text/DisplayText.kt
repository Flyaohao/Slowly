package com.couple.translator.core.ui.text

/**
 * 信件标题的展示兜底。
 *
 * ## 为什么需要这个函数（不是简单写个 ?:）
 *
 * 后端 `app/services/letter_service.py` 历史上把空标题替换成占位文案
 * 「无标题」落库，导致前端 `title?.ifBlank { "无标题" }` 这类兜底**全部失效**
 * —— 字段根本不空，值就是那三个字。已经改成落库空串（列定义
 * `nullable=False`，见 `app/models/letter.py`），"叫什么"的决定权回到前端。
 *
 * 但前端两种写法能力不同：
 *
 * | 写法 | 挡 null | 挡空串 |
 * |---|---|---|
 * | `title ?: "无标题"`（elvis） | ✅ | ❌ |
 * | `title?.ifBlank { "…" } ?: "…"` | ✅ | ✅ |
 *
 * 改后端之前 elvis 写法靠「后端保证不空」而侥幸能工作；后端一改，6 处
 * elvis 会从「显示占位文案」变成「**显示空白**」—— 比占位文案更糟。
 * 所以别再逐处手写 Elvis，统一走这里。
 *
 * ## 用法
 *
 * ```kotlin
 * text = letter.title.displayTitle()          // 信件
 * val title = letter.title.displayTitle()
 * ```
 *
 * @param fallback 兜底文案，默认「一封信」。不同场景可传入更贴合的文案。
 */
fun String?.displayTitle(fallback: String = "一封信"): String {
    val trimmed = this?.trim()
    return if (trimmed.isNullOrEmpty()) fallback else trimmed
}