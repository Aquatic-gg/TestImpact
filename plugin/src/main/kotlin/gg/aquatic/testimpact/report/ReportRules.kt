package gg.aquatic.testimpact.report

import gg.aquatic.testimpact.fallback.FullRunRule
import gg.aquatic.testimpact.gradle.TaskSelection
import java.util.Locale

/** Fixed wording for the rule that forces a full run, shared by every output that names it. */
internal object ReportRules {

    /**
     * The rule of a full run; [rule] is a [TaskSelection.fullRule] and becomes the code in upper case with
     * underscores.
     */
    fun decisionRule(rule: String, detail: String): ImpactReport.Rule =
        ImpactReport.Rule(rule.uppercase(Locale.ROOT).replace('-', '_'), decisionText(rule), detail)

    private fun decisionText(rule: String): String = FullRunRule.byId(rule)?.text ?: "the whole task runs"

    fun globalText(reachedClass: String): String = "a reached class registers a global extension: $reachedClass"
}
