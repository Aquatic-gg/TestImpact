package gg.aquatic.testimpact.graph.internal.kotlin

import gg.aquatic.testimpact.graph.ReachScope

/**
 * Shallow decoder of the protobuf in `kotlin.Metadata.d1` (UTF-8 mode): it walks only the top-level fields of
 * a Package (kinds 2 and 5) or Class (kind 1) message to find the typealiases and their visibility flag.
 * Field numbers and the flags layout are the only format knowledge, so newer Kotlin versions keep working;
 * anything unreadable yields [ReachScope.ALL], which over-selects instead of dropping a user.
 */
internal object MetadataProto {

    private const val PACKAGE_TYPE_ALIAS = 5
    private const val CLASS_TYPE_ALIAS = 11
    private const val ALIAS_FLAGS = 1
    private const val DEFAULT_FLAGS = 6L
    private const val VISIBILITY_PRIVATE = 1L
    private const val VISIBILITY_PRIVATE_TO_THIS = 4L

    private class Unreadable : RuntimeException("unreadable metadata", null, false, false)

    /**
     * Widest scope over the typealiases declared by metadata of [kind] with the d1 strings [data1];
     * null when it declares none or the kind cannot hold any.
     */
    fun typeAliasScope(kind: Int, data1: List<String>): ReachScope? {
        val aliasField = when (kind) {
            1 -> CLASS_TYPE_ALIAS
            2, 5 -> PACKAGE_TYPE_ALIAS
            3, 4 -> return null
            else -> return ReachScope.ALL
        }
        return try {
            scan(bytesOf(data1), aliasField)
        } catch (_: Unreadable) {
            ReachScope.ALL
        }
    }

    private fun scan(bytes: ByteArray, aliasField: Int): ReachScope? {
        val reader = Reader(bytes, 0, bytes.size)
        reader.skipDelimited()
        var widest: ReachScope? = null
        while (reader.hasNext()) {
            reader.next()
            if (reader.field == aliasField && reader.wire == WIRE_LENGTH) {
                val scope = aliasScope(Reader(bytes, reader.payloadStart, reader.payloadEnd))
                if (widest == null || scope > widest) widest = scope
            }
        }
        return widest
    }

    private fun aliasScope(alias: Reader): ReachScope {
        var flags = DEFAULT_FLAGS
        while (alias.hasNext()) {
            alias.next()
            if (alias.field == ALIAS_FLAGS && alias.wire == WIRE_VARINT) flags = alias.varint
        }
        val visibility = (flags shr 1) and 7
        return if (visibility == VISIBILITY_PRIVATE || visibility == VISIBILITY_PRIVATE_TO_THIS) ReachScope.FILE else ReachScope.ALL
    }

    private fun bytesOf(data1: List<String>): ByteArray {
        val first = data1.firstOrNull()
        if (first.isNullOrEmpty() || first[0] != '\u0000') throw Unreadable()
        val size = data1.sumOf { it.length } - 1
        val bytes = ByteArray(size)
        var next = 0
        for ((stringIndex, text) in data1.withIndex()) {
            for (charIndex in (if (stringIndex == 0) 1 else 0) until text.length) {
                val c = text[charIndex].code
                if (c > 0xFF) throw Unreadable()
                bytes[next++] = c.toByte()
            }
        }
        return bytes
    }

    private const val WIRE_VARINT = 0
    private const val WIRE_FIXED64 = 1
    private const val WIRE_LENGTH = 2
    private const val WIRE_FIXED32 = 5

    private class Reader(private val bytes: ByteArray, start: Int, private val end: Int) {
        private var position = start
        var field = 0
            private set
        var wire = 0
            private set
        var varint = 0L
            private set
        var payloadStart = 0
            private set
        var payloadEnd = 0
            private set

        fun hasNext(): Boolean = position < end

        fun skipDelimited() {
            val length = readVarint()
            position = advance(length)
        }

        fun next() {
            val tag = readVarint()
            wire = (tag and 7).toInt()
            val number = tag ushr 3
            if (number == 0L || number > Int.MAX_VALUE) throw Unreadable()
            field = number.toInt()
            when (wire) {
                WIRE_VARINT -> varint = readVarint()
                WIRE_FIXED64 -> position = advance(8)
                WIRE_FIXED32 -> position = advance(4)
                WIRE_LENGTH -> {
                    val length = readVarint()
                    payloadStart = position
                    position = advance(length)
                    payloadEnd = position
                }
                else -> throw Unreadable()
            }
        }

        private fun advance(count: Long): Int {
            if (count < 0 || count > end - position) throw Unreadable()
            return position + count.toInt()
        }

        private fun readVarint(): Long {
            var result = 0L
            var shift = 0
            while (shift < 70) {
                if (position >= end) throw Unreadable()
                val b = bytes[position++].toInt()
                result = result or ((b and 0x7F).toLong() shl shift)
                if (b and 0x80 == 0) return result
                shift += 7
            }
            throw Unreadable()
        }
    }
}
