package gg.aquatic.testimpact.graph.internal.classfile

/**
 * The constant pool of one class file, indexed in a single pass and decoded lazily.
 * Accessors validate the index range and the expected tag, so a hostile index never reads outside the pool.
 */
internal class ConstantPool private constructor(
    private val data: ByteCursor,
    private val tags: ByteArray,
    private val offsets: IntArray,
) {
    private val utf8Cache = arrayOfNulls<String>(tags.size)
    private val classNameCache = arrayOfNulls<String>(tags.size)
    private val classNameResolved = BooleanArray(tags.size)

    /** Number of slots including the unused slot 0, equal to `constant_pool_count`. */
    val size: Int get() = tags.size

    fun tag(index: Int): Int {
        checkIndex(index)
        val tag = tags[index].toInt()
        if (tag == 0) throw MalformedClassException("constant pool index $index is not a usable entry")
        return tag
    }

    fun utf8(index: Int): String {
        expect(index, UTF8)
        utf8Cache[index]?.let { return it }
        val offset = offsets[index]
        val decoded = data.modifiedUtf8At(offset + 2, data.u2At(offset))
        utf8Cache[index] = decoded
        return decoded
    }

    /** Raw ASCII prefix test over a Utf8 entry without decoding it. */
    fun utf8StartsWith(index: Int, prefix: String): Boolean {
        expect(index, UTF8)
        val offset = offsets[index]
        return data.startsWithAscii(offset + 2, data.u2At(offset), prefix)
    }

    /** Internal name (`p/Outer$Inner`) of a CONSTANT_Class entry exactly as stored, arrays included. */
    fun classInternalName(index: Int): String {
        expect(index, CLASS)
        return utf8(data.u2At(offsets[index]))
    }

    /** Binary name (`p.Outer$Inner`) of a CONSTANT_Class entry; arrays give the element class, primitive arrays null. */
    fun className(index: Int): String? {
        expect(index, CLASS)
        if (classNameResolved[index]) return classNameCache[index]
        val resolved = TypeNames.fromClassConstant(utf8(data.u2At(offsets[index])))
        classNameCache[index] = resolved
        classNameResolved[index] = true
        return resolved
    }

    fun stringValue(index: Int): String {
        expect(index, STRING)
        return utf8(data.u2At(offsets[index]))
    }

    fun intValue(index: Int): Int {
        expect(index, INTEGER)
        return data.u4At(offsets[index]).toInt()
    }

    fun floatBits(index: Int): Int {
        expect(index, FLOAT)
        return data.u4At(offsets[index]).toInt()
    }

    fun longValue(index: Int): Long {
        expect(index, LONG)
        return (data.u4At(offsets[index]) shl 32) or data.u4At(offsets[index] + 4)
    }

    fun doubleBits(index: Int): Long {
        expect(index, DOUBLE)
        return (data.u4At(offsets[index]) shl 32) or data.u4At(offsets[index] + 4)
    }

    fun memberClassIndex(index: Int): Int {
        expectMember(index)
        return data.u2At(offsets[index])
    }

    fun memberNameAndTypeIndex(index: Int): Int {
        expectMember(index)
        return data.u2At(offsets[index] + 2)
    }

    fun nameAndTypeName(index: Int): String {
        expect(index, NAME_AND_TYPE)
        return utf8(data.u2At(offsets[index]))
    }

    fun nameAndTypeDescriptor(index: Int): String {
        expect(index, NAME_AND_TYPE)
        return utf8(data.u2At(offsets[index] + 2))
    }

    fun methodTypeDescriptor(index: Int): String {
        expect(index, METHOD_TYPE)
        return utf8(data.u2At(offsets[index]))
    }

    fun methodHandleKind(index: Int): Int {
        expect(index, METHOD_HANDLE)
        return data.u1At(offsets[index])
    }

    fun methodHandleReferenceIndex(index: Int): Int {
        expect(index, METHOD_HANDLE)
        return data.u2At(offsets[index] + 1)
    }

    /** Index of the NameAndType of a CONSTANT_Dynamic or CONSTANT_InvokeDynamic entry. */
    fun dynamicNameAndTypeIndex(index: Int): Int {
        val tag = tag(index)
        if (tag != DYNAMIC && tag != INVOKE_DYNAMIC) throw unexpected(index, tag, "Dynamic or InvokeDynamic")
        return data.u2At(offsets[index] + 2)
    }

    fun moduleOrPackageName(index: Int): String {
        val tag = tag(index)
        if (tag != MODULE && tag != PACKAGE) throw unexpected(index, tag, "Module or Package")
        return utf8(data.u2At(offsets[index]))
    }

    /** True when any Utf8 entry starts with the ASCII [prefix]. */
    fun anyUtf8StartsWith(prefix: String): Boolean {
        for (index in 1 until tags.size) {
            if (tags[index].toInt() == UTF8 && utf8StartsWith(index, prefix)) return true
        }
        return false
    }

    /** Every Utf8 entry starting with the ASCII [prefix], decoded, in pool order. */
    fun utf8StartingWith(prefix: String): List<String> {
        val found = ArrayList<String>()
        for (index in 1 until tags.size) {
            if (tags[index].toInt() == UTF8 && utf8StartsWith(index, prefix)) found.add(utf8(index))
        }
        return found
    }

    /** Calls [action] with the index of every entry carrying [tag], in pool order. */
    fun forEachOfTag(tag: Int, action: (Int) -> Unit) {
        for (index in 1 until tags.size) {
            if (tags[index].toInt() == tag) action(index)
        }
    }

    private fun checkIndex(index: Int) {
        if (index <= 0 || index >= tags.size) {
            throw MalformedClassException("constant pool index $index outside of 1..${tags.size - 1}")
        }
    }

    private fun expect(index: Int, expected: Int) {
        val tag = tag(index)
        if (tag != expected) throw unexpected(index, tag, "tag $expected")
    }

    private fun expectMember(index: Int) {
        val tag = tag(index)
        if (tag != FIELD_REF && tag != METHOD_REF && tag != INTERFACE_METHOD_REF) {
            throw unexpected(index, tag, "Fieldref, Methodref or InterfaceMethodref")
        }
    }

    private fun unexpected(index: Int, tag: Int, expected: String) =
        MalformedClassException("constant pool index $index has tag $tag, expected $expected")

    internal companion object {
        const val UTF8 = 1
        const val INTEGER = 3
        const val FLOAT = 4
        const val LONG = 5
        const val DOUBLE = 6
        const val CLASS = 7
        const val STRING = 8
        const val FIELD_REF = 9
        const val METHOD_REF = 10
        const val INTERFACE_METHOD_REF = 11
        const val NAME_AND_TYPE = 12
        const val METHOD_HANDLE = 15
        const val METHOD_TYPE = 16
        const val DYNAMIC = 17
        const val INVOKE_DYNAMIC = 18
        const val MODULE = 19
        const val PACKAGE = 20

        /** Reads `constant_pool_count` and its entries; the cursor ends right after the pool. */
        fun read(cursor: ByteCursor): ConstantPool {
            val count = cursor.u2()
            if (count < 1) throw MalformedClassException("constant_pool_count is 0", cursor.position - 2)
            val tags = ByteArray(count)
            val offsets = IntArray(count)
            var index = 1
            while (index < count) {
                val start = cursor.position
                val tag = cursor.u1()
                tags[index] = tag.toByte()
                offsets[index] = cursor.position
                when (tag) {
                    UTF8 -> cursor.skip(cursor.u2())
                    INTEGER, FLOAT, FIELD_REF, METHOD_REF, INTERFACE_METHOD_REF, NAME_AND_TYPE, DYNAMIC, INVOKE_DYNAMIC ->
                        cursor.skip(4)
                    LONG, DOUBLE -> {
                        if (index + 1 >= count) {
                            throw MalformedClassException("long or double entry without its second slot", start)
                        }
                        cursor.skip(8)
                        index++
                    }
                    CLASS, STRING, METHOD_TYPE, MODULE, PACKAGE -> cursor.skip(2)
                    METHOD_HANDLE -> {
                        val kind = cursor.u1()
                        if (kind !in 1..9) throw MalformedClassException("invalid method handle kind $kind", start)
                        cursor.skip(2)
                    }
                    else -> throw MalformedClassException("unknown constant pool tag $tag", start)
                }
                index++
            }
            return ConstantPool(cursor, tags, offsets)
        }
    }
}
