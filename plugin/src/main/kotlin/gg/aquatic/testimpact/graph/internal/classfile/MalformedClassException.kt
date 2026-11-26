package gg.aquatic.testimpact.graph.internal.classfile

/**
 * Thrown for any class-file input that does not follow the format the reader relies on.
 * It is the only failure the reader reports for hostile bytes, so callers can turn it into a problem entry.
 */
internal class MalformedClassException(
    val reason: String,
    val offset: Int = NO_OFFSET,
) : RuntimeException(if (offset == NO_OFFSET) reason else "$reason at offset $offset") {

    internal companion object {
        const val NO_OFFSET: Int = -1
    }
}
