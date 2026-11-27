package gg.aquatic.testimpact.report

import gg.aquatic.testimpact.change.ChangeDetection
import gg.aquatic.testimpact.change.ChangeStatus
import gg.aquatic.testimpact.change.ChangedFile
import gg.aquatic.testimpact.change.FileKind
import gg.aquatic.testimpact.change.FileSnapshot
import gg.aquatic.testimpact.graph.Edge
import gg.aquatic.testimpact.graph.EdgeKind
import gg.aquatic.testimpact.select.ReachPath
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
}
