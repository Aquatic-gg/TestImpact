package gg.aquatic.testimpact.report

import gg.aquatic.testimpact.select.SeedSet
import gg.aquatic.testimpact.select.Selection
import gg.aquatic.testimpact.select.SelectionUnknownReason
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ExplainTest {

    private val report = ReportBuilder.build(
        ":c",
        ReportFixtures.changedCore(),
        SeedSet(emptyList()),
        listOf(
            ReportFixtures.known(":c:test", listOf(ReportFixtures.reachedTest())),
            ReportFixtures.known(":c:integrationTest", emptyList()),
            Selection.Unknown(":c:slowTest", SelectionUnknownReason.GRAPH_UNKNOWN, "x.jar: unreadable"),
        ),
        1,
        emptyMap(),
        ReportFixtures.scrubber,
    )

    @Test
    fun `selected full and not selected per task`() {
        val lines = Explain.explain(report, "c.ServiceTest")

        assertEquals("explain c.ServiceTest in :c", lines[0])
        assertEquals(
            ":c:integrationTest: not selected: no changed file reaches it, or it is not a test class of :c:integrationTest",
            lines[1],
        )
        assertEquals(
            ":c:slowTest: full run, so c.ServiceTest runs: a class file or archive could not be read (GRAPH_UNKNOWN): x.jar: unreadable",
            lines[2],
        )
        assertEquals(
            ":c:test: selected: c.ServiceTest <- a/src/main/java/a/Core.java: " +
                "a.Core <-REFERENCE- c.Service <-REFERENCE- c.ServiceTest\$Inner",
            lines[3],
        )
    }

    @Test
    fun `a nested class is explained by its top level class`() {
        val lines = Explain.explain(report, "c.ServiceTest\$Inner")

        assertTrue("top-level class c.ServiceTest" in lines[0])
        assertTrue(lines.any { it.startsWith(":c:test: selected: c.ServiceTest <-") })
    }

    @Test
    fun `a project without test tasks says so`() {
        val empty = report.copy(tasks = emptyList())

        assertEquals(listOf("explain c.Foo in :c", ":c has no Test tasks"), Explain.explain(empty, "c.Foo"))
    }

    @Test
    fun `refused names name the option`() {
        val refused = listOf("", "-x", "a b", "x".repeat(513), "a..b", ".a", "a.", "1a", "a;b", "a\nb")
        for (name in refused) {
            val failure = assertFailsWith<IllegalArgumentException>("name <$name>") { Explain.validate(name) }
            assertTrue("--explain" in failure.message.orEmpty())
        }
    }

    @Test
    fun `accepted names`() {
        for (name in listOf("A", "a.b.C", "a.b.C\$D", "_a.\$b", "pkg.Tëst", "x".repeat(512))) {
            assertEquals(name, Explain.validate(name))
        }
    }
}
