package gg.aquatic.testimpact.report

/** `report.txt`: the short, human-readable form of a report. */
internal object ReportText {

    const val MAX_LISTED_FILES = 50

    fun render(report: ImpactReport): String {
        val lines = ArrayList<String>()
        val changes = report.changes
        when (changes.state) {
            ImpactReport.State.KNOWN -> {
                lines.add(
                    "base ${changes.baseRef} ${abbreviate(changes.baseCommit)} merge-base " +
                        "${abbreviate(changes.mergeBase)}: ${changes.files.size} changed files",
                )
                changes.files.take(MAX_LISTED_FILES).forEach { lines.add("  ${oneLine(it.path)}") }
                if (changes.files.size > MAX_LISTED_FILES) lines.add("  and ${changes.files.size - MAX_LISTED_FILES} more")
            }

            ImpactReport.State.UNKNOWN ->
                lines.add("changes unknown: ${changes.reason}: ${oneLine(changes.detail.orEmpty())}")
        }
        report.forced?.let { lines.add("full run forced: ${describe(it)}") }
        for (task in report.tasks) {
            val counts = task.counts
            if (task.result == ImpactReport.Result.FULL || counts == null) {
                lines.add("${task.path} full run: ${task.rule?.let(::describe).orEmpty()}")
            } else {
                lines.add(
                    "${task.path} selected ${counts.selected} of ${counts.candidates} test classes " +
                        "(${counts.recognised} recognised) in ${task.durationMs} ms",
                )
                task.tests.forEach { lines.add("  ${testLine(it)}") }
            }
        }
        val durations = report.durations
        lines.add(
            "analysis ${durations.totalMs} ms (detection ${durations.detectionMs} ms, " +
                "selection ${durations.selectionMs} ms)",
        )
        return lines.joinToString("\n", postfix = "\n")
    }

    /** `<test> <- <changed file>: <seed class> <-KIND- ... <- <reached class>` plus the reason when it is not plain. */
    fun testLine(test: ImpactReport.Test): String {
        val chain = StringBuilder(test.seedClass)
        for (step in test.path) chain.append(" <-").append(step.seed ?: step.kind).append("- ").append(step.from)
        val notes = ArrayList<String>()
        if (test.reason == "GLOBAL") notes.add(ReportRules.globalText(test.reachedClass))
        if (!test.recognised) notes.add("not recognised as a test class")
        val suffix = if (notes.isEmpty()) "" else " (${notes.joinToString("; ")})"
        return oneLine("${test.className} <- ${test.changedFile}: $chain$suffix")
    }

    fun describe(rule: ImpactReport.Rule): String {
        val detail = if (rule.detail.isBlank()) "" else ": ${rule.detail}"
        return oneLine("${rule.text} (${rule.reason})$detail")
    }

    private fun abbreviate(commit: String?): String = commit.orEmpty().take(7)

    private fun oneLine(text: String): String = text.map { if (it < ' ' || it == ' ' || it == ' ') ' ' else it }
        .joinToString("")
}
