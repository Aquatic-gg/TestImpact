package gg.aquatic.testimpact.report

import gg.aquatic.testimpact.change.ChangeDetection
import gg.aquatic.testimpact.change.ChangeStatus
import gg.aquatic.testimpact.change.ChangedFile
import gg.aquatic.testimpact.change.FileKind
import gg.aquatic.testimpact.change.FileSnapshot
import gg.aquatic.testimpact.gradle.internal.SelectionDecision
import gg.aquatic.testimpact.gradle.internal.SelectionOutcome
import gg.aquatic.testimpact.gradle.internal.SelectionSettings
import gg.aquatic.testimpact.gradle.internal.TestImpactMode
import gg.aquatic.testimpact.graph.Edge
import gg.aquatic.testimpact.graph.EdgeKind
import gg.aquatic.testimpact.select.ReachPath
import gg.aquatic.testimpact.select.SeedSet
import gg.aquatic.testimpact.select.SelectReason
import gg.aquatic.testimpact.select.SelectedTest
import gg.aquatic.testimpact.select.Selection

internal object ReportFixtures {

    val scrubber = PathScrubber(emptyList())

    fun changedCore(): ChangeDetection.Known = ChangeDetection.Known(
        baseRef = "main",
        baseCommit = "0123456789abcdef",
        mergeBase = "fedcba9876543210",
        files = listOf(
            ChangedFile(
                ChangeStatus.MODIFIED,
                FileSnapshot("a/src/main/java/a/Core.java", FileKind.SOURCE, listOf("a.Core")),
                FileSnapshot("a/src/main/java/a/Core.java", FileKind.SOURCE, listOf("a.Core")),
            ),
        ),
    )

    fun reachedTest(): SelectedTest = SelectedTest(
        className = "c.ServiceTest",
        path = ReachPath(
            origin = "a/src/main/java/a/Core.java",
            start = "a.Core",
            edges = listOf(
                Edge("c.Service", "a.Core", EdgeKind.REFERENCE),
                Edge("c.ServiceTest\$Inner", "c.Service", EdgeKind.REFERENCE),
            ),
        ),
        reason = SelectReason.REACHED,
        recognised = true,
    )

    fun globalTest(): SelectedTest = SelectedTest(
        className = "c.OtherTest",
        path = ReachPath("a/src/main/java/a/Core.java", "a.Core", listOf(Edge("c.Ext", "a.Core", EdgeKind.SUPERTYPE))),
        reason = SelectReason.GLOBAL,
        recognised = false,
    )

    fun known(taskPath: String, tests: List<SelectedTest>, candidates: Int = 2) =
        Selection.Known(taskPath, tests, recognisedCount = candidates, candidateCount = candidates)

    /**
     * The report of runs that all read [detection] and [seeds], decided with the default settings in mode `auto`. The
     * first task (by path) carries [detectionMs], so the report's detection time equals it.
     */
    fun report(
        selections: List<Selection>,
        detection: ChangeDetection = changedCore(),
        seeds: SeedSet = SeedSet(emptyList()),
        detectionMs: Long = 0,
        selectionMs: Map<String, Long> = emptyMap(),
        scrubber: PathScrubber = this.scrubber,
        project: String = ":c",
        settings: SelectionSettings = SelectionSettings.of(null, 60, 12, emptySet(), emptySet()),
    ): ImpactReport {
        val first = selections.minOfOrNull { it.taskPath }
        val outcomes = selections.map { selection ->
            val detected = if (selection.taskPath == first) detectionMs else 0L
            val millis = detected + (selectionMs[selection.taskPath] ?: 0L)
            val decision = SelectionDecision.decide(selection, detection, settings, TestImpactMode.AUTO, false, millis)
            SelectionOutcome(decision, detection, seeds, selection, detected)
        }
        return ReportBuilder.build(project, outcomes, scrubber)
    }
}
