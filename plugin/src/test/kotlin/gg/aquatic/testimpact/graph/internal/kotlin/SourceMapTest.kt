package gg.aquatic.testimpact.graph.internal.kotlin

import gg.aquatic.testimpact.graph.internal.classfile.MalformedClassException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SourceMapTest {

    private val stdlibShaped = """
        SMAP
        _Collections.kt
        Kotlin
        *S Kotlin
        *F
        + 1 _Collections.kt
        kotlin/collections/CollectionsKt___CollectionsKt
        + 2 ArraysJVM.kt
        kotlin/collections/ArraysKt__ArraysJVMKt
        + 3 _Arrays.kt
        kotlin/collections/ArraysKt___ArraysKt
        *L
        1#1,100:1
        2123#2,2:101
        *E
    """.trimIndent() + "\n"

    @Test
    fun `every file entry of the F section names an inlined class`() {
        assertEquals(
            setOf(
                "kotlin.collections.CollectionsKt___CollectionsKt",
                "kotlin.collections.ArraysKt__ArraysJVMKt",
                "kotlin.collections.ArraysKt___ArraysKt",
            ),
            SourceMap.inlinedClasses(stdlibShaped),
        )
    }

    @Test
    fun `the result is sorted`() {
        assertEquals(
            listOf(
                "kotlin.collections.ArraysKt___ArraysKt",
                "kotlin.collections.CollectionsKt___CollectionsKt",
                "kotlin.collections.ArraysKt__ArraysJVMKt",
            ).sorted(),
            SourceMap.inlinedClasses(stdlibShaped).toList(),
        )
    }

    @Test
    fun `files of the debug stratum count as well`() {
        val text = """
            SMAP
            Foo.kt
            Kotlin
            *S Kotlin
            *F
            + 1 Foo.kt
            p/Foo
            *L
            1#1,5:1
            *S KotlinDebug
            *F
            + 1 Foo.kt
            p/Foo
            + 2 Bar.kt
            p/Bar${'$'}Inner
            *L
            3#1:1
            *E
        """.trimIndent()
        assertEquals(setOf("p.Foo", "p.Bar${'$'}Inner"), SourceMap.inlinedClasses(text))
    }

    @Test
    fun `carriage returns are tolerated`() {
        val text = stdlibShaped.replace("\n", "\r\n")
        assertEquals(3, SourceMap.inlinedClasses(text).size)
    }

    @Test
    fun `entries without the plus form carry no path and are ignored`() {
        val text = "SMAP\nA.kt\nKotlin\n*S Kotlin\n*F\n1 A.kt\n*L\n*E\n"
        assertTrue(SourceMap.inlinedClasses(text).isEmpty())
    }

    @Test
    fun `a file entry without a path is malformed`() {
        assertFailsWith<MalformedClassException> {
            SourceMap.inlinedClasses("SMAP\nA.kt\nKotlin\n*S Kotlin\n*F\n+ 1 A.kt\n*L\n*E\n")
        }
        assertFailsWith<MalformedClassException> {
            SourceMap.inlinedClasses("SMAP\nA.kt\nKotlin\n*S Kotlin\n*F\n+ 1 A.kt\n+ 2 B.kt\np/B\n*E\n")
        }
        assertFailsWith<MalformedClassException> {
            SourceMap.inlinedClasses("SMAP\nA.kt\nKotlin\n*S Kotlin\n*F\n+ 1 A.kt\n")
        }
    }

    @Test
    fun `a map without the end marker is malformed`() {
        assertFailsWith<MalformedClassException> {
            SourceMap.inlinedClasses("SMAP\nA.kt\nKotlin\n*S Kotlin\n*F\n+ 1 A.kt\np/A\n*L\n1#1:1\n")
        }
    }

    @Test
    fun `text that is not a source map has no entries`() {
        assertTrue(SourceMap.inlinedClasses("hello\n+ 1 A.kt\np/A\n*E").isEmpty())
        assertTrue(SourceMap.inlinedClasses("").isEmpty())
    }
}
