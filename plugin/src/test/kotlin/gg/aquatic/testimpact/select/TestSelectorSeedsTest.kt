package gg.aquatic.testimpact.select

import gg.aquatic.testimpact.graph.ClassGraphResult
import gg.aquatic.testimpact.graph.ClassProblem
import gg.aquatic.testimpact.seed.SeedFixtures
import gg.aquatic.testimpact.seed.SeedFixtures.name
import gg.aquatic.testimpact.seed.SeedInputs
import gg.aquatic.testimpact.seed.SeedKind
import gg.aquatic.testimpact.seed.SeedResult
import gg.aquatic.testimpact.seed.SeedRules
import gg.aquatic.testimpact.select.internal.FactsBuilder.graph
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class TestSelectorSeedsTest {

    private val testRoot = Path.of("test-root")
    private val otherRoot = Path.of("other-root")
    private val jupiter = "org.junit.jupiter.engine.JupiterTestEngine"
    private val input = TestTaskInput(
        ":m:test", listOf(testRoot), listOf(otherRoot), TestFrameworkKind.JUNIT_PLATFORM, listOf(jupiter), emptySet(),
    )

    private val scan: (List<Path>) -> ClassGraphResult =
        { roots -> ClassGraphResult.Known(if (roots == listOf(testRoot)) SeedFixtures.classGraph else graph()) }

    private fun select(
        vararg changed: String,
        rules: SeedRules = SeedRules.NONE,
        seedScan: (List<Path>) -> SeedResult = { SeedResult.Known(SeedFixtures.inputs) },
    ): Selection.Known {
        val seeds = SeedSet(if (changed.isEmpty()) emptyList() else listOf(Seed("change.kt", changed.toList(), emptyList())))
        return assertIs<Selection.Known>(TestSelector.select(input, seeds, scan, rules, seedScan))
    }

    private fun Selection.Known.test(className: String): SelectedTest? = tests.singleOrNull { it.className == className }

    @Test
    fun `a class named by a constant selects the test of the holder`() {
        val selected = select(name("reflect.target.ForNameTarget")).test(name("reflect.user.ForNameCheck"))
        assertContains(selected?.path?.seedKinds?.values.orEmpty(), SeedKind.CLASS_NAME)
        assertEquals(SelectReason.REACHED, selected?.reason)
    }

    @Test
    fun `a changed service implementation selects the consumer test`() {
        val selected = select(name("service.impl.LoudGreeter")).test(name("service.consumer.GreeterCheck"))
        assertContains(selected?.path?.seedKinds?.values.orEmpty(), SeedKind.SERVICE)
    }

    @Test
    fun `a change in a listed dynamic package selects the test of a reflector`() {
        val target = name("dyn.ReflectorCheck")
        val rules = SeedRules.of(listOf(name("userdyn")), emptyList())
        val selected = select(name("userdyn.A"), rules = rules).test(target)
        assertContains(selected?.path?.seedKinds?.values.orEmpty(), SeedKind.DYNAMIC)
        assertEquals(null, select(name("userdyn.A")).test(target))
    }

    @Test
    fun `a resource node on the path is named in the edges`() {
        val selected = select(name("res.DescribedMain")).test(name("res.DescriptorLoaderCheck"))
        val path = selected?.path
        assertTrue(path?.edges.orEmpty().any { it.from.startsWith("/") || it.to.startsWith("/") }, "$path")
        assertTrue(path?.seedKinds.orEmpty().isNotEmpty())
    }

    @Test
    fun `alwaysRun selects the matching tests with nothing changed`() {
        val rules = SeedRules.of(emptyList(), listOf("*Check"))
        val result = select(rules = rules)
        assertEquals(10, result.tests.size)
        for (test in result.tests) {
            assertTrue(test.className.endsWith("Check"), test.className)
            assertEquals(SelectReason.ALWAYS_RUN, test.reason)
            assertEquals(ReachPath("alwaysRun", test.className, emptyList()), test.path)
        }
    }

    @Test
    fun `a reached test keeps its reason when it also matches alwaysRun`() {
        val rules = SeedRules.of(emptyList(), listOf("*Check"))
        val selected = select(name("reflect.target.ForNameTarget"), rules = rules).test(name("reflect.user.ForNameCheck"))
        assertEquals(SelectReason.REACHED, selected?.reason)
    }

    @Test
    fun `an unreadable seed scan is graph unknown`() {
        val problem = ClassProblem("res/a.properties", "cannot read")
        val seeds = SeedSet(listOf(Seed("change.kt", listOf(name("reflect.target.ForNameTarget")), emptyList())))
        val result = TestSelector.select(input, seeds, scan, SeedRules.NONE) { SeedResult.Unknown(listOf(problem)) }
        val unknown = assertIs<Selection.Unknown>(result)
        assertEquals(SelectionUnknownReason.GRAPH_UNKNOWN, unknown.reason)
        assertContains(unknown.detail, "res/a.properties")
    }

    @Test
    fun `without rules and with an empty seed scan the class graph decides alone`() {
        val result = select(name("reflect.target.ForNameTarget"), seedScan = { SeedResult.Known(emptyInputs()) })
        assertFalse(name("reflect.user.ForNameCheck") in result.tests.map { it.className })
    }

    private fun emptyInputs() = SeedInputs(emptyList(), emptyList())
}
