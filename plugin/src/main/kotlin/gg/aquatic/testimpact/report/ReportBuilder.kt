package gg.aquatic.testimpact.report

import gg.aquatic.testimpact.change.ChangeDetection
import gg.aquatic.testimpact.change.ChangedFile
import gg.aquatic.testimpact.gradle.TaskOutcome
import gg.aquatic.testimpact.gradle.internal.SelectionOutcome
import gg.aquatic.testimpact.select.SelectedTest
import gg.aquatic.testimpact.select.Selection

/**
 * Turns the outcomes of the Test tasks' selection runs into the report model; free text is scrubbed of absolute paths.
 * A task's result is the decision of its run, the same one its selection task writes.
 */
internal object ReportBuilder {

    const val NOT_ANALYSED = "NOT_ANALYSED"

    /**
     * The changes are the ones of the first task (by path) that read them: every task of a project sees the same files,
     * only whether a file maps to classes can differ, and that shows in the task's rule. [ImpactReport.forced] is set
     * only when every task that read the changes is forced by the same reason.
     */
    fun build(project: String, outcomes: List<SelectionOutcome>, scrubber: PathScrubber): ImpactReport {
        val sorted = outcomes.sortedBy { it.decision.taskPath }
        val tasks = sorted.map { task(it, scrubber) }
        val known = tasks.filter { it.result == ImpactReport.Result.SELECTED }
        val first = sorted.firstOrNull { it.detection != null }
        val forced = first?.seeds?.forced?.takeIf { shown ->
            sorted.all { outcome -> outcome.seeds.let { it == null || it.forced?.reason == shown.reason } }
        }
        val detectionMs = sorted.sumOf { it.detectionMillis }
        val selection = tasks.sumOf { it.durationMs }
        return ImpactReport(
            project = project,
            changes = changes(first?.detection, outcomes.isEmpty(), scrubber),
            forced = forced?.let { ReportRules.rule(it.reason, scrubber.scrub(it.detail)) },
            tasks = tasks,
            totals = ImpactReport.Totals(
                tasks = tasks.size,
                fullRunTasks = tasks.size - known.size,
                selectedTests = known.sumOf { it.tests.size },
                candidates = known.sumOf { it.counts?.candidates ?: 0 },
            ),
            durations = ImpactReport.Durations(detectionMs, selection, detectionMs + selection),
        )
    }

    private fun changes(
        detection: ChangeDetection?,
        noTasks: Boolean,
        scrubber: PathScrubber,
    ): ImpactReport.Changes = when (detection) {
        null -> ImpactReport.Changes(
            state = ImpactReport.State.UNKNOWN,
            baseRef = null,
            baseCommit = null,
            mergeBase = null,
            reason = NOT_ANALYSED,
            detail = if (noTasks) "the project has no Test task" else "every task was decided before reading the changes",
            files = emptyList(),
        )

        is ChangeDetection.Known -> ImpactReport.Changes(
            state = ImpactReport.State.KNOWN,
            baseRef = detection.baseRef,
            baseCommit = detection.baseCommit,
            mergeBase = detection.mergeBase,
            reason = null,
            detail = null,
            files = detection.files.map(::file),
        )

        is ChangeDetection.Unknown -> ImpactReport.Changes(
            state = ImpactReport.State.UNKNOWN,
            baseRef = null,
            baseCommit = null,
            mergeBase = null,
            reason = detection.reason.name,
            detail = scrubber.scrub(detection.detail),
            files = emptyList(),
        )
    }

    private fun file(changed: ChangedFile) = ImpactReport.File(
        path = changed.path,
        status = changed.status.name,
        kind = (changed.after ?: changed.before)?.kind?.name.orEmpty(),
        classes = changed.reachedClasses,
    )

    private fun task(outcome: SelectionOutcome, scrubber: PathScrubber): ImpactReport.Task {
        val decision = outcome.decision
        val selection = outcome.selection
        val durationMs = (decision.analysisMillis - outcome.detectionMillis).coerceAtLeast(0)
        if (decision.outcome != TaskOutcome.FULL && selection is Selection.Known) {
            return ImpactReport.Task(
                path = decision.taskPath,
                result = ImpactReport.Result.SELECTED,
                rule = null,
                counts = ImpactReport.Counts(selection.tests.size, selection.recognisedCount, selection.candidateCount),
                tests = selection.tests.map(::test),
                durationMs = durationMs,
            )
        }
        val rule = if (selection is Selection.Unknown) {
            ReportRules.rule(selection.reason, scrubber.scrub(selection.detail))
        } else {
            ReportRules.decisionRule(decision.fullRule.orEmpty(), scrubber.scrub(decision.fullDetail.orEmpty()))
        }
        return ImpactReport.Task(
            path = decision.taskPath,
            result = ImpactReport.Result.FULL,
            rule = rule,
            counts = null,
            tests = emptyList(),
            durationMs = durationMs,
        )
    }

    private fun test(selected: SelectedTest): ImpactReport.Test {
        val path = selected.path
        return ImpactReport.Test(
            className = selected.className,
            reason = selected.reason.name,
            recognised = selected.recognised,
            changedFile = path.origin,
            seedClass = path.start,
            reachedClass = path.edges.lastOrNull()?.from ?: path.start,
            path = path.edges.mapIndexed { index, edge ->
                ImpactReport.Step(edge.from, edge.to, edge.kind.name, path.seedKinds[index]?.name)
            },
        )
    }
}
