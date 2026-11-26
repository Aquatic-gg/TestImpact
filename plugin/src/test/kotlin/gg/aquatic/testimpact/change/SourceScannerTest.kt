package gg.aquatic.testimpact.change

import gg.aquatic.testimpact.change.internal.SourceDeclarations
import gg.aquatic.testimpact.change.internal.SourceScanner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class SourceScannerTest {

    private fun scan(fileName: String, text: String): SourceDeclarations = SourceScanner.scan(fileName, text)

    private fun kotlin(text: String, fileName: String = "Sample.kt") = scan(fileName, text)

    private fun java(text: String, fileName: String = "Sample.java") = scan(fileName, text)

    @Test
    fun kotlinTypeKinds() {
        val declarations = kotlin(
            """
            package a.b

            class C
            object O
            interface I
            enum class E { X }
            annotation class An
            @JvmInline value class V(val x: Int)
            fun interface F { fun run() }
            sealed interface S
            data class D(val x: Int)
            data object DO
            """.trimIndent(),
        )
        assertEquals("a.b", declarations.packageName)
        assertEquals(listOf("C", "O", "I", "E", "An", "V", "F", "S", "D", "DO"), declarations.typeNames)
        assertNull(declarations.facade)
        assertEquals(listOf("a.b.An", "a.b.C", "a.b.D", "a.b.DO", "a.b.E", "a.b.F", "a.b.I", "a.b.O", "a.b.S", "a.b.V"), declarations.binaryNames)
    }

    @Test
    fun nestedTypesBelongToTheirOuterType() {
        val declarations = kotlin("package p\nclass Outer {\n  class Inner\n  companion object { class Deep }\n  object Obj\n}\n")
        assertEquals(listOf("Outer"), declarations.typeNames)
    }

    @Test
    fun topLevelCallablesProduceAFacade() {
        assertEquals("SampleKt", kotlin("package p\nfun helper() = 1\n").facade)
        assertEquals("SampleKt", kotlin("package p\nval answer = 42\n").facade)
        assertEquals("SampleKt", kotlin("package p\nconst val ANSWER = 42\nclass K\n").facade)
        assertEquals(listOf("p.K", "p.SampleKt"), kotlin("package p\nvar x = 1\nclass K\n").binaryNames)
        assertNull(kotlin("package p\nclass OnlyAType\nfun interface F { fun run() }\n").facade)
    }

    @Test
    fun facadeNameFollowsTheFileName() {
        assertEquals("UtilKt", kotlin("fun f() {}", "util.kt").facade)
        assertEquals("My_utilKt", kotlin("fun f() {}", "my-util.kt").facade)
        assertEquals("UtilKt", kotlin("fun f() {}", "src/main/kotlin/p/util.kt").facade)
    }

    @Test
    fun fileJvmNameOverridesTheFacade() {
        assertEquals("Custom", kotlin("@file:JvmName(\"Custom\")\npackage p\nfun f() {}\n").facade)
        assertEquals("Custom", kotlin("@file:kotlin.jvm.JvmName(name = \"Custom\")\npackage p\nfun f() {}\n").facade)
        assertEquals("SampleKt", kotlin("@file:Suppress(\"unused\")\npackage p\nfun f() {}\n").facade)
        assertNull(kotlin("@file:JvmName(\"Custom\")\npackage p\nclass OnlyAType\n").facade)
    }

    @Test
    fun classReferencesAndAnonymousObjectsAreNotDeclarations() {
        val declarations = kotlin(
            """
            package p
            val a = Foo::class
            val b = Foo::class.java
            val c = object : Runnable { override fun run() {} }
            fun d() = object { val x = 1 }
            """.trimIndent(),
        )
        assertEquals(emptyList(), declarations.typeNames)
        assertEquals("SampleKt", declarations.facade)
    }

    @Test
    fun javaTypeKinds() {
        val declarations = java(
            """
            package a.b;

            import java.util.List;

            @Deprecated
            public class Main<T extends Comparable<T>> {
                class Inner {}
                void use() { Class<?> c = Main.class; }
            }

            interface Helper {}
            record Point(int x, int y) {}
            @interface Marker {}
            enum Color { RED }
            """.trimIndent(),
        )
        assertEquals("a.b", declarations.packageName)
        assertEquals(listOf("Main", "Helper", "Point", "Marker", "Color"), declarations.typeNames)
        assertNull(declarations.facade)
    }

    @Test
    fun javaIdentifiersNamedLikeKeywordsAreNotTypes() {
        val declarations = java("class A {\n  void f() { int record = 1; String object = null; }\n}\n")
        assertEquals(listOf("A"), declarations.typeNames)
        assertNull(java("class A {}\nclass B { Object enum_ = null; }\n").facade)
    }

    @Test
    fun defaultPackage() {
        val declarations = kotlin("class Foo\nfun f() {}\n")
        assertEquals("", declarations.packageName)
        assertEquals(listOf("Foo", "SampleKt"), declarations.binaryNames)
    }

    @Test
    fun bracesInsideLiteralsAndCommentsAreIgnored() {
        val source = """
            package p
            val s = "}}} class Fake"
            val r = ${"\"\"\""}
              } class Fake2 {
            ${"\"\"\""}
            val t = "a ~{ "}" } b ~{ mapOf("k" to '}') } c"
            val u = "~{ list.map { it } } class Fake4"
            val ch = '{'
            val q = '\''
            val esc = "\" class Fake5 {"
            /* outer /* inner } class Fake6 */ still } class Fake7 */
            // class Fake8 {
            class Real
        """.trimIndent().replace('~', '$')
        assertEquals(listOf("Real"), kotlin(source).typeNames)
    }

    @Test
    fun javaTextBlocksAndNonNestingComments() {
        val declarations = java(
            "package p;\nclass A { String s = \"\"\"\n  } class Fake {\n  \\\"\"\" class Fake2 {\n\"\"\"; }\n" +
                "/* /* */ class Real {}\n",
        )
        assertEquals(listOf("A", "Real"), declarations.typeNames)
    }

    @Test
    fun backtickedNames() {
        val declarations = kotlin("package p\nclass `My Class`\nval `class` = 1\nfun `interface`() {}\nfun `fun interface`() {}\n")
        assertEquals(listOf("My Class"), declarations.typeNames)
        assertEquals("SampleKt", declarations.facade)
    }

    @Test
    fun lineEndingsAndByteOrderMarkDoNotMatter() {
        val text = "package p\n// c\nclass A {\n}\nobject B\nfun f() {}\n"
        val expected = kotlin(text)
        assertEquals(expected, kotlin(text.replace("\n", "\r\n")))
        assertEquals(expected, kotlin(text.replace("\n", "\r")))
        assertEquals(expected, kotlin("﻿$text"))
        assertEquals(expected, kotlin("﻿" + text.replace("\n", "\r\n")))
    }

    @Test
    fun unterminatedInputTerminates() {
        assertEquals(listOf("A"), kotlin("class A\nval s = \"abc").typeNames)
        assertEquals(listOf("A"), kotlin("class A\n/* never closed class B").typeNames)
        assertEquals(listOf("A"), kotlin("class A\nval s = \"\"\"never closed class B").typeNames)
        assertEquals(listOf("A"), kotlin("class A\nval s = \"~{ never closed class B".replace('~', '$')).typeNames)
        assertEquals(listOf("A"), kotlin("class A\nval c = '").typeNames)
        assertEquals(listOf("A"), kotlin("class A\nval s = \"\\").typeNames)
        assertEquals(emptyList(), kotlin("}}}}{{{{)))(((").typeNames)
    }

    @Test
    fun excessiveNestingIsRejected() {
        val templates = "val s = " + "\"~{".repeat(500) + "x" + "}\"".repeat(500)
        assertFailsWith<IllegalArgumentException> { kotlin(templates.replace('~', '$')) }
        assertFailsWith<IllegalArgumentException> { kotlin("/*".repeat(500)) }
    }
}
