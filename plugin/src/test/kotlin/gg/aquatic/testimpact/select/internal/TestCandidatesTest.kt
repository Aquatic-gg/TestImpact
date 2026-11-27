package gg.aquatic.testimpact.select.internal

import gg.aquatic.testimpact.select.internal.FactsBuilder.ABSTRACT
import gg.aquatic.testimpact.select.internal.FactsBuilder.ANNOTATION
import gg.aquatic.testimpact.select.internal.FactsBuilder.ENUM
import gg.aquatic.testimpact.select.internal.FactsBuilder.INTERFACE
import gg.aquatic.testimpact.select.internal.FactsBuilder.PUBLIC
import gg.aquatic.testimpact.select.internal.FactsBuilder.facts
import gg.aquatic.testimpact.select.internal.FactsBuilder.graph
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TestCandidatesTest {

    private val jupiterTest = "org.junit.jupiter.api.Test"

    private fun find(graph: gg.aquatic.testimpact.graph.DependencyGraph, vararg roots: String) =
        TestCandidates.find(graph, roots.toList())

    @Test
    fun `abstract interface enum and annotation are not candidates`() {
        val graph = graph(
            facts("t.Abstract", ABSTRACT or PUBLIC),
            facts("t.Iface", INTERFACE or ABSTRACT or PUBLIC),
            facts("t.Kind", ENUM or PUBLIC),
            facts("t.Marker", ANNOTATION or INTERFACE or ABSTRACT or PUBLIC),
            facts("t.Plain"),
        )
        assertEquals(listOf("t.Plain"), find(graph, "t.Abstract", "t.Iface", "t.Kind", "t.Marker", "t.Plain").names)
    }

    @Test
    fun `classes outside the test roots are not candidates`() {
        val graph = graph(facts("t.InTests"), facts("m.Main"))
        assertEquals(listOf("t.InTests"), find(graph, "t.InTests").names)
    }

    @Test
    fun `a class without markers is a candidate but not recognised`() {
        val candidates = find(graph(facts("t.Plain")), "t.Plain")
        assertEquals(listOf("t.Plain"), candidates.names)
        assertFalse(candidates.isRecognised("t.Plain"))
        assertEquals(0, candidates.recognisedCount)
    }

    @Test
    fun `a direct marker recognises the class`() {
        val candidates = find(graph(facts("t.A", references = listOf(jupiterTest))), "t.A")
        assertTrue(candidates.isRecognised("t.A"))
    }

    @Test
    fun `a test inherited through an abstract base is recognised on the subclass`() {
        val graph = graph(
            facts("m.Base", ABSTRACT or PUBLIC, references = listOf(jupiterTest)),
            facts("t.Sub", supertypes = listOf("m.Base")),
        )
        val candidates = find(graph, "t.Sub")
        assertEquals(listOf("t.Sub"), candidates.names)
        assertTrue(candidates.isRecognised("t.Sub"))
    }

    @Test
    fun `a test inherited through an interface default is recognised`() {
        val graph = graph(
            facts("m.Contract", INTERFACE or ABSTRACT or PUBLIC, references = listOf(jupiterTest)),
            facts("t.Impl", supertypes = listOf("m.Contract")),
        )
        assertTrue(find(graph, "t.Impl").isRecognised("t.Impl"))
    }

    @Test
    fun `a composed annotation recognises the class even through two levels`() {
        val graph = graph(
            facts("m.Fast", ANNOTATION or INTERFACE or ABSTRACT or PUBLIC, references = listOf("org.junit.jupiter.params.ParameterizedTest")),
            facts("m.Faster", ANNOTATION or INTERFACE or ABSTRACT or PUBLIC, references = listOf("m.Fast")),
            facts("t.A", references = listOf("m.Faster")),
            facts("t.B", references = listOf("m.Unrelated")),
        )
        val candidates = find(graph, "t.A", "t.B")
        assertTrue(candidates.isRecognised("t.A"))
        assertFalse(candidates.isRecognised("t.B"))
    }

    @Test
    fun `vintage and junit 3 markers recognise`() {
        val graph = graph(
            facts("t.Runner", references = listOf("org.junit.runner.RunWith")),
            facts("t.Old", supertypes = listOf("junit.framework.TestCase")),
            facts("t.Four", references = listOf("org.junit.Test")),
        )
        val candidates = find(graph, "t.Runner", "t.Old", "t.Four")
        assertEquals(3, candidates.recognisedCount)
    }

    @Test
    fun `kotest spec through an own abstract base is recognised`() {
        val graph = graph(
            facts("m.BaseSpec", ABSTRACT or PUBLIC, supertypes = listOf("io.kotest.core.spec.style.FunSpec")),
            facts("t.MySpec", supertypes = listOf("m.BaseSpec")),
        )
        assertTrue(find(graph, "t.MySpec").isRecognised("t.MySpec"))
    }

    @Test
    fun `nested and anonymous classes fold into the top level owner`() {
        val graph = graph(
            facts("t.Outer"),
            facts("t.Outer\$Inner", outer = "t.Outer", references = listOf(jupiterTest)),
            facts("t.Outer\$1", outer = "t.Outer"),
        )
        val candidates = find(graph, "t.Outer", "t.Outer\$Inner", "t.Outer\$1")
        assertEquals(listOf("t.Outer"), candidates.names)
        assertTrue(candidates.isRecognised("t.Outer"))
    }

    @Test
    fun `supertype cycles terminate`() {
        val graph = graph(
            facts("t.A", supertypes = listOf("t.B")),
            facts("t.B", supertypes = listOf("t.A"), references = listOf(jupiterTest)),
        )
        val candidates = find(graph, "t.A", "t.B")
        assertEquals(2, candidates.recognisedCount)
    }
}
