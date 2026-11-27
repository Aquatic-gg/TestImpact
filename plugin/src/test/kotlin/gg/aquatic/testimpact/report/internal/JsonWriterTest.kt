package gg.aquatic.testimpact.report.internal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class JsonWriterTest {

    private fun quoted(text: String) = JsonWriter.write(text).trimEnd('\n')

    @Test
    fun `escapes quote backslash and line feed`() {
        assertEquals("\"a\\\"b\\\\c\\nd\"", quoted("a\"b\\c\nd"))
    }

    @Test
    fun `escapes control characters`() {
        assertEquals("\"\\u0000\"", quoted("\u0000"))
        assertEquals("\"\\u001f\"", quoted("\u001F"))
        assertEquals("\"\\b\\f\\r\\t\"", quoted("\b\u000C\r\t"))
    }

    @Test
    fun `escapes line and paragraph separators`() {
        assertEquals("\"\\u2028\\u2029\"", quoted("\u2028\u2029"))
    }

    @Test
    fun `escapes a lone surrogate and keeps a valid pair`() {
        assertEquals("\"\\ud800\"", quoted("\uD800"))
        assertEquals("\"\\udc00x\"", quoted("\uDC00x"))
        assertEquals("\"\uD83D\uDE00\"", quoted("\uD83D\uDE00"))
        assertEquals("\"\\ud83dx\"", quoted("\uD83Dx"))
    }

    @Test
    fun `writes nested values with indent and trailing newline`() {
        val json = JsonWriter.write(linkedMapOf("a" to listOf(1, 2L), "b" to linkedMapOf("c" to null, "d" to true), "e" to emptyList<Any>()))
        val expected = """
            {
              "a": [
                1,
                2
              ],
              "b": {
                "c": null,
                "d": true
              },
              "e": []
            }
        """.trimIndent() + "\n"
        assertEquals(expected, json)
    }

    @Test
    fun `rejects unsupported values`() {
        assertFailsWith<IllegalArgumentException> { JsonWriter.write(1.5) }
        assertFailsWith<IllegalArgumentException> { JsonWriter.write(mapOf(1 to "x")) }
    }
}
