package gg.aquatic.testimpact.report

import gg.aquatic.testimpact.select.SelectionUnknownReason

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

    fun globalText(reachedClass: String): String = "a reached class registers a global extension: $reachedClass"
}
