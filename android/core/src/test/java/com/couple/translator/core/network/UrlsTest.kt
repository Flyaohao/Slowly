package com.couple.translator.core.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 相对资源路径 → 绝对 URL 的拼接测试。
 *
 * 背景：Coil 加载不了相对路径，头像/纪念馆图片此前就一直加载不出来。
 * 这类拼接一旦错了是**静默失败**——图片位置就是一片空白，不报错、不崩溃，
 * 所以必须有测试钉住输出形状。
 */
class UrlsTest {

    private val base = NetworkModule.DEFAULT_BASE_URL.trimEnd('/')

    @Test
    fun `null 与空白一律返回 null`() {
        assertNull(null.toAbsoluteUrl())
        assertNull("".toAbsoluteUrl())
        assertNull("   ".toAbsoluteUrl())
    }

    @Test
    fun `已是绝对地址时原样返回`() {
        assertEquals("http://cdn.example.com/a.png", "http://cdn.example.com/a.png".toAbsoluteUrl())
        assertEquals("https://cdn.example.com/a.png", "https://cdn.example.com/a.png".toAbsoluteUrl())
    }

    @Test
    fun `后端相对路径拼上 BASE_URL`() {
        assertEquals("$base/uploads/museum/x.jpg", "/uploads/museum/x.jpg".toAbsoluteUrl())
        assertEquals("$base/uploads/avatar/u1.png", "/uploads/avatar/u1.png".toAbsoluteUrl())
    }

    @Test
    fun `漏了前导斜杠也不会拼成死链`() {
        // 旧写法这里是 "http://host:8000uploads/a.png" —— 必然 404 且错得很安静
        assertEquals("$base/uploads/a.png", "uploads/a.png".toAbsoluteUrl())
    }

    @Test
    fun `两端空白被忽略`() {
        assertEquals("$base/uploads/a.png", "  /uploads/a.png  ".toAbsoluteUrl())
        assertEquals("http://cdn.example.com/a.png", "  http://cdn.example.com/a.png  ".toAbsoluteUrl())
    }
}
