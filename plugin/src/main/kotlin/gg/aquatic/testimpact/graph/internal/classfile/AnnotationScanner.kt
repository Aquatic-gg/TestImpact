package gg.aquatic.testimpact.graph.internal.classfile

import gg.aquatic.testimpact.graph.internal.kotlin.RawKotlinMetadata

/**
 * Walks the annotation structures of a class file (JVMS 4.7.16 to 4.7.22) and adds every class they name
 * (annotation types, enum types, class values) to [classes]. Nesting deeper than [MAX_DEPTH] is rejected,
 * so hostile input cannot overflow the stack.
 *
 * The class-level `kotlin.Metadata` annotation and the `SourceDebugExtension` annotation copy of the Kotlin
 * source map are captured while scanning with `capture = true`.
 */
internal class AnnotationScanner(
    private val pool: ConstantPool,
    private val classes: MutableCollection<String>,
) {
    private val debugChunks = ArrayList<String>()

    /** The captured `kotlin.Metadata` annotation, if one was scanned. */
    var metadata: RawKotlinMetadata? = null
        private set

    /** The captured source map text of the `SourceDebugExtension` annotation copy, if one was scanned. */
    val debugExtension: String? get() = if (debugChunks.isEmpty()) null else debugChunks.joinToString("")

    /** Reads `num_annotations` and the annotations after it. */
    fun scanAnnotations(cursor: ByteCursor, capture: Boolean = false) {
        repeat(cursor.u2()) { readAnnotation(cursor, 0, capture) }
    }

    /** Reads `num_parameters` and the annotations of every parameter. */
    fun scanParameterAnnotations(cursor: ByteCursor) {
        repeat(cursor.u1()) { scanAnnotations(cursor) }
    }

    /** Reads `num_annotations` and the type annotations after it. */
    fun scanTypeAnnotations(cursor: ByteCursor) {
        repeat(cursor.u2()) { readTypeAnnotation(cursor) }
    }

    /** Reads one element value, the body of an AnnotationDefault attribute. */
    fun scanElementValue(cursor: ByteCursor) {
        readElementValue(cursor, 0, null)
    }

    private fun readAnnotation(cursor: ByteCursor, depth: Int, capture: Boolean) {
        if (depth > MAX_DEPTH) throw MalformedClassException("annotations nested deeper than $MAX_DEPTH", cursor.position)
        val descriptor = pool.utf8(cursor.u2())
        TypeNames.addFromDescriptor(descriptor, classes)
        val captured = capture && (descriptor == METADATA || descriptor == SOURCE_DEBUG_EXTENSION)
        val values = if (captured) HashMap<String, MutableList<String>>() else null
        repeat(cursor.u2()) {
            val name = pool.utf8(cursor.u2())
            readElementValue(cursor, depth, values?.getOrPut(name) { ArrayList() })
        }
        if (values == null) return
        if (descriptor == METADATA) {
            metadata = RawKotlinMetadata(
                kind = values["k"]?.firstOrNull()?.toIntOrNull() ?: 1,
                data1 = values["d1"].orEmpty(),
                data2 = values["d2"].orEmpty(),
                extraString = values["xs"]?.firstOrNull(),
            )
        } else {
            debugChunks.addAll(values["value"].orEmpty())
        }
    }

    private fun readElementValue(cursor: ByteCursor, depth: Int, out: MutableList<String>?) {
        if (depth > MAX_DEPTH) throw MalformedClassException("element values nested deeper than $MAX_DEPTH", cursor.position)
        val tagOffset = cursor.position
        when (val tag = cursor.u1().toChar()) {
            'B', 'C', 'D', 'F', 'J', 'S', 'Z' -> cursor.u2()
            'I' -> {
                val index = cursor.u2()
                out?.add(pool.intValue(index).toString())
            }
            's' -> {
                val index = cursor.u2()
                out?.add(pool.utf8(index))
            }
            'e' -> {
                TypeNames.addFromDescriptor(pool.utf8(cursor.u2()), classes)
                cursor.u2()
            }
            'c' -> TypeNames.addFromDescriptor(pool.utf8(cursor.u2()), classes)
            '@' -> readAnnotation(cursor, depth + 1, false)
            '[' -> repeat(cursor.u2()) { readElementValue(cursor, depth + 1, out) }
            else -> throw MalformedClassException("unknown element value tag '$tag'", tagOffset)
        }
    }

    private fun readTypeAnnotation(cursor: ByteCursor) {
        val targetOffset = cursor.position
        when (val target = cursor.u1()) {
            0x00, 0x01, 0x16 -> cursor.skip(1)
            0x10, 0x11, 0x12, 0x17, 0x42, 0x43, 0x44, 0x45, 0x46 -> cursor.skip(2)
            0x13, 0x14, 0x15 -> Unit
            0x40, 0x41 -> cursor.skip(cursor.u2() * 6)
            0x47, 0x48, 0x49, 0x4A, 0x4B -> cursor.skip(3)
            else -> throw MalformedClassException("unknown type annotation target 0x${target.toString(16)}", targetOffset)
        }
        cursor.skip(cursor.u1() * 2)
        readAnnotation(cursor, 0, false)
    }

    private companion object {
        const val MAX_DEPTH = 64
        const val METADATA = "Lkotlin/Metadata;"
        const val SOURCE_DEBUG_EXTENSION = "Lkotlin/jvm/internal/SourceDebugExtension;"
    }
}
