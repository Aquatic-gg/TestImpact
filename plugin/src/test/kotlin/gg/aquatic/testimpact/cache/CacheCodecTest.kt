package gg.aquatic.testimpact.cache

import gg.aquatic.testimpact.cache.internal.CacheCodec
import gg.aquatic.testimpact.cache.internal.Decoded
import gg.aquatic.testimpact.graph.ClassFacts
import gg.aquatic.testimpact.graph.ConstantField
import gg.aquatic.testimpact.graph.FixtureClasses
import gg.aquatic.testimpact.graph.ReachScope
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.lang.reflect.Modifier
import java.security.MessageDigest
import java.util.Random
import java.util.zip.CRC32C
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

internal class CacheCodecTest {
    private val stamp = CacheStamp(CacheStamp.FORMAT_VERSION, "1.0")
    private val root = "/work/classes"

    private fun key(text: String): ContentKey = ContentKey.of(MessageDigest.getInstance("SHA-256").digest(text.toByteArray()))

    private fun corpus(): Map<ContentKey, ClassFacts> {
        val graph = FixtureClasses.graph
        val entries = LinkedHashMap<ContentKey, ClassFacts>()
        for (name in graph.classes) entries[key(name)] = checkNotNull(graph.facts(name))
        for (scope in ReachScope.entries) {
            entries[key("scope-$scope")] = ClassFacts(
                name = "p.Scoped$scope",
                access = 0x21,
                sourceFile = null,
                supertypes = emptyList(),
                outer = null,
                references = emptyList(),
                inlineSources = emptyList(),
                constantScope = scope,
                constantFields = listOf(ConstantField("C", "I", "", scope), ConstantField("D", "Ljava/lang/String;", "é中", scope)),
                isKotlin = true,
                typeAliasScope = scope,
            )
        }
        entries[key("bare")] = ClassFacts("p.Bare", 0, "Bare.java", listOf("p.A", "p.B"), "p.Outer", listOf("p.A"), listOf("p.B"), null, emptyList(), false, null)
        return entries
    }

    private fun decode(bytes: ByteArray, with: CacheStamp = stamp, path: String = root): Decoded = CacheCodec.decode(bytes, with, path)

    private fun assertRejected(decoded: Decoded, reasonPart: String? = null) {
        val rejected = assertIs<Decoded.Rejected>(decoded)
        if (reasonPart != null) assertTrue(reasonPart in rejected.reason, "reason '${rejected.reason}' lacks '$reasonPart'")
    }

    private fun sealed(body: ByteArray): ByteArray {
        val crc = CRC32C().apply { update(body, 0, body.size) }
        return ByteArrayOutputStream().apply {
            write(body)
            DataOutputStream(this).writeInt(crc.value.toInt())
        }.toByteArray()
    }

    private fun header(out: DataOutputStream) {
        out.write("TIAC".toByteArray())
        out.writeInt(stamp.formatVersion)
        for (text in listOf(stamp.pluginVersion, root)) {
            out.writeInt(text.length * 2)
            out.writeChars(text)
        }
    }

    @Test
    fun `round trip keeps every fact`() {
        val entries = corpus()
        val decoded = assertIs<Decoded.Entries>(decode(CacheCodec.encode(stamp, root, entries)))
        assertEquals(entries, decoded.entries)
    }

    @Test
    fun `encoding is deterministic`() {
        val entries = corpus()
        val reversed = LinkedHashMap<ContentKey, ClassFacts>().apply { entries.entries.reversed().forEach { put(it.key, it.value) } }
        assertTrue(CacheCodec.encode(stamp, root, entries).contentEquals(CacheCodec.encode(stamp, root, reversed)))
    }

    @Test
    fun `empty input and wrong header are rejected`() {
        val bytes = CacheCodec.encode(stamp, root, corpus())
        assertRejected(decode(ByteArray(0)), "too short")
        assertRejected(decode(bytes.copyOf().also { it[0] = 'X'.code.toByte() }), "bad magic")
        assertRejected(decode(bytes, CacheStamp(CacheStamp.FORMAT_VERSION + 1, "1.0")), "format version")
        assertRejected(decode(bytes, CacheStamp(CacheStamp.FORMAT_VERSION, "2.0")), "plugin version")
        assertRejected(decode(bytes, path = "/other"), "other root")
    }

    @Test
    fun `truncation and a flipped byte are rejected`() {
        val bytes = CacheCodec.encode(stamp, root, corpus())
        val random = Random(7)
        repeat(10) {
            assertRejected(decode(bytes.copyOf(random.nextInt(bytes.size))))
        }
        assertRejected(decode(bytes.copyOf().also { it[bytes.size / 2] = (it[bytes.size / 2].toInt() xor 0x55).toByte() }), "checksum")
    }

    @Test
    fun `a valid checksum cannot make the decoder trust sizes`() {
        val hugeCount = ByteArrayOutputStream().also {
            DataOutputStream(it).apply { header(this); writeInt(0); writeInt(Int.MAX_VALUE) }
        }.toByteArray()
        assertRejected(decode(sealed(hugeCount)), "exceeds")

        val hugeTable = ByteArrayOutputStream().also {
            DataOutputStream(it).apply { header(this); writeInt(Int.MAX_VALUE) }
        }.toByteArray()
        assertRejected(decode(sealed(hugeTable)), "exceeds")

        val badIndex = ByteArrayOutputStream().also {
            DataOutputStream(it).apply {
                header(this)
                writeInt(1)
                writeInt(2)
                writeChars("a")
                writeInt(1)
                write(ByteArray(ContentKey.BYTES))
                writeInt(5)
                writeInt(0)
            }
        }.toByteArray()
        assertRejected(decode(sealed(badIndex)), "out of range")
    }

    @Test
    fun `extend CacheCodec and bump FORMAT_VERSION when a fact gains a field`() {
        fun fields(type: Class<*>) = type.declaredFields.count { !Modifier.isStatic(it.modifiers) }
        assertEquals(11, fields(ClassFacts::class.java), "ClassFacts changed: extend CacheCodec and bump FORMAT_VERSION")
        assertEquals(4, fields(ConstantField::class.java), "ConstantField changed: extend CacheCodec and bump FORMAT_VERSION")
        assertEquals(
            listOf("FILE", "PACKAGE", "ALL"),
            ReachScope.entries.map { it.name },
            "ReachScope changed: ordinals are stored, extend CacheCodec and bump FORMAT_VERSION",
        )
    }

    @Test
    fun `strings with unpaired surrogates survive`() {
        val values = listOf("\uD800", "a\uDC00b", "\uDC00\uD800", "ok 😀 é中", "")
        val entries = LinkedHashMap<ContentKey, ClassFacts>()
        values.forEachIndexed { i, value ->
            entries[key("s$i")] = ClassFacts(
                "p.S$i", 0, null, emptyList(), null, emptyList(), emptyList(), null,
                listOf(ConstantField("C", "Ljava/lang/String;", value, ReachScope.entries.first())), false, null,
            )
        }
        val decoded = assertIs<Decoded.Entries>(decode(CacheCodec.encode(stamp, root, entries)))
        assertEquals(entries, decoded.entries)
    }

    @Test
    fun `an odd string length is rejected`() {
        val odd = ByteArrayOutputStream().also {
            DataOutputStream(it).apply { header(this); writeInt(1); writeInt(3); write(ByteArray(3)) }
        }.toByteArray()
        assertRejected(decode(sealed(odd)), "string length")
    }
}
