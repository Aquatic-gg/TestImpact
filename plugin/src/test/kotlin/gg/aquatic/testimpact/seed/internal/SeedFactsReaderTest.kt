package gg.aquatic.testimpact.seed.internal

import gg.aquatic.testimpact.graph.internal.classfile.ClassBytes
import gg.aquatic.testimpact.graph.internal.classfile.MalformedClassException
import gg.aquatic.testimpact.seed.ServiceProvision
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SeedFactsReaderTest {

    private fun classCalling(vararg calls: Triple<Int, String, String>): ByteArray {
        val b = ClassBytes()
        val self = b.classRef("p/Main")
        for ((tag, owner, name) in calls) {
            val ownerRef = b.classRef(owner)
            b.member(tag, ownerRef, b.nameAndType(name, "()V"))
        }
        return b.classFile(self)
    }

    private fun method(owner: String, name: String) = Triple(ClassBytes.METHOD_REF, owner, name)

    @Test
    fun `literal shape keeps names and paths and drops descriptors, member names and internal names`() {
        val b = ClassBytes()
        val self = b.classRef("p/Main")
        b.string("p.Target")
        b.string("/gg/res/plugin.yml")
        b.utf8("Lorg.junit.CsvFileSource;")
        b.utf8("data.csv")
        b.utf8("(Lp.A;)V")
        b.utf8("<init>")
        b.utf8("p/Outer\$Inner")
        b.utf8("SMAP\nMain.kt\nKotlin\n*S Kotlin\n*F\n+ 1 Main.kt\np.Main")
        b.utf8("two words.txt")
        b.utf8("arr[0].x")
        b.utf8("gen<T>.x")
        b.utf8("tab\t.x")
        b.utf8(".")
        b.utf8("a.".padEnd(1025, 'x'))
        b.utf8("b.".padEnd(1024, 'x'))

        val facts = SeedFactsReader.read(b.classFile(self))

        assertEquals("p.Main", facts.name)
        assertEquals(
            listOf("/gg/res/plugin.yml", "b.".padEnd(1024, 'x'), "data.csv", "p.Target"),
            facts.literals,
        )
    }

    @Test
    fun `array descriptor literals give the element class and other bracket shapes stay dropped`() {
        val b = ClassBytes()
        val self = b.classRef("p/Main")
        b.string("[La.b.C;")
        b.utf8("[[Lx.Y\$Z;")
        b.utf8("[I")
        b.utf8("[Lnodot;")
        b.utf8("[La.b.C")
        b.utf8("La.b.C;")
        b.utf8("[L;")
        b.utf8("[La.b[0];")
        b.utf8("[La.b.C; ")

        assertEquals(listOf("a.b.C", "x.Y\$Z"), SeedFactsReader.read(b.classFile(self)).literals)
    }

    @Test
    fun `literals are sorted and distinct`() {
        val b = ClassBytes()
        val self = b.classRef("p/Main")
        b.utf8("z.B")
        b.utf8("a.B")
        b.utf8("z.B")

        assertEquals(listOf("a.B", "z.B"), SeedFactsReader.read(b.classFile(self)).literals)
    }

    @Test
    fun `reflection calls mark the class dynamic`() {
        val facts = SeedFactsReader.read(classCalling(method("java/lang/Class", "forName")))

        assertTrue(facts.dynamic)
        assertFalse(facts.loadsResources)
    }

    @Test
    fun `an interface method of a reflection package marks the class dynamic`() {
        val bytes = classCalling(Triple(ClassBytes.INTERFACE_METHOD_REF, "java/lang/reflect/InvocationHandler", "invoke"))

        assertTrue(SeedFactsReader.read(bytes).dynamic)
    }

    @Test
    fun `resource lookups mark the class as a loader`() {
        val facts = SeedFactsReader.read(classCalling(method("java/lang/Class", "getResourceAsStream")))

        assertTrue(facts.loadsResources)
        assertFalse(facts.dynamic)
    }

    @Test
    fun `plain calls and primitive array owners set no flag`() {
        val facts = SeedFactsReader.read(
            classCalling(method("java/util/List", "size"), method("[I", "clone"), method("java/lang/reflect/Array", "newInstance")),
        )

        assertFalse(facts.dynamic)
        assertFalse(facts.loadsResources)
    }

    private fun moduleInfo(truncateBy: Int = 0): ByteArray {
        val b = ClassBytes()
        val self = b.classRef("module-info")
        val moduleName = b.raw(1, 19, 0, b.utf8("app"))
        val requires = b.raw(1, 19, 0, b.utf8("java.base"))
        val pkg = b.raw(1, 20, 0, b.utf8("p/api"))
        val target = b.raw(1, 19, 0, b.utf8("friend"))
        val api = b.classRef("p/api/Api")
        val implB = b.classRef("p/impl/B")
        val implA = b.classRef("p/impl/A")
        val other = b.classRef("p/api/Other")
        val body = ByteArrayOutputStream()
        val out = DataOutputStream(body)
        out.writeShort(moduleName)
        out.writeShort(0)
        out.writeShort(0)
        out.writeShort(1)
        out.writeShort(requires)
        out.writeShort(0)
        out.writeShort(0)
        out.writeShort(1)
        out.writeShort(pkg)
        out.writeShort(0)
        out.writeShort(1)
        out.writeShort(target)
        out.writeShort(1)
        out.writeShort(pkg)
        out.writeShort(0)
        out.writeShort(0)
        out.writeShort(1)
        out.writeShort(other)
        out.writeShort(1)
        out.writeShort(api)
        out.writeShort(2)
        out.writeShort(implB)
        out.writeShort(implA)
        val bytes = body.toByteArray()
        val attribute = b.attribute("Module", bytes.copyOf(bytes.size - truncateBy))
        return b.classFile(self, access = 0x8000, attributes = listOf(attribute))
    }

    @Test
    fun `module descriptor keeps only the provides clauses with sorted providers`() {
        val facts = SeedFactsReader.read(moduleInfo())

        assertEquals("module-info", facts.name)
        assertEquals(listOf(ServiceProvision("p.api.Api", listOf("p.impl.A", "p.impl.B"))), facts.provides)
        assertFalse(facts.dynamic)
    }

    @Test
    fun `a truncated Module attribute is malformed`() {
        assertFailsWith<MalformedClassException> { SeedFactsReader.read(moduleInfo(truncateBy = 3)) }
    }

    @Test
    fun `a module descriptor without a Module attribute provides nothing`() {
        val b = ClassBytes()
        val self = b.classRef("module-info")

        assertEquals(emptyList(), SeedFactsReader.read(b.classFile(self, access = 0x8000)).provides)
    }

    @Test
    fun `bad magic, old versions and truncated input are malformed`() {
        val good = classCalling(method("java/util/List", "size"))
        val badMagic = good.copyOf().also { it[0] = 0 }
        val oldVersion = good.copyOf().also { it[7] = 44 }

        assertFailsWith<MalformedClassException> { SeedFactsReader.read(badMagic) }
        assertFailsWith<MalformedClassException> { SeedFactsReader.read(oldVersion) }
        assertFailsWith<MalformedClassException> { SeedFactsReader.read(good.copyOf(12)) }
        assertFailsWith<MalformedClassException> { SeedFactsReader.read(ByteArray(0)) }
    }
}
