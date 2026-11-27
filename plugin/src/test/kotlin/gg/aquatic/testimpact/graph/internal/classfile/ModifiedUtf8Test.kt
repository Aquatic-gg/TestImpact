package gg.aquatic.testimpact.graph.internal.classfile

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ModifiedUtf8Test {

    private fun decode(vararg bytes: Int): String {
        val array = ByteArray(bytes.size) { bytes[it].toByte() }
        return ModifiedUtf8.decode(array, 0, array.size)
    }

    @Test
    fun `ascii and the one two and three byte forms decode`() {
        assertEquals("abc", decode('a'.code, 'b'.code, 'c'.code))
        assertEquals("é", decode(0xC3, 0xA9))
        assertEquals("€", decode(0xE2, 0x82, 0xAC))
    }

    @Test
    fun `the two byte form of zero decodes to a NUL char`() {
        assertEquals("a\u0000b", decode('a'.code, 0xC0, 0x80, 'b'.code))
    }

    @Test
    fun `a surrogate pair stored as two three byte forms is kept as two chars`() {
        val decoded = decode(0xED, 0xA0, 0xBD, 0xED, 0xB8, 0x80)

        assertEquals("😀", decoded)
        assertEquals(2, decoded.length)
    }

    @Test
    fun `output of DataOutputStream writeUTF decodes back for random strings`() {
        val random = Random(7)
        repeat(200) {
            val text = String(CharArray(random.nextInt(0, 40)) { random.nextInt(1, 0xFFFF).toChar() })
            val out = ByteArrayOutputStream()
            DataOutputStream(out).writeUTF(text)
            val encoded = out.toByteArray()

            assertEquals(text, ModifiedUtf8.decode(encoded, 2, encoded.size - 2))
        }
    }

    @Test
    fun `an offset and length decode a slice`() {
        val bytes = byteArrayOf('x'.code.toByte(), 'y'.code.toByte(), 'z'.code.toByte())

        assertEquals("y", ModifiedUtf8.decode(bytes, 1, 1))
    }

    @Test
    fun `invalid input throws MalformedClassException`() {
        val invalid = listOf(
            intArrayOf(0x00),
            intArrayOf(0xF0, 0x9F, 0x98, 0x80),
            intArrayOf(0x80),
            intArrayOf(0xC3),
            intArrayOf(0xC3, 0x41),
            intArrayOf(0xE2, 0x82),
            intArrayOf(0xE2, 0x82, 0x41),
            intArrayOf(0xC1, 0x80),
            intArrayOf(0xC0, 0x81),
            intArrayOf(0xE0, 0x80, 0x80),
            intArrayOf(0xFF),
        )
        for (bytes in invalid) {
            assertFailsWith<MalformedClassException>(bytes.joinToString()) { decode(*bytes) }
        }
    }

    @Test
    fun `a range outside of the array throws MalformedClassException`() {
        assertFailsWith<MalformedClassException> { ModifiedUtf8.decode(ByteArray(2), 1, 5) }
        assertFailsWith<MalformedClassException> { ModifiedUtf8.decode(ByteArray(2), -1, 1) }
    }
}
