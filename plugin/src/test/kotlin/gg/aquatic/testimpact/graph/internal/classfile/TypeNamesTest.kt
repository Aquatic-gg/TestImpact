package gg.aquatic.testimpact.graph.internal.classfile

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TypeNamesTest {

    private fun descriptor(text: String): List<String> =
        mutableListOf<String>().also { TypeNames.addFromDescriptor(text, it) }

    private fun signature(text: String): List<String> =
        mutableListOf<String>().also { TypeNames.addFromSignature(text, it) }

    @Test
    fun `toBinary swaps slashes for dots and keeps the dollar`() {
        assertEquals("p.q.Outer\$Inner", TypeNames.toBinary("p/q/Outer\$Inner"))
    }

    @Test
    fun `class constants resolve arrays to their element`() {
        assertEquals("p.A", TypeNames.fromClassConstant("p/A"))
        assertEquals("p.A", TypeNames.fromClassConstant("[[Lp/A;"))
        assertEquals(null, TypeNames.fromClassConstant("[I"))
        assertFailsWith<MalformedClassException> { TypeNames.fromClassConstant("") }
        assertFailsWith<MalformedClassException> { TypeNames.fromClassConstant("[Lp/A") }
        assertFailsWith<MalformedClassException> { TypeNames.fromClassConstant("[V") }
    }

    @Test
    fun `descriptors give classes and drop primitives`() {
        assertEquals(listOf("p.A"), descriptor("Lp/A;"))
        assertEquals(listOf("p.A"), descriptor("[[Lp/A;"))
        assertEquals(emptyList(), descriptor("[I"))
        assertEquals(listOf("p.A", "p.B", "p.C"), descriptor("(Lp/A;I[J[Lp/B;)Lp/C;"))
        assertEquals(emptyList(), descriptor("()V"))
    }

    @Test
    fun `generic syntax is rejected in a descriptor`() {
        for (text in listOf("Lp/A<TT;>;", "TT;", "Lp/A;^Lp/B;", "Lp/A.B;", "(*)V")) {
            assertFailsWith<MalformedClassException>(text) { descriptor(text) }
        }
    }

    @Test
    fun `type arguments wildcards and type variables`() {
        assertEquals(
            listOf("java.util.Map", "java.lang.String", "p.A"),
            signature("Ljava/util/Map<Ljava/lang/String;+Lp/A;>;"),
        )
        assertEquals(listOf("java.util.List", "p.Target"), signature("Ljava/util/List<Lp/Target;>;"))
        assertEquals(listOf("java.util.List"), signature("Ljava/util/List<*>;"))
        assertEquals(listOf("java.util.List", "p.A"), signature("Ljava/util/List<-Lp/A;>;"))
        assertEquals(emptyList(), signature("TT;"))
        assertEquals(listOf("java.util.List"), signature("[Ljava/util/List<TT;>;"))
    }

    @Test
    fun `nested type arguments`() {
        assertEquals(
            listOf("java.util.Map", "p.A", "java.util.List", "p.B"),
            signature("Ljava/util/Map<Lp/A;Ljava/util/List<Lp/B;>;>;"),
        )
    }

    @Test
    fun `an inner class suffix becomes the dollar name`() {
        assertEquals(
            listOf("p.Outer", "p.A", "p.Outer\$Inner", "p.B"),
            signature("Lp/Outer<Lp/A;>.Inner<Lp/B;>;"),
        )
        assertEquals(listOf("p.Outer", "p.Outer\$Inner", "p.Outer\$Inner\$Deep"), signature("Lp/Outer.Inner.Deep;"))
    }

    @Test
    fun `class signature with formals bounds superclass and interfaces`() {
        assertEquals(
            listOf("p.Bound", "java.lang.Comparable", "p.Super", "p.Face"),
            signature("<T:Lp/Bound;U::Ljava/lang/Comparable<TU;>;>Lp/Super;Lp/Face;"),
        )
    }

    @Test
    fun `type parameters named like signature letters are identifiers`() {
        assertEquals(
            listOf("java.lang.Object", "java.lang.Object"),
            signature("<L:Ljava/lang/Object;T:TL;>Ljava/lang/Object;"),
        )
    }

    @Test
    fun `method signature with formals parameters return and throws`() {
        assertEquals(
            listOf("p.E", "p.A", "java.util.List", "p.B", "p.C", "p.X"),
            signature("<E:Lp/E;>(Lp/A;Ljava/util/List<Lp/B;>;I)Lp/C;^Lp/X;^TE;"),
        )
    }

    @Test
    fun `malformed signatures throw MalformedClassException`() {
        val malformed = listOf(
            "Lp/A",
            "Lp/A<",
            "Lp/A<Lp/B;",
            "Lp/A<Lp/B;>",
            "Lp/A>;",
            "L;",
            "T;",
            "TT",
            "<T:Lp/A;",
            "<T",
            "<>",
            "Lp/A;.B",
            "Lp/A.;",
            "Q",
            ">",
            "*",
        )
        for (text in malformed) {
            assertFailsWith<MalformedClassException>(text) { signature(text) }
        }
    }

    @Test
    fun `a very deep nesting does not overflow the stack`() {
        val depth = 200_000
        val text = "Lp/A<".repeat(depth) + "Lp/B;" + ">;".repeat(depth)

        assertEquals(depth + 1, signature(text).size)
    }
}
