package gg.aquatic.testimpact.graph.internal.classfile

import java.io.DataOutputStream
import java.util.TreeSet
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class AnnotationScannerTest {

    private class Fixture(val pool: ClassBytes = ClassBytes()) {
        val refs = TreeSet<String>()
        fun scanner() = AnnotationScanner(ConstantPool.read(ByteCursor(pool.poolBytes())), refs)
    }

    @Test
    fun `annotation types and enum, class and nested element values are collected`() {
        val f = Fixture()
        val type = f.pool.utf8("Lp/Ann;")
        val enumType = f.pool.utf8("Lp/Kind;")
        val enumConst = f.pool.utf8("A")
        val classValue = f.pool.utf8("Lp/Target;")
        val nestedType = f.pool.utf8("Lp/Nested;")
        val arrayClass = f.pool.utf8("[Lp/Elem;")
        val names = (0..5).map { f.pool.utf8("n$it") }
        val text = f.pool.utf8("text")
        val int = f.pool.integer(7)

        val body = bytes {
            writeShort(1)
            writeShort(type)
            writeShort(6)
            writeShort(names[0]); writeByte('e'.code); writeShort(enumType); writeShort(enumConst)
            writeShort(names[1]); writeByte('c'.code); writeShort(classValue)
            writeShort(names[2]); writeByte('@'.code); writeShort(nestedType); writeShort(0)
            writeShort(names[3]); writeByte('['.code); writeShort(2)
            writeByte('c'.code); writeShort(arrayClass)
            writeByte('s'.code); writeShort(text)
            writeShort(names[4]); writeByte('I'.code); writeShort(int)
            writeShort(names[5]); writeByte('s'.code); writeShort(text)
        }
        val cursor = ByteCursor(body)
        f.scanner().scanAnnotations(cursor)

        assertEquals(sortedSetOf("p.Ann", "p.Kind", "p.Target", "p.Nested", "p.Elem"), f.refs)
        assertEquals(0, cursor.remaining)
    }

    @Test
    fun `nesting of 10000 annotations ends as a malformed class, not a stack overflow`() {
        val f = Fixture()
        val type = f.pool.utf8("Lp/Ann;")
        val name = f.pool.utf8("v")
        val body = bytes {
            writeShort(1)
            repeat(10_000) {
                writeShort(type); writeShort(1); writeShort(name); writeByte('@'.code)
            }
            writeShort(type); writeShort(0)
        }
        assertFailsWith<MalformedClassException> { f.scanner().scanAnnotations(ByteCursor(body)) }
    }

    @Test
    fun `nesting of 10000 arrays ends as a malformed class, not a stack overflow`() {
        val f = Fixture()
        val body = bytes {
            repeat(10_000) { writeByte('['.code); writeShort(1) }
            writeByte('Z'.code); writeShort(0)
        }
        assertFailsWith<MalformedClassException> { f.scanner().scanElementValue(ByteCursor(body)) }
    }

    @Test
    fun `nesting up to the cap is accepted`() {
        val f = Fixture()
        val type = f.pool.utf8("Lp/Ann;")
        val name = f.pool.utf8("v")
        val body = bytes {
            writeShort(1)
            repeat(64) {
                writeShort(type); writeShort(1); writeShort(name); writeByte('@'.code)
            }
            writeShort(type); writeShort(0)
        }
        f.scanner().scanAnnotations(ByteCursor(body))
        assertEquals(sortedSetOf("p.Ann"), f.refs)
    }

    @Test
    fun `unknown element value tag and truncated structures are malformed`() {
        val f = Fixture()
        val type = f.pool.utf8("Lp/Ann;")
        val name = f.pool.utf8("v")
        val badTag = bytes { writeShort(1); writeShort(type); writeShort(1); writeShort(name); writeByte('x'.code); writeShort(0) }
        assertFailsWith<MalformedClassException> { f.scanner().scanAnnotations(ByteCursor(badTag)) }

        val truncated = bytes { writeShort(1); writeShort(type); writeShort(3) }
        assertFailsWith<MalformedClassException> { f.scanner().scanAnnotations(ByteCursor(truncated)) }

        val hugeCount = bytes { writeShort(65535) }
        assertFailsWith<MalformedClassException> { f.scanner().scanAnnotations(ByteCursor(hugeCount)) }
    }

    @Test
    fun `annotation default and parameter annotations are walked`() {
        val f = Fixture()
        val a = f.pool.utf8("Lp/A;")
        val b = f.pool.utf8("Lp/B;")
        val classValue = f.pool.utf8("Lp/Default;")

        f.scanner().scanElementValue(ByteCursor(bytes { writeByte('c'.code); writeShort(classValue) }))
        val parameters = bytes {
            writeByte(2)
            writeShort(1); writeShort(a); writeShort(0)
            writeShort(1); writeShort(b); writeShort(0)
        }
        val cursor = ByteCursor(parameters)
        f.scanner().scanParameterAnnotations(cursor)

        assertEquals(sortedSetOf("p.A", "p.B", "p.Default"), f.refs)
        assertEquals(0, cursor.remaining)
    }

    @Test
    fun `every type annotation target of the specification is skipped with its exact size`() {
        val f = Fixture()
        val type = f.pool.utf8("Lp/Ann;")
        val infoSizes = mapOf(
            0x00 to 1, 0x01 to 1, 0x10 to 2, 0x11 to 2, 0x12 to 2, 0x13 to 0, 0x14 to 0, 0x15 to 0, 0x16 to 1, 0x17 to 2,
            0x42 to 2, 0x43 to 2, 0x44 to 2, 0x45 to 2, 0x46 to 2, 0x47 to 3, 0x48 to 3, 0x49 to 3, 0x4A to 3, 0x4B to 3,
        )
        for ((target, size) in infoSizes) {
            val body = bytes {
                writeShort(1)
                writeByte(target)
                repeat(size) { writeByte(0xAA) }
                writeByte(2); writeByte(0); writeByte(0); writeByte(3); writeByte(1)
                writeShort(type); writeShort(0)
            }
            val cursor = ByteCursor(body)
            f.scanner().scanTypeAnnotations(cursor)
            assertEquals(0, cursor.remaining, "target 0x${target.toString(16)}")
        }
        for (target in intArrayOf(0x40, 0x41)) {
            val body = bytes {
                writeShort(1)
                writeByte(target)
                writeShort(2)
                repeat(12) { writeByte(0xBB) }
                writeByte(0)
                writeShort(type); writeShort(0)
            }
            val cursor = ByteCursor(body)
            f.scanner().scanTypeAnnotations(cursor)
            assertEquals(0, cursor.remaining, "localvar target 0x${target.toString(16)}")
        }
        assertEquals(sortedSetOf("p.Ann"), f.refs)
    }

    @Test
    fun `type annotation targets outside the table are malformed`() {
        val f = Fixture()
        val type = f.pool.utf8("Lp/Ann;")
        for (target in intArrayOf(0x02, 0x0F, 0x18, 0x3F, 0x4C, 0xFF)) {
            val body = bytes { writeShort(1); writeByte(target); writeByte(0); writeByte(0); writeShort(type); writeShort(0) }
            assertFailsWith<MalformedClassException>("target $target") { f.scanner().scanTypeAnnotations(ByteCursor(body)) }
        }
    }

    private fun DataOutputStream.metadataAnnotation(f: Fixture, kind: Int?, withExtras: Boolean) {
        val type = f.pool.utf8("Lkotlin/Metadata;")
        val k = f.pool.utf8("k")
        val d1 = f.pool.utf8("d1")
        val d2 = f.pool.utf8("d2")
        val xs = f.pool.utf8("xs")
        val mv = f.pool.utf8("mv")
        val kindValue = f.pool.integer(kind ?: 0)
        val mvValue = f.pool.integer(2)
        val s1 = f.pool.utf8("\u0000abc")
        val s2 = f.pool.utf8("def")
        val s3 = f.pool.utf8("Lp/Meters;")
        val s4 = f.pool.utf8("p/Facade")
        writeShort(1)
        writeShort(type)
        writeShort((if (kind != null) 1 else 0) + 1 + (if (withExtras) 3 else 0))
        if (kind != null) { writeShort(k); writeByte('I'.code); writeShort(kindValue) }
        writeShort(mv); writeByte('['.code); writeShort(1); writeByte('I'.code); writeShort(mvValue)
        if (withExtras) {
            writeShort(d1); writeByte('['.code); writeShort(2)
            writeByte('s'.code); writeShort(s1); writeByte('s'.code); writeShort(s2)
            writeShort(d2); writeByte('['.code); writeShort(1); writeByte('s'.code); writeShort(s3)
            writeShort(xs); writeByte('s'.code); writeShort(s4)
        }
    }

    @Test
    fun `the kotlin metadata annotation is captured at class level only`() {
        val f = Fixture()
        val body = bytes { metadataAnnotation(f, 5, withExtras = true) }

        val scannerWithoutCapture = f.scanner()
        scannerWithoutCapture.scanAnnotations(ByteCursor(body))
        assertNull(scannerWithoutCapture.metadata)

        val scanner = f.scanner()
        scanner.scanAnnotations(ByteCursor(body), capture = true)
        val metadata = assertNotNull(scanner.metadata)
        assertEquals(5, metadata.kind)
        assertEquals(listOf("\u0000abc", "def"), metadata.data1)
        assertEquals(listOf("Lp/Meters;"), metadata.data2)
        assertEquals("p/Facade", metadata.extraString)
    }

    @Test
    fun `a kotlin metadata annotation without k means kind 1`() {
        val f = Fixture()
        val body = bytes { metadataAnnotation(f, null, withExtras = false) }
        val scanner = f.scanner()
        scanner.scanAnnotations(ByteCursor(body), capture = true)

        val metadata = assertNotNull(scanner.metadata)
        assertEquals(1, metadata.kind)
        assertEquals(emptyList(), metadata.data1)
    }

    @Test
    fun `source debug extension chunks are joined`() {
        val f = Fixture()
        val type = f.pool.utf8("Lkotlin/jvm/internal/SourceDebugExtension;")
        val value = f.pool.utf8("value")
        val first = f.pool.utf8("SMAP\nA.kt\n")
        val second = f.pool.utf8("Kotlin\n*E\n")
        val body = bytes {
            writeShort(1); writeShort(type); writeShort(1); writeShort(value)
            writeByte('['.code); writeShort(2)
            writeByte('s'.code); writeShort(first)
            writeByte('s'.code); writeShort(second)
        }
        val scanner = f.scanner()
        scanner.scanAnnotations(ByteCursor(body), capture = true)

        assertEquals("SMAP\nA.kt\nKotlin\n*E\n", scanner.debugExtension)
    }
}
