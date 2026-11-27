package gg.aquatic.testimpact.gradle.internal

import gg.aquatic.testimpact.seed.SeedRules

/**
 * The validated settings of one selection run.
 *
 * @property baseRef git ref the changes are measured against, or null to let change detection choose one.
 * @property wideChangePercent share of the candidate classes, in percent, above which a selection runs in full.
 * @property wideChangeMinClasses fewest candidate classes for which [wideChangePercent] applies.
 * @property rules the validated `dynamicPackages` and `alwaysRun` entries.
 */
internal class SelectionSettings private constructor(
    val baseRef: String?,
    val wideChangePercent: Int,
    val wideChangeMinClasses: Int,
    val rules: SeedRules,
) {
    companion object {
        const val MAX_REF_LENGTH: Int = 255
        const val MAX_MIN_CLASSES: Int = 1_000_000
        private const val SHOWN_LENGTH = 40

        /** @throws IllegalArgumentException naming every invalid setting in one message */
        fun of(
            baseRef: String?,
            wideChangePercent: Int,
            wideChangeMinClasses: Int,
            dynamicPackages: Collection<String>,
            alwaysRun: Collection<String>,
        ): SelectionSettings {
            val problems = ArrayList<String>()
            val ref = baseRef?.takeIf { it.isNotEmpty() }
            if (ref != null) refProblem(ref)?.let { problems.add("baseRef $it") }
            if (wideChangePercent !in 1..100) {
                problems.add("wideChangePercent is $wideChangePercent, expected 1 to 100")
            }
            if (wideChangeMinClasses !in 0..MAX_MIN_CLASSES) {
                problems.add("wideChangeMinClasses is $wideChangeMinClasses, expected 0 to $MAX_MIN_CLASSES")
            }
            val rules = try {
                SeedRules.of(dynamicPackages, alwaysRun)
            } catch (e: IllegalArgumentException) {
                problems.add(e.message ?: "invalid dynamicPackages or alwaysRun")
                SeedRules.NONE
            }
            require(problems.isEmpty()) { problems.joinToString("; ") }
            return SelectionSettings(ref, wideChangePercent, wideChangeMinClasses, rules)
        }

        private fun refProblem(ref: String): String? = when {
            ref.length > MAX_REF_LENGTH -> "is ${ref.length} characters long, at most $MAX_REF_LENGTH allowed"
            ref.startsWith("-") -> "'${shown(ref)}' must not start with '-'"
            ref.any { it.isWhitespace() || it.isISOControl() } ->
                "'${shown(ref)}' must not contain whitespace or control characters"
            ref.startsWith("..") || ref.endsWith("..") -> "'${shown(ref)}' must not start or end with '..'"
            else -> null
        }

        private fun shown(value: String): String {
            val clean = value.map { if (it.isISOControl()) '?' else it }.joinToString("")
            return if (clean.length > SHOWN_LENGTH) clean.take(SHOWN_LENGTH) + "..." else clean
        }
    }
}
