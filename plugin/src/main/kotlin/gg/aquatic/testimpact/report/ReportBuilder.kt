package gg.aquatic.testimpact.report

import gg.aquatic.testimpact.change.ChangeDetection
import gg.aquatic.testimpact.change.ChangedFile
import gg.aquatic.testimpact.select.SeedSet
import gg.aquatic.testimpact.select.SelectedTest
import gg.aquatic.testimpact.select.Selection

/** Turns the outcome of detection and selection into the report model; free text is scrubbed of absolute paths. */
internal object ReportBuilder {

    /**
     * @param selectionMs milliseconds spent selecting, by task path; a task without an entry counts as 0.
     */
    fun build(
        project: String,
        detection: ChangeDetection,
        seeds: SeedSet,
        selections: List<Selection>,
        detectionMs: Long,
        selectionMs: Map<String, Long>,
        scrubber: PathScrubber,
    ): ImpactReport {
        val tasks = selections.sortedBy { it.taskPath }
            .map { task(it, selectionMs[it.taskPath] ?: 0L, scrubber) }
        val known = tasks.filter { it.result == ImpactReport.Result.SELECTED }
        val selection = tasks.sumOf { it.durationMs }
        return ImpactReport(
            project = project,
            changes = changes(detection, scrubber),
            forced = seeds.forced?.let { ReportRules.rule(it.reason, scrubber.scrub(it.detail)) },
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

    private fun changes(detection: ChangeDetection, scrubber: PathScrubber): ImpactReport.Changes = when (detection) {
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

    private fun task(selection: Selection, durationMs: Long, scrubber: PathScrubber): ImpactReport.Task =
        when (selection) {
            is Selection.Known -> ImpactReport.Task(
                path = selection.taskPath,
                result = ImpactReport.Result.SELECTED,
                rule = null,
                counts = ImpactReport.Counts(selection.tests.size, selection.recognisedCount, selection.candidateCount),
                tests = selection.tests.map(::test),
                durationMs = durationMs,
            )

            is Selection.Unknown -> ImpactReport.Task(
                path = selection.taskPath,
                result = ImpactReport.Result.FULL,
                rule = ReportRules.rule(selection.reason, scrubber.scrub(selection.detail)),
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
            path = path.edges.map { ImpactReport.Step(it.from, it.to, it.kind.name) },
        )
    }
}
