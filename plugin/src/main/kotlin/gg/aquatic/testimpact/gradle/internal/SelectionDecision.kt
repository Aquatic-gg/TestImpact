package gg.aquatic.testimpact.gradle.internal

import gg.aquatic.testimpact.change.ChangeDetection
import gg.aquatic.testimpact.fallback.FallbackRules
import gg.aquatic.testimpact.fallback.FullRunRule
import gg.aquatic.testimpact.fallback.WideChange
import gg.aquatic.testimpact.gradle.SelectedEntry
import gg.aquatic.testimpact.gradle.TaskOutcome
import gg.aquatic.testimpact.gradle.TaskSelection
import gg.aquatic.testimpact.select.SelectedTest
import gg.aquatic.testimpact.select.Selection

/** Turns what selection found into the decision for one Test task: run the selected classes, nothing, or all. */
internal object SelectionDecision {

    /**
     * The decision that needs neither change detection nor selection: [TestImpactMode.FULL], [TestImpactMode.OFF]
     * (only the report asks; that mode adds no selection task), or a `--tests` filter from the user. Null when the
     * analysis has to run.
     */
    fun shortCircuit(taskPath: String, mode: TestImpactMode, userTests: Boolean, millis: Long): TaskSelection? =
        when {
            mode == TestImpactMode.FULL ->
                full(taskPath, mode, FullRunRule.MODE_FULL.id, "mode is full", millis)
            mode == TestImpactMode.OFF ->
                full(taskPath, mode, FullRunRule.MODE_OFF.id, "mode is off", millis)
            userTests ->
                full(taskPath, mode, FullRunRule.USER_TESTS.id, "the build was given --tests", millis)
            else -> null
        }

    /** A full run caused by a failure inside the plugin; the build itself carries on. */
    fun pluginError(taskPath: String, mode: TestImpactMode, detail: String, millis: Long): TaskSelection =
        full(taskPath, mode, FullRunRule.ANALYSIS_ERROR.id, "the selection failed: $detail", millis)

    /** A full run that a fallback rule decided before the selection; the changed files stay known. */
    fun fallback(
        taskPath: String,
        mode: TestImpactMode,
        verdict: FallbackRules.Verdict,
        detection: ChangeDetection,
        millis: Long,
    ): TaskSelection = withChanges(full(taskPath, mode, verdict.rule.id, verdict.detail, millis), detection)

    /**
     * @param userTests whether the user restricted the run with `--tests`
     * @param millis time spent so far
     */
    fun decide(
        selection: Selection,
        detection: ChangeDetection,
        settings: SelectionSettings,
        mode: TestImpactMode,
        userTests: Boolean,
        millis: Long,
    ): TaskSelection {
        val taskPath = selection.taskPath
        shortCircuit(taskPath, mode, userTests, millis)?.let { return withChanges(it, detection) }
        return when (selection) {
            is Selection.Unknown -> withChanges(
                full(taskPath, mode, FullRunRule.forSelection(selection.reason).id, selection.detail, millis),
                detection,
            )
            is Selection.Known -> decideKnown(selection, detection, settings, mode, millis)
        }
    }

    private fun decideKnown(
        selection: Selection.Known,
        detection: ChangeDetection,
        settings: SelectionSettings,
        mode: TestImpactMode,
        millis: Long,
    ): TaskSelection {
        val tests = selection.tests
        val entries = tests.map(::entry)
        val candidates = selection.candidateCount
        val changed = changedFiles(detection)
        val base = TaskSelection(
            taskPath = selection.taskPath,
            mode = mode,
            outcome = TaskOutcome.SELECTED,
            changedFiles = changed,
            candidateCount = candidates,
            selected = entries,
            fullRule = null,
            fullDetail = null,
            analysisMillis = millis,
        )
        val wide = WideChange.exceeds(tests.size, candidates, settings.wideChangePercent, settings.wideChangeMinClasses)
        return when {
            wide -> base.asFull(
                FullRunRule.WIDE_CHANGE.id,
                "${tests.size} of $candidates test classes are affected, above ${settings.wideChangePercent}%",
            )
            tests.isNotEmpty() && tests.none { it.recognised } -> base.asFull(
                FullRunRule.NOT_RECOGNISED.id,
                "none of the ${tests.size} selected classes is recognised as a test",
            )
            tests.isEmpty() -> base.copy(outcome = TaskOutcome.EMPTY)
            else -> base
        }
    }

    private fun TaskSelection.asFull(rule: String, detail: String): TaskSelection =
        copy(outcome = TaskOutcome.FULL, fullRule = rule, fullDetail = TaskSelection.cap(detail))

    private fun full(taskPath: String, mode: TestImpactMode, rule: String, detail: String, millis: Long) =
        TaskSelection(
            taskPath = taskPath,
            mode = mode,
            outcome = TaskOutcome.FULL,
            changedFiles = null,
            candidateCount = null,
            selected = emptyList(),
            fullRule = rule,
            fullDetail = TaskSelection.cap(detail),
            analysisMillis = millis,
        )

    private fun withChanges(selection: TaskSelection, detection: ChangeDetection): TaskSelection =
        selection.copy(changedFiles = changedFiles(detection))

    private fun changedFiles(detection: ChangeDetection): List<String>? = when (detection) {
        is ChangeDetection.Known -> detection.files.map { it.path }
        is ChangeDetection.Unknown -> null
    }

    private fun entry(test: SelectedTest): SelectedEntry = SelectedEntry(
        className = test.className,
        reason = test.reason,
        recognised = test.recognised,
        origin = test.path.origin,
        start = test.path.start,
        edges = test.path.edges.map { "${it.from}>${it.to}:${it.kind.name}" },
        seedKinds = test.path.seedKinds.entries.sortedBy { it.key }.map { "${it.key}:${it.value.name}" },
    )
}
