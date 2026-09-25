package com.couple.translator.feature.couple.ai

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime

/**
 * 会话列表时间分桶（P-A §3.2 纯函数）。
 */
class SessionTimeBucketTest {

    // 固定 now = 2026-09-25 15:00
    private val now = LocalDateTime.of(2026, 9, 25, 15, 0, 0)

    @Test
    fun `今天`() {
        assertEquals(SessionBucket.TODAY, sessionBucketOf("2026-09-25T09:30:00", now))
        assertEquals(SessionBucket.TODAY, sessionBucketOf("2026-09-25T23:59:59", now))
    }

    @Test
    fun `昨天`() {
        assertEquals(SessionBucket.YESTERDAY, sessionBucketOf("2026-09-24T10:00:00", now))
    }

    @Test
    fun `7天内`() {
        // 09-25 往前 7 天窗口 = 09-19 起（不含昨天/今天已单列）
        assertEquals(SessionBucket.WEEK, sessionBucketOf("2026-09-20T08:00:00", now))
        assertEquals(SessionBucket.WEEK, sessionBucketOf("2026-09-19T08:00:00", now))
    }

    @Test
    fun `更早`() {
        assertEquals(SessionBucket.EARLIER, sessionBucketOf("2026-09-18T08:00:00", now))
        assertEquals(SessionBucket.EARLIER, sessionBucketOf("2026-01-01T08:00:00", now))
    }

    @Test
    fun `null 与非法字符串落到更早且不抛`() {
        assertEquals(SessionBucket.EARLIER, sessionBucketOf(null, now))
        assertEquals(SessionBucket.EARLIER, sessionBucketOf("", now))
        assertEquals(SessionBucket.EARLIER, sessionBucketOf("not-a-date", now))
        assertEquals(SessionBucket.EARLIER, sessionBucketOf("2026-13-45T99:99:99", now))
    }

    @Test
    fun `OffsetDateTime 与 LocalDateTime 两种格式都能解析`() {
        assertEquals(SessionBucket.TODAY, sessionBucketOf("2026-09-25T09:30:00+08:00", now))
        assertEquals(SessionBucket.TODAY, sessionBucketOf("2026-09-25T09:30:00", now))
    }

    @Test
    fun `组内时间标签 今天昨天用时分更早用月日`() {
        assertEquals("09:30", bucketTimeLabel("2026-09-25T09:30:00", SessionBucket.TODAY))
        assertEquals("10:00", bucketTimeLabel("2026-09-24T10:00:00", SessionBucket.YESTERDAY))
        assertEquals("09-20", bucketTimeLabel("2026-09-20T08:00:00", SessionBucket.WEEK))
        assertEquals("01-01", bucketTimeLabel("2026-01-01T08:00:00", SessionBucket.EARLIER))
        assertEquals("", bucketTimeLabel(null, SessionBucket.EARLIER))
    }
}
