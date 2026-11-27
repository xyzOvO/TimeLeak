package com.chengliu.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Json5Test {

    @Test
    fun parsesCommentsUnquotedKeysSingleQuotesAndTrailingComma() {
        val text = """
            {
              // 行注释
              name: 'demo',
              /* 块注释 */
              "count": 3,
              nested: { ok: true, },
            }
        """.trimIndent()
        val v = Json5.parse(text) as Map<*, *>
        assertEquals("demo", v["name"])
        assertEquals(3.0, v["count"])
        val nested = v["nested"] as Map<*, *>
        assertEquals(true, nested["ok"])
    }

    @Test
    fun parsesMultipleTopLevelValues() {
        val v = Json5.parse("{a:1}\n{b:2}")
        assertTrue(v is List<*>)
        assertEquals(2, (v as List<*>).size)
    }

    @Test
    fun parsesArrayWithMixedLiterals() {
        val v = Json5.parse("[1, 'two', false, null]") as List<*>
        assertEquals(1.0, v[0])
        assertEquals("two", v[1])
        assertEquals(false, v[2])
        assertNull(v[3])
    }

    @Test
    fun blankInputReturnsNull() {
        assertNull(Json5.parse(""))
        assertNull(Json5.parse("   \n\t "))
    }

    @Test
    fun handlesUnicodeEscape() {
        val v = Json5.parse("""{ "s": "\u4e2d\u6587" }""") as Map<*, *>
        assertEquals("中文", v["s"])
    }

    /**
     * 畸形输入必须「立刻失败」，不能原地打转。
     *
     * v1.3.9 的解析循环里，遇到多余的 `]`、被截断的下载、半个引号时，
     * 这一轮既不推进 i 也不退出循环 —— 整条解析线程会永久卡在 100% CPU 上。
     * 订阅是下载来的、自定义规则是手输的，两种都可能畸形，所以这条线必须守住。
     */
    private fun assertTerminates(src: String) {
        var finished = false
        var failure: Throwable? = null
        val t = Thread {
            try {
                Json5.parse(src)
            } catch (e: Throwable) {
                failure = e
            }
            finished = true
        }
        t.isDaemon = true
        t.start()
        t.join(3000)
        assertTrue("解析必须终止：${src.take(60)}", finished)
        assertNull("解析失败按约定返回 null，不应把异常抛给调用方：$failure", failure)
    }

    @Test
    fun strayBracketDoesNotHang() {
        assertTerminates("""{"a": [1,2] ] }""")
        assertTerminates("""{"a": 1 ] """)
    }

    @Test
    fun truncatedSubscriptionDoesNotHang() {
        // 大文件下到一半断网，就是这个形状。
        assertTerminates("""{"apps":[{"id":"com.x","groups":[{"rules":[{"matches":"[text*=""")
        assertTerminates("""{"apps":[{"id":"com.x","groups":[{"rules":[{"matches":"[text=""")
    }

    @Test
    fun unterminatedStringDoesNotHang() {
        assertTerminates("""{"a":"abc""")
        assertTerminates("""{'a':'abc""")
    }

    @Test
    fun deepNestingGivesUpInsteadOfHanging() {
        val deep = "[".repeat(200) + "]".repeat(200)
        assertTerminates(deep)
        assertNull("超深嵌套属于畸形数据，直接放弃", Json5.parse(deep))
    }
}
