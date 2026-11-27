package gg.aquatic.testimpact.graph.internal.classfile

/**
 * Big-endian reader over a byte array. Every read is bounds-checked against [limit] and reports
 * absolute offsets, so a truncated or hostile class file fails with [MalformedClassException].
 */
internal class ByteCursor(
    private val bytes: ByteArray,
    position: Int = 0,
    val limit: Int = bytes.size,
) {
    var position: Int = position
        private set

    init {
        if (limit < 0 || limit > bytes.size || position < 0 || position > limit) {
            throw MalformedClassException("cursor outside of the data", position)
        }
    }

    val remaining: Int get() = limit - position

    fun u1(): Int {
        val value = u1At(position)
        position += 1
        return value
    }

    fun u2(): Int {
        val value = u2At(position)
        position += 2
        return value
    }

    fun u4(): Long {
        val value = u4At(position)
        position += 4
        return value
    }

    fun s1(): Int = u1().toByte().toInt()

    fun s2(): Int = u2().toShort().toInt()

    fun skip(count: Long) {
        if (count < 0 || count > remaining) {
            throw MalformedClassException("length $count exceeds the remaining $remaining bytes", position)
        }
        position += count.toInt()
    }

    fun skip(count: Int) = skip(count.toLong())

    fun seek(target: Int) {
        if (target < 0 || target > limit) throw MalformedClassException("seek outside of the data", target)
        position = target
    }

    fun u1At(offset: Int): Int {
        require(offset, 1)
        return bytes[offset].toInt() and 0xFF
    }

    fun u2At(offset: Int): Int {
        require(offset, 2)
        return (bytes[offset].toInt() and 0xFF shl 8) or (bytes[offset + 1].toInt() and 0xFF)
    }

    fun u4At(offset: Int): Long {
        require(offset, 4)
        return (bytes[offset].toLong() and 0xFF shl 24) or
            (bytes[offset + 1].toLong() and 0xFF shl 16) or
            (bytes[offset + 2].toLong() and 0xFF shl 8) or
            (bytes[offset + 3].toLong() and 0xFF)
    }

    fun modifiedUtf8At(offset: Int, length: Int): String {
        require(offset, length)
        return ModifiedUtf8.decode(bytes, offset, length)
    }

    fun startsWithAscii(offset: Int, length: Int, prefix: String): Boolean {
        require(offset, length)
        if (length < prefix.length) return false
        for (i in prefix.indices) {
            if (bytes[offset + i].toInt() != prefix[i].code) return false
        }
        return true
    }

    private fun require(offset: Int, count: Int) {
        if (offset < 0 || count < 0 || count > limit - offset) {
            throw MalformedClassException("read of $count bytes past the end of the data (limit $limit)", offset)
        }
    }
}
