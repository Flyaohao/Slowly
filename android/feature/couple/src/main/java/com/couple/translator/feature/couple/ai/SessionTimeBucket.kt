package com.couple.translator.feature.couple.ai

import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

/**
 * P-A §3.2 会话列表时间分桶（纯函数，便于单测）。
 *
 * 分组依据是「最后活跃时间」（`lastMessageAt ?: createdAt`，🟢J）：
 * 昨天创建今天聊 → 归「今天」。
 */
enum class SessionBucket(val label: String) {
    TODAY("今天"),
    YESTERDAY("昨天"),
    WEEK("7 天内"),
    EARLIER("更早"),
}

/**
 * ISO-8601 → [LocalDateTime]，两级兜底照项目既有写法
 * （`LetterListScreen` / `QuestionnaireHistoryScreen`）：
 * `OffsetDateTime` → `LocalDateTime` → 失败返回 null。
 */
fun parseIsoLocal(iso: String?): LocalDateTime? {
    if (iso.isNullOrBlank()) return null
    return try {
        OffsetDateTime.parse(iso).toLocalDateTime()
    } catch (_: Exception) {
        try {
            LocalDateTime.parse(iso)
        } catch (_: Exception) {
            null
        }
    }
}

/**
 * 时间 → 分桶。`now` 由调用方传入（便于测试）；解析失败/入参为 null → [SessionBucket.EARLIER]，不抛。
 */
fun sessionBucketOf(iso: String?, now: LocalDateTime): SessionBucket {
    val dt = parseIsoLocal(iso) ?: return SessionBucket.EARLIER
    val date = dt.toLocalDate()
    val today = now.toLocalDate()
    return when (date) {
        today -> SessionBucket.TODAY
        today.minusDays(1) -> SessionBucket.YESTERDAY
        // 「7 天内」= 严格晚于 today-7（今天+前 6 天共 7 个自然日）；第 7 天前属「更早」
        else -> if (date.isAfter(today.minusDays(7))) SessionBucket.WEEK else SessionBucket.EARLIER
    }
}

/** 组内时间显示：今天/昨天用 HH:mm（更近），更早用 MM-dd。 */
fun bucketTimeLabel(iso: String?, bucket: SessionBucket): String {
    val dt = parseIsoLocal(iso) ?: return ""
    return when (bucket) {
        SessionBucket.TODAY, SessionBucket.YESTERDAY ->
            dt.format(DateTimeFormatter.ofPattern("HH:mm"))
        else -> dt.format(DateTimeFormatter.ofPattern("MM-dd"))
    }
}
