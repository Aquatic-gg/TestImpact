package gg.aquatic.testimpact.graph.internal.kotlin

import gg.aquatic.testimpact.graph.ReachScope
import kotlin.metadata.KmPackage
import kotlin.metadata.jvm.JvmMetadataVersion
import kotlin.metadata.jvm.KotlinClassMetadata
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class KotlinMetadataTest {

    private fun raw(kind: Int = 1, d2: List<String> = emptyList(), xs: String? = null) =
        RawKotlinMetadata(kind, listOf("\u0000"), d2, xs)

    @Test
    fun `class names come from descriptor and internal name strings`() {
        val metadata = KotlinMetadata.of(
            raw(
                d2 = listOf(
                    "Lkotlin/text/StringBuilder;", "p/Plain", "p/Outer.Inner", "name", "", "has space/x", "p//q",
                    "(Lp/Param;)V", "Lorem ipsum;", "L;", "kotlin/Int",
                ),
            ),
        )
        assertEquals(
            setOf("kotlin.text.StringBuilder", "p.Plain", "p.Outer.Inner", "p.Outer${'$'}Inner", "kotlin.Int"),
            metadata.classNames,
        )
    }

    @Test
    fun `the facade of a multifile part is read from the extra string`() {
        val pkg = KmPackage()
        val written = KotlinClassMetadata.MultiFileClassPart(pkg, "p/Facade", JvmMetadataVersion.LATEST_STABLE_SUPPORTED, 0).write()
        val metadata = KotlinMetadata.of(RawKotlinMetadata(written.kind, written.data1.toList(), written.data2.toList(), written.extraString))

        assertEquals("p.Facade", metadata.facade)
        assertNull(metadata.typeAliasScope)
    }

    @Test
    fun `an extra string means a facade for kind 5 only`() {
        assertNull(KotlinMetadata.of(raw(kind = 2, xs = "p/Other")).facade)
        assertNull(KotlinMetadata.of(raw(kind = 5, xs = "")).facade)
        assertEquals("p.Facade", KotlinMetadata.of(raw(kind = 5, xs = "p.Facade")).facade)
    }

    @Test
    fun `unreadable metadata of a declaring kind is conservative`() {
        assertEquals(ReachScope.ALL, KotlinMetadata.of(RawKotlinMetadata(2, listOf("junk"), emptyList(), null)).typeAliasScope)
    }
}
