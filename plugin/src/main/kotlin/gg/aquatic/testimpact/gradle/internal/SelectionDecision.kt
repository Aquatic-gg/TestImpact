package gg.aquatic.testimpact.gradle.internal

import gg.aquatic.testimpact.change.ChangeDetection
import gg.aquatic.testimpact.gradle.SelectedEntry
import gg.aquatic.testimpact.gradle.TaskOutcome
import gg.aquatic.testimpact.gradle.TaskSelection
import gg.aquatic.testimpact.select.SelectedTest
import gg.aquatic.testimpact.select.Selection
import java.util.Locale

/** Turns what selection found into the decision for one Test task: run the selected classes, nothing, or all. */
internal object SelectionDecision {

    /**
     * The decision that needs neither change detection nor selection: [TestImpactMode.FULL], or a `--tests` filter
     * from the user. Null when the analysis has to run.
     */
    fun shortCircuit(taskPath: String, mode: TestImpactMode, userTests: Boolean, millis: Long): TaskSelection? =
        when {
            mode == TestImpactMode.FULL ->
                full(taskPath, mode, TaskSelection.RULE_MODE_FULL, "mode is full", millis)
            userTests ->
                full(taskPath, mode, TaskSelection.RULE_USER_TESTS, "the build was given --tests", millis)
            else -> null
        }

    /** A full run caused by a failure inside the plugin; the build itself carries on. */
    fun pluginError(taskPath: String, mode: TestImpactMode, detail: String, millis: Long): TaskSelection =
        full(taskPath, mode, TaskSelection.RULE_PLUGIN_ERROR, detail, millis)

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
                full(taskPath, mode, kebab(selection.reason.name), selection.detail, millis),
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
        val wide = candidates >= settings.wideChangeMinClasses &&
            tests.size.toLong() * 100 > settings.wideChangePercent.toLong() * candidates
        return when {
            wide -> base.asFull(
                TaskSelection.RULE_WIDE_CHANGE,
                "${tests.size} of $candidates test classes are affected, above ${settings.wideChangePercent}%",
            )
            tests.isNotEmpty() && tests.none { it.recognised } -> base.asFull(
                TaskSelection.RULE_NOT_RECOGNISED,
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

    private fun kebab(name: String): String = name.lowercase(Locale.ROOT).replace('_', '-')
}
