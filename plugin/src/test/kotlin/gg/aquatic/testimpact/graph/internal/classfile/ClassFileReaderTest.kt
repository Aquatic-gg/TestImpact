package gg.aquatic.testimpact.graph.internal.classfile

import gg.aquatic.testimpact.graph.ClassFacts
import gg.aquatic.testimpact.graph.ConstantField
import gg.aquatic.testimpact.graph.ReachScope
import java.io.DataOutputStream
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ClassFileReaderTest {

    private fun shortAttribute(b: ClassBytes, name: String, index: Int) = b.attribute(name, bytes { writeShort(index) })

    private fun constantClass(
        classAccess: Int,
        fieldAccess: Int,
        innerFlags: Int? = null,
        outer: String? = null,
    ): ClassFacts {
        val b = ClassBytes()
        val self = b.classRef("p/A")
        val constant = b.integer(5)
        val field = b.declaration(fieldAccess, "X", "I", listOf(shortAttribute(b, "ConstantValue", constant)))
        val attributes = ArrayList<ByteArray>()
        if (innerFlags != null) {
            val outerIndex = if (outer != null) b.classRef(outer) else 0
            attributes.add(
                b.attribute("InnerClasses", bytes { writeShort(1); writeShort(self); writeShort(outerIndex); writeShort(0); writeShort(innerFlags) }),
            )
        }
        return ClassFileReader.read(b.classFile(self, access = classAccess, fields = listOf(field), attributes = attributes))
    }

    @Test
    fun `supertypes and references come from the structure of the file`() {
        val b = ClassBytes()
        val self = b.classRef("p/A")
        val base = b.classRef("p/Base")
        val itf = b.classRef("p/I")
        val field = b.declaration(
            0x2, "f", "Lp/FieldType;",
            listOf(shortAttribute(b, "Signature", b.utf8("Ljava/util/List<Lp/Generic;>;"))),
        )
        val code = b.attribute(
            "Code",
            bytes {
                writeShort(1); writeShort(1)
                writeInt(1); writeByte(0xB1)
                writeShort(0)
                writeShort(1)
                writeShort(b.utf8("RuntimeVisibleTypeAnnotations")); writeInt(10)
                writeShort(1); writeByte(0x45); writeShort(0); writeByte(0)
                writeShort(b.utf8("Lp/TypeAnn;")); writeShort(0)
            },
        )
        val method = b.declaration(0x1, "m", "(Lp/Param;)Lp/Ret;", listOf(code))
        val classAnnotation = b.attribute(
            "RuntimeInvisibleAnnotations",
            bytes { writeShort(1); writeShort(b.utf8("Lp/ClassAnn;")); writeShort(0) },
        )
        val sourceFile = shortAttribute(b, "SourceFile", b.utf8("A.kt"))

        val facts = ClassFileReader.read(
            b.classFile(self, base, 0x21, listOf(itf), listOf(field), listOf(method), listOf(classAnnotation, sourceFile)),
        )

        assertEquals("p.A", facts.name)
        assertEquals(0x21, facts.access)
        assertEquals("A.kt", facts.sourceFile)
        assertEquals(listOf("p.Base", "p.I"), facts.supertypes)
        assertEquals(
            listOf("java.util.List", "p.Base", "p.ClassAnn", "p.FieldType", "p.Generic", "p.I", "p.Param", "p.Ret", "p.TypeAnn"),
            facts.references,
        )
        assertFalse(facts.isKotlin)
        assertNull(facts.typeAliasScope)
        assertNull(facts.constantScope)
        assertNull(facts.outer)
        assertTrue(facts.inlineSources.isEmpty())
    }

    @Test
    fun `a record component contributes its descriptor, signature and annotations`() {
        val b = ClassBytes()
        val self = b.classRef("p/R")
        val record = b.attribute(
            "Record",
            bytes {
                writeShort(1)
                writeShort(b.utf8("items")); writeShort(b.utf8("Ljava/util/List;"))
                writeShort(2)
                writeShort(b.utf8("Signature")); writeInt(2); writeShort(b.utf8("Ljava/util/List<Lp/Item;>;"))
                writeShort(b.utf8("RuntimeVisibleAnnotations")); writeInt(6)
                writeShort(1); writeShort(b.utf8("Lp/Marker;")); writeShort(0)
            },
        )
        val facts = ClassFileReader.read(b.classFile(self, attributes = listOf(record)))

        assertEquals(listOf("java.util.List", "p.Item", "p.Marker"), facts.references)
    }

    @Test
    fun `constant fields carry the narrower of field and class visibility`() {
        assertEquals(ReachScope.FILE, constantClass(0x21, 0x1A).constantScope)
        assertEquals(ReachScope.PACKAGE, constantClass(0x21, 0x18).constantScope)
        assertEquals(ReachScope.ALL, constantClass(0x21, 0x19).constantScope)
        assertEquals(ReachScope.ALL, constantClass(0x21, 0x1C).constantScope)
        assertEquals(ReachScope.PACKAGE, constantClass(0x30, 0x19).constantScope)
        assertEquals(ReachScope.FILE, constantClass(0x30, 0x1A).constantScope)
        assertEquals(ReachScope.PACKAGE, constantClass(0x30, 0x18).constantScope)
    }

    @Test
    fun `a class that can be subclassed does not narrow its constants`() {
        assertEquals(ReachScope.ALL, constantClass(0x20, 0x19).constantScope)
        assertEquals(ReachScope.ALL, constantClass(0x20, 0x1C).constantScope)
        assertEquals(ReachScope.FILE, constantClass(0x20, 0x1A).constantScope)
        assertEquals(ReachScope.PACKAGE, constantClass(0x20, 0x18).constantScope)
        assertEquals(ReachScope.ALL, constantClass(0x600, 0x19).constantScope)
    }

    @Test
    fun `the InnerClasses flags of the class itself decide its visibility`() {
        assertEquals(ReachScope.FILE, constantClass(0x21, 0x19, innerFlags = 0x001A, outer = "p/Outer").constantScope)
        assertEquals(ReachScope.PACKAGE, constantClass(0x21, 0x19, innerFlags = 0x0018, outer = "p/Outer").constantScope)
        assertEquals(ReachScope.ALL, constantClass(0x21, 0x19, innerFlags = 0x0019, outer = "p/Outer").constantScope)
        assertEquals(ReachScope.ALL, constantClass(0x21, 0x19, innerFlags = 0x001C, outer = "p/Outer").constantScope)
        assertEquals(ReachScope.ALL, constantClass(0x21, 0x19, innerFlags = 0x000A, outer = "p/Outer").constantScope)
        assertEquals(ReachScope.ALL, constantClass(0x21, 0x19, innerFlags = 0x060A, outer = "p/Outer").constantScope)
    }

    @Test
    fun `constant values are rendered by type and sorted`() {
        val b = ClassBytes()
        val self = b.classRef("p/A")
        fun constant(name: String, descriptor: String, index: Int) =
            b.declaration(0x19, name, descriptor, listOf(shortAttribute(b, "ConstantValue", index)))
        val fields = listOf(
            constant("S", "Ljava/lang/String;", b.string("a * 2")),
            constant("L", "J", b.long(1L shl 40)),
            constant("F", "F", b.float(1.5f)),
            constant("D", "D", b.double(2.25)),
            constant("Z", "Z", b.integer(1)),
            b.declaration(0x19, "plain", "I"),
        )
        val facts = ClassFileReader.read(b.classFile(self, fields = fields))

        assertEquals(
            listOf(
                ConstantField("D", "D", "2.25", ReachScope.ALL),
                ConstantField("F", "F", "1.5", ReachScope.ALL),
                ConstantField("L", "J", (1L shl 40).toString(), ReachScope.ALL),
                ConstantField("S", "Ljava/lang/String;", "a * 2", ReachScope.ALL),
                ConstantField("Z", "Z", "1", ReachScope.ALL),
            ),
            facts.constantFields,
        )
    }

    @Test
    fun `a constant value pointing at the wrong kind of entry is malformed`() {
        val b = ClassBytes()
        val self = b.classRef("p/A")
        val field = b.declaration(0x19, "X", "I", listOf(shortAttribute(b, "ConstantValue", self)))
        assertFailsWith<MalformedClassException> { ClassFileReader.read(b.classFile(self, fields = listOf(field))) }
    }

    @Test
    fun `the direct outer class prefers InnerClasses, then EnclosingMethod, then NestHost, then the kotlin facade`() {
        assertEquals("p.Outer", constantClass(0x21, 0x19, innerFlags = 0, outer = "p/Outer").outer)
        assertNull(constantClass(0x21, 0x19, innerFlags = 0).outer)

        val b = ClassBytes()
        val self = b.classRef("p/A$1")
        val enclosing = b.classRef("p/Encl")
        val host = b.classRef("p/Host")
        val facts = ClassFileReader.read(
            b.classFile(
                self,
                attributes = listOf(
                    b.attribute("EnclosingMethod", bytes { writeShort(enclosing); writeShort(0) }),
                    shortAttribute(b, "NestHost", host),
                ),
            ),
        )
        assertEquals("p.Encl", facts.outer)

        val c = ClassBytes()
        val onlyHost = ClassFileReader.read(
            c.classFile(c.classRef("p/A${'$'}Inner"), attributes = listOf(shortAttribute(c, "NestHost", c.classRef("p/Host")))),
        )
        assertEquals("p.Host", onlyHost.outer)
    }

    @Test
    fun `kotlin metadata marks the class and adds d2 classes and the multifile facade`() {
        val b = ClassBytes()
        val self = b.classRef("p/Part")
        val annotation = b.attribute(
            "RuntimeVisibleAnnotations",
            bytes { metadataAnnotation(b, kind = 5, d1 = listOf("\u0000abc"), d2 = listOf("Lp/Meters;", "text"), xs = "p/Facade") },
        )
        val facts = ClassFileReader.read(b.classFile(self, attributes = listOf(annotation)))

        assertTrue(facts.isKotlin)
        assertContains(facts.references, "p.Meters")
        assertContains(facts.references, "kotlin.Metadata")
        assertEquals("p.Facade", facts.outer)
        assertEquals(ReachScope.ALL, facts.typeAliasScope)
    }

    @Test
    fun `kotlin metadata of a kind that cannot declare aliases leaves the scope empty`() {
        val b = ClassBytes()
        val self = b.classRef("p/Lambda")
        val annotation = b.attribute(
            "RuntimeVisibleAnnotations",
            bytes { metadataAnnotation(b, kind = 3, d1 = emptyList(), d2 = emptyList(), xs = null) },
        )
        val facts = ClassFileReader.read(b.classFile(self, attributes = listOf(annotation)))

        assertTrue(facts.isKotlin)
        assertNull(facts.typeAliasScope)
    }

    private fun DataOutputStream.metadataAnnotation(b: ClassBytes, kind: Int, d1: List<String>, d2: List<String>, xs: String?) {
        writeShort(1)
        writeShort(b.utf8("Lkotlin/Metadata;"))
        writeShort(3 + if (xs != null) 1 else 0)
        writeShort(b.utf8("k")); writeByte('I'.code); writeShort(b.integer(kind))
        for ((name, values) in listOf("d1" to d1, "d2" to d2)) {
            writeShort(b.utf8(name)); writeByte('['.code); writeShort(values.size)
            for (value in values) { writeByte('s'.code); writeShort(b.utf8(value)) }
        }
        if (xs != null) { writeShort(b.utf8("xs")); writeByte('s'.code); writeShort(b.utf8(xs)) }
    }

    private fun smap(vararg inlined: String) =
        "SMAP\nA.kt\nKotlin\n*S Kotlin\n*F\n" + inlined.withIndex().joinToString("") { (i, c) -> "+ ${i + 1} F$i.kt\n$c\n" } + "*L\n1#1:1\n*E\n"

    @Test
    fun `the source debug extension attribute lists inlined classes`() {
        val b = ClassBytes()
        val self = b.classRef("p/AKt")
        b.utf8("\$i\$f\$run")
        val attribute = b.attribute("SourceDebugExtension", smap("p/AKt", "p/Lib", "p/Other").toByteArray())
        val facts = ClassFileReader.read(b.classFile(self, attributes = listOf(attribute)))

        assertEquals(listOf("p.Lib", "p.Other"), facts.inlineSources)
    }

    @Test
    fun `the annotation copy of the source map is used as well`() {
        val b = ClassBytes()
        val self = b.classRef("p/AKt")
        b.utf8("\$i\$f\$run")
        val annotation = b.attribute(
            "RuntimeInvisibleAnnotations",
            bytes {
                writeShort(1)
                writeShort(b.utf8("Lkotlin/jvm/internal/SourceDebugExtension;"))
                writeShort(1)
                writeShort(b.utf8("value")); writeByte('['.code); writeShort(1)
                writeByte('s'.code); writeShort(b.utf8(smap("p/Lib")))
            },
        )
        val facts = ClassFileReader.read(b.classFile(self, attributes = listOf(annotation)))

        assertEquals(listOf("p.Lib"), facts.inlineSources)
    }

    @Test
    fun `an inline function marker inside its own definition needs no source map`() {
        val b = ClassBytes()
        val self = b.classRef("p/AKt")
        b.utf8("\$i\$f\$run")
        val definer = b.classFile(self, methods = listOf(b.declaration(0x19, "run", "()V")))
        assertEquals(emptyList(), ClassFileReader.read(definer).inlineSources)

        val c = ClassBytes()
        val other = c.classRef("p/BKt")
        c.utf8("\$i\$f\$run")
        val user = c.classFile(other, methods = listOf(c.declaration(0x19, "walk", "()V")))
        assertFailsWith<MalformedClassException> { ClassFileReader.read(user) }
    }

    @Test
    fun `inlined code without a source map is malformed`() {
        val b = ClassBytes()
        val self = b.classRef("p/AKt")
        b.utf8("\$i\$f\$run")
        val bare = b.classFile(self)
        val failure = assertFailsWith<MalformedClassException> { ClassFileReader.read(bare) }
        assertContains(failure.message.orEmpty(), "source map")

        val c = ClassBytes()
        val other = c.classRef("p/BKt")
        c.utf8("\$i\$f\$run")
        val notAMap = c.attribute("SourceDebugExtension", "not a map".toByteArray())
        assertFailsWith<MalformedClassException> { ClassFileReader.read(c.classFile(other, attributes = listOf(notAMap))) }
    }

    @Test
    fun `a module descriptor has facts but no edges`() {
        val b = ClassBytes()
        val self = b.classRef("module-info")
        b.classRef("p/Something")
        val facts = ClassFileReader.read(b.classFile(self, access = 0x8000))

        assertEquals("module-info", facts.name)
        assertTrue(facts.references.isEmpty())
        assertTrue(facts.supertypes.isEmpty())
        assertNull(facts.outer)
    }

    @Test
    fun `broken framing is malformed`() {
        val b = ClassBytes()
        val self = b.classRef("p/A")
        val valid = b.classFile(self)

        assertFailsWith<MalformedClassException> { ClassFileReader.read(valid.copyOf().also { it[0] = 0 }) }
        assertFailsWith<MalformedClassException> { ClassFileReader.read(valid.copyOf().also { it[7] = 44 }) }
        assertFailsWith<MalformedClassException> { ClassFileReader.read(valid + byteArrayOf(0)) }
        assertFailsWith<MalformedClassException> { ClassFileReader.read(valid.copyOf(valid.size - 1)) }
        assertFailsWith<MalformedClassException> { ClassFileReader.read(ByteArray(0)) }
    }

    @Test
    fun `this class must be a class entry`() {
        val b = ClassBytes()
        val text = b.utf8("p/A")
        assertFailsWith<MalformedClassException> { ClassFileReader.read(b.classFile(text)) }

        val c = ClassBytes()
        val array = c.classRef("[I")
        assertFailsWith<MalformedClassException> { ClassFileReader.read(c.classFile(array)) }
    }

    @Test
    fun `an attribute longer than the rest of the file or than its content is malformed`() {
        val b = ClassBytes()
        val self = b.classRef("p/A")
        val tooLong = b.attributeAt(b.utf8("SourceFile"), ByteArray(2), declaredLength = 1_000_000)
        assertFailsWith<MalformedClassException> { ClassFileReader.read(b.classFile(self, attributes = listOf(tooLong))) }

        val c = ClassBytes()
        val other = c.classRef("p/A")
        val leftover = c.attribute("SourceFile", bytes { writeShort(c.utf8("A.kt")); writeByte(1) })
        assertFailsWith<MalformedClassException> { ClassFileReader.read(c.classFile(other, attributes = listOf(leftover))) }
    }

    @Test
    fun `unknown attributes are skipped by their length`() {
        val b = ClassBytes()
        val self = b.classRef("p/A")
        val unknown = b.attribute("Whatever", ByteArray(37))
        val facts = ClassFileReader.read(b.classFile(self, attributes = listOf(unknown, shortAttribute(b, "SourceFile", b.utf8("A.java")))))

        assertEquals("A.java", facts.sourceFile)
    }

    @Test
    fun `an index outside of the pool in an attribute is malformed`() {
        val b = ClassBytes()
        val self = b.classRef("p/A")
        val broken = shortAttribute(b, "Signature", 4000)
        assertFailsWith<MalformedClassException> { ClassFileReader.read(b.classFile(self, attributes = listOf(broken))) }
    }

    private fun resource(path: String): ByteArray {
        val stream = assertNotNull(Thread.currentThread().contextClassLoader.getResourceAsStream(path), path)
        return stream.use { it.readBytes() }
    }

    @Test
    fun `a typealias used by an erased value class parameter shows in the d2 strings of the stdlib`() {
        val facts = ClassFileReader.read(resource("kotlin/text/StringsKt__StringBuilderKt.class"))

        assertTrue(facts.isKotlin)
        assertContains(facts.references, "kotlin.text.StringBuilder")
    }

    @Test
    fun `stdlib classes that inline other classes list them from the source map`() {
        val facts = ClassFileReader.read(resource("kotlin/collections/CollectionsKt___CollectionsKt.class"))

        assertContains(facts.inlineSources, "kotlin.collections.ArraysKt__ArraysJVMKt")
    }

    @Test
    fun `a class that only defines inline functions is read`() {
        val facts = ClassFileReader.read(resource("kotlin/StandardKt__StandardKt.class"))

        assertTrue(facts.isKotlin)
    }

    @Test
    fun `jdk and test classes are read`() {
        for (path in listOf("java/lang/String", "java/util/HashMap", "java/util/Optional", "java/lang/Record", "java/lang/Enum")) {
            val facts = ClassFileReader.read(resource("$path.class"))
            assertEquals(path.replace('/', '.'), facts.name)
        }
        val lookup = ClassFileReader.read(resource("java/lang/invoke/MethodHandles${'$'}Lookup.class"))
        assertEquals("java.lang.invoke.MethodHandles", lookup.outer)

        val self = ClassFileReader.read(resource("gg/aquatic/testimpact/graph/internal/classfile/ClassFileReaderTest.class"))
        assertTrue(self.isKotlin)
        assertContains(self.references, "gg.aquatic.testimpact.graph.ClassFacts")
        assertEquals("ClassFileReaderTest.kt", self.sourceFile)
    }
}
