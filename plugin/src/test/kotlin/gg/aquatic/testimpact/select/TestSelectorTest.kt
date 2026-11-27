package gg.aquatic.testimpact.select

import gg.aquatic.testimpact.graph.ClassGraphResult
import gg.aquatic.testimpact.graph.ClassProblem
import gg.aquatic.testimpact.graph.DependencyGraph
import gg.aquatic.testimpact.graph.EdgeKind
import gg.aquatic.testimpact.select.internal.EngineCatalog
import gg.aquatic.testimpact.select.internal.FactsBuilder.ABSTRACT
import gg.aquatic.testimpact.select.internal.FactsBuilder.PUBLIC
import gg.aquatic.testimpact.select.internal.FactsBuilder.facts
import gg.aquatic.testimpact.select.internal.FactsBuilder.graph
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class TestSelectorTest {

    private val testRoot = Path.of("test-root")
    private val otherRoot = Path.of("other-root")
    private val jupiter = "org.junit.jupiter.engine.JupiterTestEngine"
    private val vintage = "org.junit.vintage.engine.VintageTestEngine"
    private val kotest = "io.kotest.runner.junit.platform.KotestJunitPlatformTestEngine"
    private val jupiterTest = "org.junit.jupiter.api.Test"

    private fun scanner(tests: DependencyGraph, others: DependencyGraph = graph()): (List<Path>) -> ClassGraphResult =
        { roots -> ClassGraphResult.Known(if (roots == listOf(testRoot)) tests else others) }

    private fun input(
        framework: TestFrameworkKind = TestFrameworkKind.JUNIT_PLATFORM,
        engines: List<String> = listOf(jupiter),
        globals: Set<String> = emptySet(),
    ) = TestTaskInput(":m:test", listOf(testRoot), listOf(otherRoot), framework, engines, globals)

    private fun seedsOf(vararg classes: String) = SeedSet(listOf(Seed("change.kt", classes.toList(), emptyList())))

    private fun unknown(selection: Selection): Selection.Unknown = assertIs<Selection.Unknown>(selection)

    private fun known(selection: Selection): Selection.Known = assertIs<Selection.Known>(selection)

    @Test
    fun `forced seeds are unknown with their own reason`() {
        val forced = SeedSet(emptyList(), Forced(SelectionUnknownReason.CHANGE_NOT_MAPPED, "resource change: a"))
        val result = unknown(TestSelector.select(input(), forced, scanner(graph())))
        assertEquals(SelectionUnknownReason.CHANGE_NOT_MAPPED, result.reason)
        assertEquals("resource change: a", result.detail)
        assertEquals(":m:test", result.taskPath)
    }

    @Test
    fun `testng and other frameworks are unsupported`() {
        for (framework in listOf(TestFrameworkKind.TESTNG, TestFrameworkKind.OTHER)) {
            val result = unknown(TestSelector.select(input(framework), seedsOf("m.Core"), scanner(graph())))
            assertEquals(SelectionUnknownReason.UNSUPPORTED_FRAMEWORK, result.reason)
        }
    }

    @Test
    fun `a platform task without or with an unknown engine is unknown`() {
        val none = unknown(TestSelector.select(input(engines = emptyList()), seedsOf("m.Core"), scanner(graph())))
        assertEquals(SelectionUnknownReason.UNKNOWN_ENGINE, none.reason)
        val other = unknown(TestSelector.select(input(engines = listOf(jupiter, "com.example.FakeEngine")), seedsOf("m.Core"), scanner(graph())))
        assertEquals(SelectionUnknownReason.UNKNOWN_ENGINE, other.reason)
        assertTrue("com.example.FakeEngine" in other.detail)
    }

    @Test
    fun `junit 4 needs no engine`() {
        val tests = graph(facts("t.T", references = listOf("org.junit.Test", "m.Core")))
        val result = known(TestSelector.select(input(TestFrameworkKind.JUNIT4, emptyList()), seedsOf("m.Core"), scanner(tests, graph(facts("m.Core")))))
        assertEquals(listOf("t.T"), result.tests.map { it.className })
    }

    @Test
    fun `jupiter vintage and kotest engines are supported together`() {
        val tests = graph(facts("t.T", references = listOf("m.Core", jupiterTest)), facts("t.U"))
        val others = graph(facts("m.Core"))
        val result = known(
            TestSelector.select(input(engines = listOf(jupiter, vintage, kotest)), seedsOf("m.Core"), scanner(tests, others)),
        )
        assertEquals(listOf("t.T"), result.tests.map { it.className })
        assertEquals(2, result.candidateCount)
        assertEquals(1, result.recognisedCount)
        assertEquals(SelectReason.REACHED, result.tests.single().reason)
        assertTrue(result.tests.single().recognised)
    }

    @Test
    fun `a reached class that no marker recognises is still selected`() {
        val tests = graph(facts("t.Plain", references = listOf("m.Core")))
        val result = known(TestSelector.select(input(), seedsOf("m.Core"), scanner(tests, graph(facts("m.Core")))))
        assertEquals(listOf("t.Plain"), result.tests.map { it.className })
        assertEquals(false, result.tests.single().recognised)
    }

    @Test
    fun `an abstract base in other roots reaches the concrete subclass in the test roots`() {
        val others = graph(facts("m.Base", ABSTRACT or PUBLIC))
        val tests = graph(facts("t.Impl", supertypes = listOf("m.Base")), facts("t.Unrelated"))
        val result = known(TestSelector.select(input(), seedsOf("m.Base"), scanner(tests, others)))
        val selected = result.tests.single()
        assertEquals("t.Impl", selected.className)
        assertEquals(listOf(EdgeKind.SUPERTYPE), selected.path.edges.map { it.kind })
        assertEquals("change.kt", selected.path.origin)
    }

    @Test
    fun `no seeds select nothing`() {
        val result = known(TestSelector.select(input(), SeedSet(emptyList()), scanner(graph(facts("t.T")))))
        assertEquals(emptyList(), result.tests)
        assertEquals(1, result.candidateCount)
    }

    @Test
    fun `an unverified seed unrelated to the graph is dropped`() {
        val tests = graph(facts("t.T", references = listOf("m.Core")))
        val seeds = SeedSet(listOf(Seed("lib/src/test/p/LibTest.kt", listOf("p.LibTest"), listOf("p/LibTest.kt"), true)))
        val result = known(TestSelector.select(input(), seeds, scanner(tests, graph(facts("m.Core")))))
        assertEquals(emptyList(), result.tests)
    }

    @Test
    fun `an unverified seed that the graph knows by source file or class is kept`() {
        val tests = graph(facts("t.T", references = listOf("m.Core")))
        val others = graph(facts("m.Core", sourceFile = "Core.kt"))
        val bySource = SeedSet(listOf(Seed("lib/Core.kt", listOf("m.Core"), listOf("m/Core.kt"), true)))
        assertEquals(listOf("t.T"), known(TestSelector.select(input(), bySource, scanner(tests, others))).tests.map { it.className })
    }

    @Test
    fun `an unverified seed whose source file only exists in a relocated package cannot be mapped`() {
        val others = graph(facts("q.p.Foo", sourceFile = "Foo.kt"))
        val seeds = SeedSet(listOf(Seed("lib/Foo.kt", listOf("p.Foo"), listOf("p/Foo.kt"), true)))
        val result = unknown(TestSelector.select(input(), seeds, scanner(graph(facts("t.T")), others)))
        assertEquals(SelectionUnknownReason.CHANGE_NOT_MAPPED, result.reason)
    }

    @Test
    fun `an unverified seed with only some classes in the graph cannot be mapped`() {
        val others = graph(facts("m.Core"))
        val seeds = SeedSet(listOf(Seed("lib/Core.kt", listOf("m.Core", "m.Gone"), listOf("m/Core.kt"), true)))
        val result = unknown(TestSelector.select(input(), seeds, scanner(graph(facts("t.T")), others)))
        assertEquals(SelectionUnknownReason.CHANGE_NOT_MAPPED, result.reason)
    }

    @Test
    fun `selected tests are sorted by class name`() {
        val tests = graph(
            facts("t.B", references = listOf("m.Core")),
            facts("t.A", references = listOf("m.Core")),
        )
        val result = known(TestSelector.select(input(), seedsOf("m.Core"), scanner(tests, graph(facts("m.Core")))))
        assertEquals(listOf("t.A", "t.B"), result.tests.map { it.className })
    }

    @Test
    fun `a reached global extension selects every test class`() {
        val tests = graph(
            facts("t.Listener", references = listOf("m.Core")),
            facts("t.Direct", references = listOf("m.Core")),
            facts("t.Other"),
        )
        val others = graph(facts("m.Core"))
        val result = known(TestSelector.select(input(globals = setOf("t.Listener")), seedsOf("m.Core"), scanner(tests, others)))
        assertEquals(listOf("t.Direct", "t.Listener", "t.Other"), result.tests.map { it.className })
        assertEquals(SelectReason.GLOBAL, result.tests.single { it.className == "t.Other" }.reason)
        assertEquals("m.Core", result.tests.single { it.className == "t.Other" }.path.start)
    }

    @Test
    fun `a reached kotest project config selects every test class`() {
        val tests = graph(
            facts("t.Config", supertypes = listOf("io.kotest.core.config.AbstractProjectConfig"), references = listOf("m.Core")),
            facts("t.Spec"),
        )
        val result = known(TestSelector.select(input(), seedsOf("m.Core"), scanner(tests, graph(facts("m.Core")))))
        assertEquals(listOf("t.Config", "t.Spec"), result.tests.map { it.className })
    }

    @Test
    fun `a kotest config reached through a base class or a package config selects every test class`() {
        val transitive = graph(
            facts("t.Base", PUBLIC or ABSTRACT, supertypes = listOf("io.kotest.core.config.AbstractProjectConfig")),
            facts("t.Config", supertypes = listOf("t.Base"), references = listOf("m.Core")),
            facts("t.Spec"),
        )
        val viaBase = known(TestSelector.select(input(), seedsOf("m.Core"), scanner(transitive, graph(facts("m.Core")))))
        assertEquals(listOf("t.Config", "t.Spec"), viaBase.tests.map { it.className })

        val pkg = graph(
            facts("t.PkgConfig", supertypes = listOf("io.kotest.core.config.AbstractPackageConfig"), references = listOf("m.Core")),
            facts("t.Spec"),
        )
        val viaPackage = known(TestSelector.select(input(), seedsOf("m.Core"), scanner(pkg, graph(facts("m.Core")))))
        assertEquals(listOf("t.PkgConfig", "t.Spec"), viaPackage.tests.map { it.className })
    }

    @Test
    fun `the launcher interceptor service is a global registration`() {
        assertTrue("org.junit.platform.launcher.LauncherInterceptor" in EngineCatalog.globalServices)
    }

    @Test
    fun `a kotest auto scan class selects every test class`() {
        val tests = graph(
            facts("t.Scanned", references = listOf("io.kotest.core.annotation.AutoScan", "m.Core")),
            facts("t.Spec"),
        )
        val result = known(TestSelector.select(input(), seedsOf("m.Core"), scanner(tests, graph(facts("m.Core")))))
        assertEquals(2, result.tests.size)
    }

    @Test
    fun `an unreached global extension selects nothing extra`() {
        val tests = graph(facts("t.Listener"), facts("t.Spec"))
        val result = known(TestSelector.select(input(globals = setOf("t.Listener")), seedsOf("m.Core"), scanner(tests, graph(facts("m.Core")))))
        assertEquals(emptyList(), result.tests)
    }

    @Test
    fun `a malformed class in a real root is graph unknown`() {
        val dir = createTempDirectory("selector")
        try {
            Files.write(dir.resolve("Bad.class"), byteArrayOf(1, 2, 3))
            val input = TestTaskInput(":m:test", listOf(dir), emptyList(), TestFrameworkKind.JUNIT_PLATFORM, listOf(jupiter))
            val result = unknown(TestSelector.select(input, seedsOf("m.Core")))
            assertEquals(SelectionUnknownReason.GRAPH_UNKNOWN, result.reason)
            assertTrue("Bad.class" in result.detail)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `problem detail is capped at twenty entries`() {
        val problems = (1..25).map { ClassProblem("loc$it", "bad") }
        val result = unknown(TestSelector.select(input(), seedsOf("m.Core")) { ClassGraphResult.Unknown(problems) })
        assertEquals(SelectionUnknownReason.GRAPH_UNKNOWN, result.reason)
        assertTrue("loc20: bad" in result.detail)
        assertTrue("loc21" !in result.detail)
        assertTrue(result.detail.endsWith("and 30 more"))
    }

    @Test
    fun `a throwing scanner is an analysis error`() {
        val result = unknown(TestSelector.select(input(), seedsOf("m.Core")) { error("disk gone") })
        assertEquals(SelectionUnknownReason.ANALYSIS_ERROR, result.reason)
        assertTrue("disk gone" in result.detail)
    }

    @Test
    fun `errors are not swallowed`() {
        assertFailsWith<AssertionError> {
            TestSelector.select(input(), seedsOf("m.Core")) { throw AssertionError("fatal") }
        }
    }

    @Test
    fun `identical inputs give identical results`() {
        val tests = graph(
            facts("t.B", references = listOf("m.Core")),
            facts("t.A", references = listOf("m.Core")),
        )
        val others = graph(facts("m.Core"))
        val first = TestSelector.select(input(), seedsOf("m.Core"), scanner(tests, others))
        val second = TestSelector.select(input(), seedsOf("m.Core"), scanner(tests, others))
        assertEquals(first, second)
    }
}
