package gg.aquatic.testimpact.graph.internal.classfile

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConstantPoolTest {

    private fun read(builder: ClassBytes, count: Int? = null): ConstantPool {
        val bytes = if (count == null) builder.poolBytes() else builder.poolBytes(count)
        return ConstantPool.read(ByteCursor(bytes))
    }

    @Test
    fun `every entry kind is read and typed accessors return its values`() {
        val b = ClassBytes()
        val text = b.utf8("p/Owner")
        val owner = b.classRef(text)
        val int = b.integer(-5)
        val float = b.float(1.5f)
        val long = b.long(Long.MIN_VALUE + 3)
        val double = b.double(2.5)
        val string = b.string("hello")
        val nat = b.nameAndType("run", "(Lp/A;)V")
        val method = b.member(ClassBytes.METHOD_REF, owner, nat)
        val handle = b.methodHandle(6, method)
        val type = b.methodType("(Lp/B;)Lp/C;")
        val indy = b.invokeDynamic(0, nat)
        val module = b.raw(1, 19, 0, text)
        val pkg = b.raw(1, 20, 0, text)

        val pool = read(b)

        assertEquals("p/Owner", pool.utf8(text))
        assertEquals("p/Owner", pool.classInternalName(owner))
        assertEquals("p.Owner", pool.className(owner))
        assertEquals(-5, pool.intValue(int))
        assertEquals(1.5f, Float.fromBits(pool.floatBits(float)))
        assertEquals(Long.MIN_VALUE + 3, pool.longValue(long))
        assertEquals(2.5, Double.fromBits(pool.doubleBits(double)))
        assertEquals("hello", pool.stringValue(string))
        assertEquals("run", pool.nameAndTypeName(nat))
        assertEquals("(Lp/A;)V", pool.nameAndTypeDescriptor(nat))
        assertEquals(owner, pool.memberClassIndex(method))
        assertEquals(nat, pool.memberNameAndTypeIndex(method))
        assertEquals(6, pool.methodHandleKind(handle))
        assertEquals(method, pool.methodHandleReferenceIndex(handle))
        assertEquals("(Lp/B;)Lp/C;", pool.methodTypeDescriptor(type))
        assertEquals(nat, pool.dynamicNameAndTypeIndex(indy))
        assertEquals("p/Owner", pool.moduleOrPackageName(module))
        assertEquals("p/Owner", pool.moduleOrPackageName(pkg))
    }

    @Test
    fun `long and double take two slots and the pool ends right after the last entry`() {
        val b = ClassBytes()
        val long = b.long(1)
        val after = b.utf8("after")
        val bytes = b.poolBytes() + byteArrayOf(0x55)
        val cursor = ByteCursor(bytes)

        val pool = ConstantPool.read(cursor)

        assertEquals(1, long)
        assertEquals(3, after)
        assertEquals(4, pool.size)
        assertEquals("after", pool.utf8(after))
        assertEquals(bytes.size - 1, cursor.position)
        assertFailsWith<MalformedClassException> { pool.tag(2) }
    }

    @Test
    fun `an array class constant resolves to its element and primitive arrays to null`() {
        val b = ClassBytes()
        val objects = b.classRef("[[Lp/A;")
        val primitives = b.classRef("[I")

        val pool = read(b)

        assertEquals("p.A", pool.className(objects))
        assertEquals("p.A", pool.className(objects))
        assertNull(pool.className(primitives))
        assertEquals("[[Lp/A;", pool.classInternalName(objects))
    }

    @Test
    fun `an accessor of the wrong kind throws MalformedClassException`() {
        val b = ClassBytes()
        val text = b.utf8("p/A")
        val owner = b.classRef(text)
        val pool = read(b)

        assertFailsWith<MalformedClassException> { pool.className(text) }
        assertFailsWith<MalformedClassException> { pool.utf8(owner) }
        assertFailsWith<MalformedClassException> { pool.intValue(owner) }
        assertFailsWith<MalformedClassException> { pool.memberClassIndex(owner) }
        assertFailsWith<MalformedClassException> { pool.methodHandleKind(text) }
        assertFailsWith<MalformedClassException> { pool.dynamicNameAndTypeIndex(owner) }
        assertFailsWith<MalformedClassException> { pool.moduleOrPackageName(owner) }
    }

    @Test
    fun `index zero and indices past the count throw MalformedClassException`() {
        val b = ClassBytes()
        b.utf8("a")
        val pool = read(b)

        assertFailsWith<MalformedClassException> { pool.utf8(0) }
        assertFailsWith<MalformedClassException> { pool.utf8(2) }
        assertFailsWith<MalformedClassException> { pool.utf8(-1) }
        assertFailsWith<MalformedClassException> { pool.utf8(0xFFFF) }
        assertFailsWith<MalformedClassException> { pool.tag(Int.MAX_VALUE) }
    }

    @Test
    fun `a class entry pointing at a bad utf8 or the wrong tag fails on access not on read`() {
        val b = ClassBytes()
        val badName = b.classRef(99)
        val integer = b.integer(1)
        val pointsAtInteger = b.classRef(integer)

        val pool = read(b)

        assertFailsWith<MalformedClassException> { pool.className(badName) }
        assertFailsWith<MalformedClassException> { pool.className(pointsAtInteger) }
    }

    @Test
    fun `a long in the last slot without its second slot is rejected`() {
        val b = ClassBytes()
        b.long(1)

        assertFailsWith<MalformedClassException> { read(b, count = 2) }
    }

    @Test
    fun `unknown tags including the unused 2 13 and 14 are rejected`() {
        for (tag in listOf(0, 2, 13, 14, 21, 255)) {
            val b = ClassBytes()
            b.raw(1, tag, 0, 0, 0, 0)

            assertFailsWith<MalformedClassException>("tag $tag") { read(b) }
        }
    }

    @Test
    fun `invalid method handle kinds are rejected`() {
        for (kind in listOf(0, 10)) {
            val b = ClassBytes()
            b.methodHandle(kind, 1)

            assertFailsWith<MalformedClassException>("kind $kind") { read(b) }
        }
    }

    @Test
    fun `a pool cut short anywhere throws MalformedClassException`() {
        val b = ClassBytes()
        b.utf8("hello")
        b.long(7)
        b.classRef("p/A")
        b.nameAndType("n", "I")
        val full = b.poolBytes()

        for (length in 0 until full.size) {
            assertFailsWith<MalformedClassException>("length $length") {
                ConstantPool.read(ByteCursor(full.copyOf(length)))
            }
        }
    }

    @Test
    fun `a count of zero and a count past the data are rejected`() {
        assertFailsWith<MalformedClassException> { ConstantPool.read(ByteCursor(byteArrayOf(0, 0))) }
        assertFailsWith<MalformedClassException> {
            ConstantPool.read(ByteCursor(byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 1, 0, 1, 'a'.code.toByte())))
        }
    }

    @Test
    fun `a utf8 length past the end is rejected`() {
        val bytes = byteArrayOf(0, 2, 1, 0xFF.toByte(), 0xFF.toByte(), 'a'.code.toByte())

        assertFailsWith<MalformedClassException> { ConstantPool.read(ByteCursor(bytes)) }
    }

    @Test
    fun `utf8 entries decode lazily so malformed text surfaces on access`() {
        val b = ClassBytes()
        val bad = b.raw(1, 1, 0, 1, 0xFF)
        val good = b.utf8("ok")

        val pool = read(b)

        assertEquals("ok", pool.utf8(good))
        assertFailsWith<MalformedClassException> { pool.utf8(bad) }
    }

    @Test
    fun `utf8 prefix tests run on raw bytes and forEachOfTag lists entries in pool order`() {
        val b = ClassBytes()
        val first = b.utf8("\$i\$f\$run")
        val second = b.utf8("other")
        val classes = listOf(b.classRef("p/A"), b.classRef("p/B"))

        val pool = read(b)

        assertTrue(pool.utf8StartsWith(first, "\$i\$f\$"))
        assertFalse(pool.utf8StartsWith(second, "\$i\$f\$"))
        assertTrue(pool.anyUtf8StartsWith("\$i\$f\$"))
        assertFalse(pool.anyUtf8StartsWith("zzz"))
        val seen = mutableListOf<Int>()
        pool.forEachOfTag(ConstantPool.CLASS) { seen.add(it) }
        assertEquals(classes, seen)
    }

    @Test
    fun `a whole minimal class file starts with the pool at offset 8`() {
        val b = ClassBytes()
        val self = b.classRef("p/A")
        val bytes = b.classFile(self)
        val cursor = ByteCursor(bytes, position = 8)

        val pool = ConstantPool.read(cursor)

        assertEquals("p.A", pool.className(self))
        assertEquals(bytes.size - 14, cursor.position)
    }
}
