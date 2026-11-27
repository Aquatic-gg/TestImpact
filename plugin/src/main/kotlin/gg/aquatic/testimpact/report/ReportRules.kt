package gg.aquatic.testimpact.report

import gg.aquatic.testimpact.gradle.TaskSelection
import gg.aquatic.testimpact.select.SelectionUnknownReason
import java.util.Locale

/** Fixed wording for the rule that forces a full run, shared by every output that names it. */
internal object ReportRules {

    fun text(reason: SelectionUnknownReason): String = when (reason) {
        SelectionUnknownReason.CHANGES_UNKNOWN -> "changes could not be determined"
        SelectionUnknownReason.CHANGE_NOT_MAPPED -> "a changed file cannot be mapped to classes"
        SelectionUnknownReason.UNSUPPORTED_FRAMEWORK -> "the test framework is not supported"
        SelectionUnknownReason.UNKNOWN_ENGINE -> "a JUnit Platform engine is missing or not supported"
        SelectionUnknownReason.GRAPH_UNKNOWN -> "a class file or archive could not be read"
        SelectionUnknownReason.ANALYSIS_ERROR -> "the analysis failed"
    }

    fun rule(reason: SelectionUnknownReason, detail: String): ImpactReport.Rule =
        ImpactReport.Rule(reason.name, text(reason), detail)

    /**
     * The rule for a full run that the decision makes after a known selection, such as a wide change; [rule] is a
     * [TaskSelection.fullRule] and becomes the code in upper case with underscores.
     */
    fun decisionRule(rule: String, detail: String): ImpactReport.Rule =
        ImpactReport.Rule(rule.uppercase(Locale.ROOT).replace('-', '_'), decisionText(rule), detail)

    private fun decisionText(rule: String): String = when (rule) {
        TaskSelection.RULE_WIDE_CHANGE -> "the change reaches too large a share of the test classes"
        TaskSelection.RULE_NOT_RECOGNISED -> "no selected class is recognised as a test class"
        TaskSelection.RULE_MODE_FULL -> "mode is full"
        TaskSelection.RULE_MODE_OFF -> "mode is off, the plugin leaves the task as it is"
        TaskSelection.RULE_USER_TESTS -> "the build was given --tests"
        TaskSelection.RULE_PLUGIN_ERROR -> "the selection failed inside the plugin"
        else -> "the whole task runs"
    }

    fun globalText(reachedClass: String): String = "a reached class registers a global extension: $reachedClass"
}
