package gg.aquatic.testimpact.report

import gg.aquatic.testimpact.report.internal.JsonWriter

/**
 * `report.json`, schema [REPORT_SCHEMA_VERSION]. Every key is always present (null when it does not apply) and in a
 * fixed order; only the `durationsMs` objects differ between two runs on the same tree.
 */
internal object ReportJson {

    fun render(report: ImpactReport): String = JsonWriter.write(
        linkedMapOf(
            "schemaVersion" to REPORT_SCHEMA_VERSION,
            "project" to report.project,
            "changes" to changes(report.changes),
            "forced" to report.forced?.let(::rule),
            "tasks" to report.tasks.map(::task),
            "totals" to linkedMapOf(
                "tasks" to report.totals.tasks,
                "fullRunTasks" to report.totals.fullRunTasks,
                "selectedTests" to report.totals.selectedTests,
                "candidates" to report.totals.candidates,
            ),
            "durationsMs" to linkedMapOf(
                "detection" to report.durations.detectionMs,
                "selection" to report.durations.selectionMs,
                "total" to report.durations.totalMs,
            ),
        ),
    )

    private fun changes(changes: ImpactReport.Changes) = linkedMapOf(
        "state" to changes.state.name.lowercase(),
        "baseRef" to changes.baseRef,
        "baseCommit" to changes.baseCommit,
        "mergeBase" to changes.mergeBase,
        "reason" to changes.reason,
        "detail" to changes.detail,
        "files" to changes.files.map {
            linkedMapOf(
                "path" to it.path,
                "status" to it.status,
                "kind" to it.kind,
                "classes" to it.classes,
            )
        },
    )

    private fun rule(rule: ImpactReport.Rule) = linkedMapOf(
        "reason" to rule.reason,
        "text" to rule.text,
        "detail" to rule.detail,
    )

    private fun task(task: ImpactReport.Task) = linkedMapOf(
        "path" to task.path,
        "result" to task.result.name.lowercase(),
        "rule" to task.rule?.let(::rule),
        "counts" to task.counts?.let {
            linkedMapOf("selected" to it.selected, "recognised" to it.recognised, "candidates" to it.candidates)
        },
        "tests" to task.tests.map(::test),
        "durationsMs" to linkedMapOf("selection" to task.durationMs),
    )

    private fun test(test: ImpactReport.Test) = linkedMapOf(
        "className" to test.className,
        "reason" to test.reason,
        "recognised" to test.recognised,
        "changedFile" to test.changedFile,
        "seedClass" to test.seedClass,
        "reachedClass" to test.reachedClass,
        "path" to test.path.map { linkedMapOf("from" to it.from, "to" to it.to, "kind" to it.kind) },
    )
}
