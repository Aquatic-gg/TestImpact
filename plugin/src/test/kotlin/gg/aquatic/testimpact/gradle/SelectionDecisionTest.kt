package gg.aquatic.testimpact.gradle

import gg.aquatic.testimpact.change.ChangeDetection
import gg.aquatic.testimpact.change.ChangeStatus
import gg.aquatic.testimpact.change.ChangedFile
import gg.aquatic.testimpact.change.FileKind
import gg.aquatic.testimpact.change.FileSnapshot
import gg.aquatic.testimpact.change.UnknownReason
import gg.aquatic.testimpact.graph.Edge
import gg.aquatic.testimpact.graph.EdgeKind
import gg.aquatic.testimpact.gradle.internal.SelectionDecision
import gg.aquatic.testimpact.gradle.internal.SelectionSettings
import gg.aquatic.testimpact.gradle.internal.TestImpactMode
import gg.aquatic.testimpact.seed.SeedKind
import gg.aquatic.testimpact.select.ReachPath
import gg.aquatic.testimpact.select.SelectReason
import gg.aquatic.testimpact.select.SelectedTest
import gg.aquatic.testimpact.select.Selection
import gg.aquatic.testimpact.select.SelectionUnknownReason
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SelectionDecisionTest {

    private val settings = SelectionSettings.of(null, 60, 12, emptyList(), emptyList())

    private val known = ChangeDetection.Known(
        baseRef = "main",
        baseCommit = "a",
        mergeBase = "b",
        files = listOf(
            ChangedFile(ChangeStatus.MODIFIED, null, FileSnapshot("src/A.kt", FileKind.SOURCE, listOf("A"))),
            ChangedFile(ChangeStatus.ADDED, null, FileSnapshot("src/B.kt", FileKind.SOURCE, listOf("B"))),
        ),
    )

    private fun test(name: String, recognised: Boolean = true, reason: SelectReason = SelectReason.REACHED) =
        SelectedTest(
            className = name,
            path = ReachPath(
                origin = "src/A.kt",
                start = "A",
                edges = listOf(Edge("T", "A", EdgeKind.REFERENCE), Edge("/x.txt", "T", EdgeKind.REFERENCE)),
                seedKinds = mapOf(1 to SeedKind.RESOURCE_NAME),
            ),
            reason = reason,
            recognised = recognised,
        )

    private fun known(selected: Int, candidates: Int, recognised: Boolean = true) = Selection.Known(
        taskPath = ":test",
        tests = List(selected) { test("t.T$it", recognised) },
        recognisedCount = if (recognised) candidates else 0,
        candidateCount = candidates,
    )

    private fun decide(
        selection: Selection,
        detection: ChangeDetection = known,
        mode: TestImpactMode = TestImpactMode.AUTO,
        userTests: Boolean = false,
    ) = SelectionDecision.decide(selection, detection, settings, mode, userTests, 7)

    @Test
    fun `a partial selection is selected`() {
        val result = decide(known(2, 20))

        assertEquals(TaskOutcome.SELECTED, result.outcome)
        assertNull(result.fullRule)
        assertEquals(listOf("t.T0", "t.T1"), result.selected.map { it.className })
        assertEquals(listOf("src/A.kt", "src/B.kt"), result.changedFiles)
        assertEquals(20, result.candidateCount)
        assertEquals(7, result.analysisMillis)
    }

    @Test
    fun `the entry carries the path and the seed kinds`() {
        val entry = decide(known(1, 20)).selected.single()

        assertEquals(listOf("T>A:REFERENCE", "/x.txt>T:REFERENCE"), entry.edges)
        assertEquals(listOf("1:RESOURCE_NAME"), entry.seedKinds)
        assertEquals("src/A.kt", entry.origin)
        assertEquals("A", entry.start)
    }

    @Test
    fun `mode full runs everything`() {
        val result = decide(known(1, 20), mode = TestImpactMode.FULL)

        assertEquals(TaskOutcome.FULL, result.outcome)
        assertEquals("mode-full", result.fullRule)
    }

    @Test
    fun `user tests run everything`() {
        val result = decide(known(1, 20), userTests = true)

        assertEquals(TaskOutcome.FULL, result.outcome)
        assertEquals("user-tests", result.fullRule)
    }

    @Test
    fun `an unknown selection runs everything with its reason in kebab case`() {
        val result = decide(Selection.Unknown(":test", SelectionUnknownReason.CHANGES_UNKNOWN, "no git"))

        assertEquals(TaskOutcome.FULL, result.outcome)
        assertEquals("changes-unknown", result.fullRule)
        assertEquals("no git", result.fullDetail)
    }

    @Test
    fun `unknown changes leave the changed files null`() {
        val unknown = ChangeDetection.Unknown(UnknownReason.NOT_A_REPOSITORY, "no repo")
        val result = decide(Selection.Unknown(":test", SelectionUnknownReason.CHANGES_UNKNOWN, "no repo"), unknown)

        assertNull(result.changedFiles)
        assertTrue(result.consoleLine().startsWith("changed ?, "))
    }

    @Test
    fun `wide change boundary with 12 candidates and 60 percent`() {
        assertEquals(TaskOutcome.SELECTED, decide(known(7, 12)).outcome)

        val wide = decide(known(8, 12))

        assertEquals(TaskOutcome.FULL, wide.outcome)
        assertEquals("wide-change", wide.fullRule)
        assertEquals(8, wide.selected.size)
    }

    @Test
    fun `fewer candidates than the minimum never trip the wide change rule`() {
        assertEquals(TaskOutcome.SELECTED, decide(known(11, 11)).outcome)
    }

    @Test
    fun `selected classes that are not recognised run everything`() {
        val result = decide(known(2, 20, recognised = false))

        assertEquals(TaskOutcome.FULL, result.outcome)
        assertEquals("not-recognised", result.fullRule)
    }

    @Test
    fun `one recognised class among others is enough`() {
        val selection = Selection.Known(":test", listOf(test("a.A", recognised = false), test("a.B")), 1, 20)

        assertEquals(TaskOutcome.SELECTED, decide(selection).outcome)
    }

    @Test
    fun `nothing selected is empty`() {
        val result = decide(known(0, 20))

        assertEquals(TaskOutcome.EMPTY, result.outcome)
        assertNull(result.fullRule)
    }

    @Test
    fun `an always run class makes the selection non-empty`() {
        val selection = Selection.Known(":test", listOf(test("a.Smoke", reason = SelectReason.ALWAYS_RUN)), 20, 20)

        val result = decide(selection, ChangeDetection.Known("main", "a", "b", emptyList()))

        assertEquals(TaskOutcome.SELECTED, result.outcome)
        assertEquals(SelectReason.ALWAYS_RUN, result.selected.single().reason)
    }

    @Test
    fun `a long detail is capped and kept on one line`() {
        val result = decide(Selection.Unknown(":test", SelectionUnknownReason.ANALYSIS_ERROR, "x\n".repeat(600)))

        assertEquals(TaskSelection.MAX_DETAIL, result.fullDetail?.length)
        assertTrue('\n' !in result.fullDetail.orEmpty())
    }

    @Test
    fun `short circuit covers mode full, mode off and user tests but not auto`() {
        assertEquals("mode-full", SelectionDecision.shortCircuit(":t", TestImpactMode.FULL, false, 0)?.fullRule)
        assertEquals("mode-off", SelectionDecision.shortCircuit(":t", TestImpactMode.OFF, true, 0)?.fullRule)
        assertEquals("user-tests", SelectionDecision.shortCircuit(":t", TestImpactMode.AUTO, true, 0)?.fullRule)
        assertNull(SelectionDecision.shortCircuit(":t", TestImpactMode.AUTO, false, 0))
    }

    @Test
    fun `a plugin error is a full run`() {
        val result = SelectionDecision.pluginError(":t", TestImpactMode.AUTO, "boom", 3)

        assertEquals("plugin-error", result.fullRule)
        assertEquals("changed ?, selected all test classes, full: plugin-error: boom", result.consoleLine())
    }

    @Test
    fun `console line formats`() {
        assertEquals("changed 2, selected 2 of 20 test classes", decide(known(2, 20)).consoleLine())
        assertEquals("changed 2, selected 0 of 20 test classes", decide(known(0, 20)).consoleLine())
        assertEquals(
            "changed 2, selected 12 of 12 test classes, full: wide-change: 8 of 12 test classes are affected, above 60%",
            decide(known(8, 12)).consoleLine(),
        )
        assertEquals(
            "changed 2, selected all test classes, full: mode-full: mode is full",
            decide(known(1, 20), mode = TestImpactMode.FULL).consoleLine(),
        )
    }
}
