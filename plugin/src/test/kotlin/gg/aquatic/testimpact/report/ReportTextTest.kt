package gg.aquatic.testimpact.report

import gg.aquatic.testimpact.change.ChangeDetection
import gg.aquatic.testimpact.change.ChangeStatus
import gg.aquatic.testimpact.change.ChangedFile
import gg.aquatic.testimpact.change.FileKind
import gg.aquatic.testimpact.change.FileSnapshot
import gg.aquatic.testimpact.change.UnknownReason
import gg.aquatic.testimpact.select.Selection
import gg.aquatic.testimpact.select.SelectionUnknownReason
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReportTextTest {

    private fun build(detection: ChangeDetection, selections: List<Selection>) =
        ReportFixtures.report(selections, detection, detectionMs = 3, selectionMs = mapOf(":c:test" to 12L))

    @Test
    fun `known changes with a selected and a full task`() {
        val report = build(
            ReportFixtures.changedCore(),
            listOf(
                ReportFixtures.known(":c:test", listOf(ReportFixtures.reachedTest(), ReportFixtures.globalTest()), candidates = 2),
                Selection.Unknown(":f:test", SelectionUnknownReason.UNKNOWN_ENGINE, "f.FakeEngine"),
            ),
        )

        val expected = """
            base main 0123456 merge-base fedcba9: 1 changed files
              a/src/main/java/a/Core.java
            :c:test selected 2 of 2 test classes (2 recognised) in 12 ms
              c.ServiceTest <- a/src/main/java/a/Core.java: a.Core <-REFERENCE- c.Service <-REFERENCE- c.ServiceTest${'$'}Inner
              c.OtherTest <- a/src/main/java/a/Core.java: a.Core <-SUPERTYPE- c.Ext (a reached class registers a global extension: c.Ext; not recognised as a test class)
            :f:test full run: a JUnit Platform engine is missing or not supported (UNKNOWN_ENGINE): f.FakeEngine
            analysis 15 ms (detection 3 ms, selection 12 ms)
        """.trimIndent() + "\n"
        assertEquals(expected, ReportText.render(report))
    }

    @Test
    fun `unknown changes name the reason and detail`() {
        val unknown = Selection.Unknown(":c:test", SelectionUnknownReason.CHANGES_UNKNOWN, "HEAD is detached")
        val report = build(ChangeDetection.Unknown(UnknownReason.DETACHED_HEAD, "HEAD is detached"), listOf(unknown))

        assertEquals(
            "changes unknown: DETACHED_HEAD: HEAD is detached\n" +
                ":c:test full run: changes could not be determined (CHANGES_UNKNOWN): HEAD is detached\n" +
                "analysis 15 ms (detection 3 ms, selection 12 ms)\n",
            ReportText.render(report),
        )
    }

    @Test
    fun `changed files are capped at fifty`() {
        val files = (1..53).map {
            val snapshot = FileSnapshot("f/%03d.txt".format(it), FileKind.OTHER)
            ChangedFile(ChangeStatus.MODIFIED, snapshot, snapshot)
        }
        val detection = ChangeDetection.Known("main", "a".repeat(40), "b".repeat(40), files)

        val lines = ReportText.render(build(detection, listOf(ReportFixtures.known(":c:test", emptyList())))).lines()

        assertEquals("base main aaaaaaa merge-base bbbbbbb: 53 changed files", lines.first())
        assertEquals(50, lines.count { it.startsWith("  f/") })
        assertTrue("  and 3 more" in lines)
    }

    @Test
    fun `details with line breaks stay on one line`() {
        val report = build(
            ReportFixtures.changedCore(),
            listOf(Selection.Unknown(":c:test", SelectionUnknownReason.ANALYSIS_ERROR, "first\nsecond\r\nthird")),
        )

        assertTrue(":c:test full run: the analysis failed (ANALYSIS_ERROR): first second  third" in ReportText.render(report).lines())
    }
}
