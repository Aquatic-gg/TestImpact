package gg.aquatic.testimpact.cache.internal

import gg.aquatic.testimpact.cache.CacheStamp
import gg.aquatic.testimpact.cache.ContentKey
import gg.aquatic.testimpact.graph.ClassFacts
import gg.aquatic.testimpact.graph.ConstantField
import gg.aquatic.testimpact.graph.ReachScope
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.BufferUnderflowException
import java.nio.ByteBuffer
import java.util.zip.CRC32C

/**
 * Binary layout of one root's cache file: magic, format version, plugin version, root path, a deduplicated string
 * table, the entries (content key, then the facts with strings as table indexes, enums as ordinals), and a CRC32C
 * of everything before it. The file is untrusted: [decode] verifies length and checksum first and then bounds every
 * count, index and ordinal against what is left of the file, so a damaged or hostile file is rejected. Decoding
 * allocates a small multiple of the file's size (strings and fact lists), which [CacheFiles.MAX_FILE_BYTES] bounds. Every field of `ClassFacts` and `ConstantField` is written; a unit test fails
 * when one is added without extending this codec.
 */
internal object CacheCodec {
    private val MAGIC = byteArrayOf('T'.code.toByte(), 'I'.code.toByte(), 'A'.code.toByte(), 'C'.code.toByte())
    private const val NULL_INDEX = -1
    private const val CHECKSUM_BYTES = 4
    private const val MIN_SIZE = 4 + 4 + CHECKSUM_BYTES
    private const val MIN_ENTRY_BYTES = ContentKey.BYTES
    private const val MIN_STRING_BYTES = 4
    private const val MIN_REFERENCE_BYTES = 4
    private const val MIN_CONSTANT_BYTES = 13
    private val scopes = ReachScope.entries

    fun encode(stamp: CacheStamp, rootPath: String, entries: Map<ContentKey, ClassFacts>): ByteArray {
        val ordered = entries.entries.sortedWith(
            compareBy({ it.key.first }, { it.key.second }, { it.key.third }, { it.key.fourth }),
        )
        val table = LinkedHashMap<String, Int>()
        fun index(value: String?): Int = if (value == null) NULL_INDEX else table.getOrPut(value) { table.size }
        for ((_, facts) in ordered) {
            index(facts.name)
            index(facts.sourceFile)
            index(facts.outer)
            facts.supertypes.forEach(::index)
            facts.references.forEach(::index)
            facts.inlineSources.forEach(::index)
            for (field in facts.constantFields) {
                index(field.name)
                index(field.descriptor)
                index(field.value)
            }
        }
        val bytes = ByteArrayOutputStream()
        val out = DataOutputStream(bytes)
        out.write(MAGIC)
        out.writeInt(stamp.formatVersion)
        writeString(out, stamp.pluginVersion)
        writeString(out, rootPath)
        out.writeInt(table.size)
        for (text in table.keys) writeString(out, text)
        out.writeInt(ordered.size)
        for ((key, facts) in ordered) {
            out.write(key.toBytes())
            out.writeInt(index(facts.name))
            out.writeInt(facts.access)
            out.writeInt(index(facts.sourceFile))
            writeIndexes(out, facts.supertypes, ::index)
            out.writeInt(index(facts.outer))
            writeIndexes(out, facts.references, ::index)
            writeIndexes(out, facts.inlineSources, ::index)
            out.writeInt(facts.constantScope?.ordinal ?: NULL_INDEX)
            out.writeInt(facts.constantFields.size)
            for (field in facts.constantFields) {
                out.writeInt(index(field.name))
                out.writeInt(index(field.descriptor))
                out.writeInt(index(field.value))
                out.writeByte(field.scope.ordinal)
            }
            out.writeBoolean(facts.isKotlin)
            out.writeInt(facts.typeAliasScope?.ordinal ?: NULL_INDEX)
        }
        out.flush()
        val body = bytes.toByteArray()
        return ByteBuffer.allocate(body.size + CHECKSUM_BYTES).put(body).putInt(checksum(body, body.size)).array()
    }

    fun decode(bytes: ByteArray, stamp: CacheStamp, rootPath: String): Decoded {
        if (bytes.size < MIN_SIZE) return Decoded.Rejected("too short (${bytes.size} bytes)")
        for (i in MAGIC.indices) {
            if (bytes[i] != MAGIC[i]) return Decoded.Rejected("bad magic")
        }
        val bodyLength = bytes.size - CHECKSUM_BYTES
        val stored = ByteBuffer.wrap(bytes, bodyLength, CHECKSUM_BYTES).getInt()
        if (stored != checksum(bytes, bodyLength)) return Decoded.Rejected("checksum mismatch")
        val buffer = ByteBuffer.wrap(bytes, MAGIC.size, bodyLength - MAGIC.size).slice()
        return try {
            read(buffer, stamp, rootPath)
        } catch (e: CorruptCacheException) {
            Decoded.Rejected("malformed at offset ${MAGIC.size + buffer.position()}: ${e.message}")
        } catch (e: BufferUnderflowException) {
            Decoded.Rejected("malformed at offset ${MAGIC.size + buffer.position()}: unexpected end")
        } catch (e: RuntimeException) {
            Decoded.Rejected("malformed at offset ${MAGIC.size + buffer.position()}: ${e.javaClass.simpleName}")
        }
    }

