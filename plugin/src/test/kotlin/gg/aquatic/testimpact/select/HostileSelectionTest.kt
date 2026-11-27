package gg.aquatic.testimpact.select

import gg.aquatic.testimpact.graph.ClassGraphResult
import gg.aquatic.testimpact.graph.ConstantField
import gg.aquatic.testimpact.graph.ReachScope
import gg.aquatic.testimpact.select.internal.FactsBuilder.facts
import gg.aquatic.testimpact.select.internal.FactsBuilder.graph
import java.nio.file.Path
import java.time.Duration
import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import org.junit.jupiter.api.function.ThrowingSupplier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class HostileSelectionTest {

    private val testRoot = Path.of("test-root")
    private val timeout = Duration.ofSeconds(30)

    private fun input() = TestTaskInput(
        ":m:test",
        listOf(testRoot),
        listOf(Path.of("other-root")),
        TestFrameworkKind.JUNIT_PLATFORM,
        listOf("org.junit.jupiter.engine.JupiterTestEngine"),
    )

    private fun select(tests: gg.aquatic.testimpact.graph.DependencyGraph, others: gg.aquatic.testimpact.graph.DependencyGraph, seed: String) =
        assertTimeoutPreemptively(
            timeout,
            ThrowingSupplier {
                TestSelector.select(
                    input(),
                    SeedSet(listOf(Seed("change.kt", listOf(seed), emptyList()))),
                ) { roots -> ClassGraphResult.Known(if (roots == listOf(testRoot)) tests else others) }
            },
        )

    @Test
    fun `twenty thousand public constant definers finish`() {
        val definers = (0 until 20_000).map {
            facts("h.D$it").copy(
                constantScope = ReachScope.ALL,
                constantFields = listOf(ConstantField("X", "I", "1", ReachScope.ALL)),
            )
        }
        val result = select(graph(facts("t.T")), gg.aquatic.testimpact.graph.DependencyGraph.of(definers), "h.D0")
        assertEquals(listOf("t.T"), assertIs<Selection.Known>(result).tests.map { it.className })
    }

    @Test
    fun `twenty thousand package private constant definers with an unreached candidate finish`() {
        val definers = (0 until 20_000).map {
            facts("h.D$it").copy(
                constantScope = ReachScope.PACKAGE,
                constantFields = listOf(ConstantField("X", "I", "1", ReachScope.PACKAGE)),
            )
        }
        val result = select(graph(facts("t.T")), gg.aquatic.testimpact.graph.DependencyGraph.of(definers), "h.D0")
        assertEquals(emptyList(), assertIs<Selection.Known>(result).tests)
    }

    @Test
    fun `twenty thousand typealias definers with an unreached java candidate finish`() {
        val definers = (0 until 20_000).map { i ->
            facts("h.K$i", references = if (i == 0) emptyList() else listOf("h.K${i - 1}"))
                .copy(isKotlin = true, typeAliasScope = ReachScope.ALL)
        }
        val result = select(graph(facts("t.T")), gg.aquatic.testimpact.graph.DependencyGraph.of(definers), "h.K0")
        assertEquals(emptyList(), assertIs<Selection.Known>(result).tests)
    }

    @Test
    fun `a twenty thousand class reference chain finishes without recursion`() {
        val chain = (0 until 20_000).map { i ->
            if (i == 0) facts("h.C0") else facts("h.C$i", references = listOf("h.C${i - 1}"))
        }
        val tests = graph(facts("t.T", references = listOf("h.C19999")))
        val result = select(tests, gg.aquatic.testimpact.graph.DependencyGraph.of(chain), "h.C0")
        val selected = assertIs<Selection.Known>(result).tests.single()
        assertEquals(20_000, selected.path.edges.size)
    }

    @Test
    fun `a twenty thousand class supertype cycle chain finishes`() {
        val chain = (0 until 20_000).map { i -> facts("h.S$i", supertypes = listOf("h.S${(i + 1) % 20_000}")) }
        val result = select(graph(facts("t.T")), gg.aquatic.testimpact.graph.DependencyGraph.of(chain), "h.S0")
        assertEquals(emptyList(), assertIs<Selection.Known>(result).tests)
    }
}
