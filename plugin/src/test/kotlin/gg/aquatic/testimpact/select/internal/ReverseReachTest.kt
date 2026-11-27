package gg.aquatic.testimpact.select.internal

import gg.aquatic.testimpact.graph.Edge
import gg.aquatic.testimpact.graph.EdgeKind
import gg.aquatic.testimpact.select.Seed
import gg.aquatic.testimpact.select.internal.FactsBuilder.ABSTRACT
import gg.aquatic.testimpact.select.internal.FactsBuilder.PUBLIC
import gg.aquatic.testimpact.select.internal.FactsBuilder.facts
import gg.aquatic.testimpact.select.internal.FactsBuilder.graph
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ReverseReachTest {

    private fun seed(vararg classes: String, keys: List<String> = emptyList()) = Seed("change.kt", classes.toList(), keys)

    @Test
    fun `path runs from the seed outward with edge kinds`() {
        val graph = graph(
            facts("m.Core"),
            facts("m.Service", references = listOf("m.Core")),
            facts("t.ServiceTest", references = listOf("m.Service")),
        )
        val result = ReverseReach.reach(graph, listOf(seed("m.Core")), setOf("t.ServiceTest"))
        val path = result.pathTo("t.ServiceTest")
        assertEquals("change.kt", path?.origin)
        assertEquals("m.Core", path?.start)
        assertEquals(
            listOf(
                Edge("m.Service", "m.Core", EdgeKind.REFERENCE),
                Edge("t.ServiceTest", "m.Service", EdgeKind.REFERENCE),
            ),
            path?.edges,
        )
    }

    @Test
    fun `a changed supertype reaches the subclass with a supertype edge`() {
        val graph = graph(
            facts("m.Base", ABSTRACT or PUBLIC),
            facts("t.SubTest", supertypes = listOf("m.Base")),
        )
        val result = ReverseReach.reach(graph, listOf(seed("m.Base")), setOf("t.SubTest"))
        assertEquals(listOf(EdgeKind.SUPERTYPE), result.pathTo("t.SubTest")?.edges?.map { it.kind })
    }

    @Test
    fun `equal length alternatives pick the same path every time`() {
        val graph = graph(
            facts("m.Core"),
            facts("m.B", references = listOf("m.Core")),
            facts("m.A", references = listOf("m.Core")),
            facts("t.T", references = listOf("m.A", "m.B")),
        )
        val paths = (1..3).map {
            ReverseReach.reach(graph, listOf(seed("m.Core")), setOf("t.T")).pathTo("t.T")?.edges?.map { it.from }
        }
        assertEquals(listOf("m.A", "t.T"), paths.first())
        assertEquals(1, paths.toSet().size)
    }

    @Test
    fun `a seed class that is itself a candidate is selected with an empty path`() {
        val graph = graph(facts("t.T"))
        val path = ReverseReach.reach(graph, listOf(seed("t.T")), setOf("t.T")).pathTo("t.T")
        assertEquals(emptyList(), path?.edges)
        assertEquals("t.T", path?.start)
    }

    @Test
    fun `a nested seed class is covered through its owner`() {
        val graph = graph(
            facts("m.Outer"),
            facts("m.Outer\$Inner", outer = "m.Outer"),
            facts("t.T", references = listOf("m.Outer\$Inner")),
        )
        val result = ReverseReach.reach(graph, listOf(seed("m.Outer")), setOf("t.T"))
        assertEquals(listOf("t.T"), result.selected)
        assertEquals("m.Outer\$Inner", result.pathTo("t.T")?.start)
    }

    @Test
    fun `a facade class is reached through the source key`() {
        val graph = graph(
            facts("m.Util", sourceFile = "Util.kt"),
            facts("m.UtilKt", sourceFile = "Util.kt"),
            facts("t.T", references = listOf("m.UtilKt")),
        )
        val byName = ReverseReach.reach(graph, listOf(seed("m.Util")), setOf("t.T"))
        assertEquals(emptyList(), byName.selected)
        val byKey = ReverseReach.reach(graph, listOf(seed("m.Util", keys = listOf("m/Util.kt"))), setOf("t.T"))
        assertEquals(listOf("t.T"), byKey.selected)
    }

    @Test
    fun `a class reached only through a nested class selects the top level class`() {
        val graph = graph(
            facts("m.Core"),
            facts("t.T"),
            facts("t.T\$1", outer = "t.T", references = listOf("m.Core")),
        )
        val result = ReverseReach.reach(graph, listOf(seed("m.Core")), setOf("t.T"))
        assertEquals(listOf("t.T"), result.selected)
        assertEquals("t.T\$1", result.pathTo("t.T")?.edges?.last()?.from)
    }

    @Test
    fun `reference cycles end`() {
        val graph = graph(
            facts("m.A", references = listOf("m.B")),
            facts("m.B", references = listOf("m.A")),
            facts("t.T", references = listOf("m.A")),
            facts("t.Other"),
        )
        val result = ReverseReach.reach(graph, listOf(seed("m.A")), setOf("t.T", "t.Other"))
        assertEquals(listOf("t.T"), result.selected)
        assertNull(result.pathTo("t.Other"))
        assertNotNull(result.pathToClass("m.B"))
        assertNull(result.pathToClass("t.Other"))
    }

    @Test
    fun `an unscanned seed selects nothing`() {
        val graph = graph(facts("t.T"))
        assertEquals(emptyList(), ReverseReach.reach(graph, listOf(seed("nope.X")), setOf("t.T")).selected)
    }

    @Test
    fun `the origin follows the seed that reached the class`() {
        val graph = graph(
            facts("m.A"),
            facts("m.B"),
            facts("t.FromB", references = listOf("m.B")),
        )
        val seeds = listOf(Seed("a.kt", listOf("m.A"), emptyList()), Seed("b.kt", listOf("m.B"), emptyList()))
        assertEquals("b.kt", ReverseReach.reach(graph, seeds, setOf("t.FromB")).pathTo("t.FromB")?.origin)
    }
}
