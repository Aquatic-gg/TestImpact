package gg.aquatic.testimpact.graph.internal.classfile

/** Decoder for the JVM modified UTF-8 of CONSTANT_Utf8 entries (JVMS 4.4.7). */
internal object ModifiedUtf8 {

    fun decode(bytes: ByteArray, offset: Int, length: Int): String {
        if (offset < 0 || length < 0 || length > bytes.size - offset) {
            throw MalformedClassException("utf8 range outside of the data", offset)
        }
        val end = offset + length
        val chars = CharArray(length)
        var count = 0
        var i = offset
        while (i < end) {
            val b = bytes[i].toInt() and 0xFF
            when {
                b == 0 -> throw MalformedClassException("zero byte in modified utf8", i)
                b < 0x80 -> {
                    chars[count++] = b.toChar()
                    i += 1
                }
                b and 0xE0 == 0xC0 -> {
                    if (i + 1 >= end) throw MalformedClassException("truncated 2-byte utf8 sequence", i)
                    val value = ((b and 0x1F) shl 6) or continuation(bytes, i + 1)
                    val overlong = if (value == 0) b != 0xC0 else value < 0x80
                    if (overlong) throw MalformedClassException("overlong 2-byte utf8 sequence", i)
                    chars[count++] = value.toChar()
                    i += 2
                }
                b and 0xF0 == 0xE0 -> {
                    if (i + 2 >= end) throw MalformedClassException("truncated 3-byte utf8 sequence", i)
                    val value = ((b and 0x0F) shl 12) or (continuation(bytes, i + 1) shl 6) or continuation(bytes, i + 2)
                    if (value < 0x800) throw MalformedClassException("overlong 3-byte utf8 sequence", i)
                    chars[count++] = value.toChar()
                    i += 3
                }
                else -> throw MalformedClassException("invalid utf8 lead byte 0x${b.toString(16)}", i)
            }
        }
        return String(chars, 0, count)
    }

    private fun continuation(bytes: ByteArray, index: Int): Int {
        val b = bytes[index].toInt() and 0xFF
        if (b and 0xC0 != 0x80) throw MalformedClassException("invalid utf8 continuation byte", index)
        return b and 0x3F
    }
}
