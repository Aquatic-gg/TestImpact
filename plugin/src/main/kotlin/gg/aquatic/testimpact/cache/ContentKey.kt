package gg.aquatic.testimpact.cache

import java.nio.ByteBuffer

/** The SHA-256 digest of a class file, held as four longs so it can key a map without allocating a string. */
internal class ContentKey private constructor(
    val first: Long,
    val second: Long,
    val third: Long,
    val fourth: Long,
) {
    fun toBytes(): ByteArray =
        ByteBuffer.allocate(BYTES).putLong(first).putLong(second).putLong(third).putLong(fourth).array()

    override fun equals(other: Any?): Boolean =
        other is ContentKey && first == other.first && second == other.second &&
            third == other.third && fourth == other.fourth

    override fun hashCode(): Int = first.hashCode()

    override fun toString(): String = "ContentKey(${java.lang.Long.toHexString(first)}...)"

    companion object {
        const val BYTES: Int = 32

        fun of(digest: ByteArray): ContentKey {
            require(digest.size == BYTES) { "digest must be $BYTES bytes, was ${digest.size}" }
            val buffer = ByteBuffer.wrap(digest)
            return ContentKey(buffer.getLong(), buffer.getLong(), buffer.getLong(), buffer.getLong())
        }
    }
}
