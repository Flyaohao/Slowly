package com.couple.translator.feature.couple.anniversary

import com.couple.translator.feature.couple.data.model.AnniversaryDto
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 纪念日文案回归（整改契约 §8.8 的日期语义）。
 *
 * 契约给的正例：「每年 11 月 20 日 / 下次 2026-11-20」，被点名的反例是
 * 「还有 55 天 · 2025-11-20」——两个数字互相打架，用户根本不知道该信哪个。
 *
 * 这条测试把「说什么」钉死：**先讲语义（每年 / 一次性），再讲下一次是哪天**，
 * 而且下一次的日期一律用服务端算好的 `nextOccurrenceDate`，
 * 客户端任何时候都不许自己把日期往前推一年。
 *
 * 数字（`days_until`）的算法在服务端，由 backend/tests/test_anniversary_dates.py
 * 覆盖；这里只管渲染，两边合起来才是完整的 §8.9-5。
 */
class AnniversaryDateCopyTest {

    private fun item(
        date: String = "2024-11-20",
        repeat: Boolean = true,
        next: String? = "2026-11-20",
        days: Int? = 55,
    ) = AnniversaryDto.AnniversaryResponse(
        id = 1,
        relationId = 1,
        title = "在一起纪念日",
        anniversaryDate = date,
        repeatAnnually = repeat,
        nextOccurrenceDate = next,
        daysUntil = days,
    )

    @Test
    fun `每年重复的日子先说每年几月几日再说下次是哪天`() {
        assertEquals("每年 11 月 20 日 · 下次 2026-11-20", anniversaryDateText(item()))
    }

    @Test
    fun `日期里的前导零不会漏到文案里`() {
        // 「每年 01 月 05 日」读起来像系统日志，不像给人看的话
        assertEquals(
            "每年 1 月 5 日 · 下次 2027-01-05",
            anniversaryDateText(item(date = "2024-01-05", next = "2027-01-05")),
        )
    }

    @Test
    fun `一次性且还没到只说这一次的日期`() {
        assertEquals(
            "2027-05-01（一次性）",
            anniversaryDateText(item(date = "2027-05-01", repeat = false, next = "2027-05-01", days = 217)),
        )
    }

    @Test
    fun `一次性且已经过去时明说已过去而不是编一个下一次`() {
        // 这正是契约禁止的年份冲突的来源：服务端不给下一次，客户端也不许自己造
        assertEquals(
            "2025-11-20（一次性，已过去）",
            anniversaryDateText(item(date = "2025-11-20", repeat = false, next = null, days = null)),
        )
    }

    @Test
    fun `服务端没给下次日期时至少不丢掉每年重复这个事实`() {
        assertEquals(
            "每年 11 月 20 日",
            anniversaryDateText(item(repeat = true, next = null, days = null)),
        )
    }

    @Test
    fun `日期字段异常时原样展示而不是崩掉整行`() {
        // 生产数据里出现过非 ISO 的脏值；列表不能因为一条脏数据整页渲染不出来
        assertEquals(
            "每年 记不清了 · 下次 2026-11-20",
            anniversaryDateText(item(date = "记不清了", next = "2026-11-20")),
        )
    }
}