    private fun read(buffer: ByteBuffer, stamp: CacheStamp, rootPath: String): Decoded {
        val format = buffer.getInt()
        if (format != stamp.formatVersion) return Decoded.Rejected("format version $format")
        val plugin = readString(buffer)
        if (plugin != stamp.pluginVersion) return Decoded.Rejected("plugin version $plugin")
        if (readString(buffer) != rootPath) return Decoded.Rejected("other root")
        val strings = Array(readCount(buffer, MIN_STRING_BYTES)) { readString(buffer) }
        val count = readCount(buffer, MIN_ENTRY_BYTES)
        val entries = HashMap<ContentKey, ClassFacts>(count * 2)
        val digest = ByteArray(ContentKey.BYTES)
        repeat(count) {
            buffer.get(digest)
            val key = ContentKey.of(digest)
            val name = requireNotNull(string(buffer, strings, nullable = false))
            val access = buffer.getInt()
            val sourceFile = string(buffer, strings, nullable = true)
            val supertypes = readStrings(buffer, strings)
            val outer = string(buffer, strings, nullable = true)
            val references = readStrings(buffer, strings)
            val inlineSources = readStrings(buffer, strings)
            val constantScope = scope(buffer.getInt(), nullable = true)
            val constants = ArrayList<ConstantField>(0)
            repeat(readCount(buffer, MIN_CONSTANT_BYTES)) {
                constants.add(
                    ConstantField(
                        name = requireNotNull(string(buffer, strings, nullable = false)),
                        descriptor = requireNotNull(string(buffer, strings, nullable = false)),
                        value = requireNotNull(string(buffer, strings, nullable = false)),
                        scope = requireNotNull(scope(buffer.get().toInt(), nullable = false)),
                    ),
                )
            }
            val isKotlin = when (buffer.get().toInt()) {
                0 -> false
                1 -> true
                else -> throw CorruptCacheException("invalid boolean")
            }
            val typeAliasScope = scope(buffer.getInt(), nullable = true)
            entries[key] = ClassFacts(
                name, access, sourceFile, supertypes, outer, references, inlineSources,
                constantScope, constants, isKotlin, typeAliasScope,
            )
        }
        if (buffer.hasRemaining()) throw CorruptCacheException("${buffer.remaining()} unread bytes")
        return Decoded.Entries(entries)
    }

    private fun readStrings(buffer: ByteBuffer, strings: Array<String>): List<String> {
        val count = readCount(buffer, MIN_REFERENCE_BYTES)
        val values = ArrayList<String>(count)
        repeat(count) { values.add(requireNotNull(string(buffer, strings, nullable = false))) }
        return values
    }

    private fun string(buffer: ByteBuffer, strings: Array<String>, nullable: Boolean): String? {
        val index = buffer.getInt()
        if (index == NULL_INDEX && nullable) return null
        if (index < 0 || index >= strings.size) throw CorruptCacheException("string index $index out of range")
        return strings[index]
    }

    private fun scope(ordinal: Int, nullable: Boolean): ReachScope? {
        if (ordinal == NULL_INDEX && nullable) return null
        if (ordinal < 0 || ordinal >= scopes.size) throw CorruptCacheException("scope ordinal $ordinal out of range")
        return scopes[ordinal]
    }

    private fun readCount(buffer: ByteBuffer, minBytesEach: Int): Int {
        val count = buffer.getInt()
        if (count < 0 || count.toLong() * minBytesEach > buffer.remaining()) {
            throw CorruptCacheException("count $count exceeds the remaining ${buffer.remaining()} bytes")
        }
        return count
    }

    private fun readString(buffer: ByteBuffer): String {
        val length = buffer.getInt()
        if (length < 0 || length > buffer.remaining() || length % Char.SIZE_BYTES != 0) {
            throw CorruptCacheException("string length $length")
        }
        val chars = CharArray(length / Char.SIZE_BYTES)
        for (i in chars.indices) chars[i] = buffer.getChar()
        return String(chars)
    }

    /** Strings are stored as raw UTF-16 code units: constants may hold unpaired surrogates, which UTF-8 cannot carry. */
    private fun writeString(out: DataOutputStream, value: String) {
        out.writeInt(value.length * Char.SIZE_BYTES)
        out.writeChars(value)
    }

    private fun writeIndexes(out: DataOutputStream, values: List<String>, index: (String?) -> Int) {
        out.writeInt(values.size)
        for (value in values) out.writeInt(index(value))
    }

    private fun checksum(bytes: ByteArray, length: Int): Int {
        val crc = CRC32C()
        crc.update(bytes, 0, length)
        return crc.value.toInt()
    }
}
