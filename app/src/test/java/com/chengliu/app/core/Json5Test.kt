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
}
