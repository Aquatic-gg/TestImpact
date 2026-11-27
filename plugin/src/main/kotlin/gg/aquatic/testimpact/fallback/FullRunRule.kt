package gg.aquatic.testimpact.fallback

import gg.aquatic.testimpact.select.SelectionUnknownReason
import java.util.Locale

/**
 * Every reason a Test task runs in full, in documentation order. The order is also the precedence among the rules
 * decided from the change set.
 *
 * @property id the stable kebab-case name shown on the console line and stored as the task's full rule.
 * @property text the sentence that reports use for the rule.
 * @property kind whether the rule is a fallback for an uncertain analysis or a request of the user.
 */
internal enum class FullRunRule(val text: String, val kind: Kind = Kind.FALLBACK) {
    ANALYSIS_ERROR("the analysis failed"),
    NO_BASE("the changes cannot be compared with a base commit"),
    DEPENDENCY_CHANGE("a dependency declaration or lock file changed"),
    TOOLCHAIN_CHANGE("the Gradle or Java toolchain changed"),
    BUILD_LOGIC("the build logic changed"),
    UNKNOWN_FILE_TYPE("a changed file is of a kind the plugin cannot map to classes"),
    RESOURCE_CHANGE("a resource file changed"),
    NO_INDEX("a changed source file or a class file could not be read into the class graph"),
    FIRST_RUN("no earlier full run of this task is recorded"),
    WIDE_CHANGE("the change reaches too large a share of the test classes"),
    NOT_RECOGNISED("no selected class is recognised as a test class"),
    UNSUPPORTED_FRAMEWORK("the test framework is not supported"),
    UNKNOWN_ENGINE("a JUnit Platform engine is missing or not supported"),
    MODE_FULL("mode is full", Kind.REQUEST),
    MODE_OFF("mode is off, the plugin leaves the task as it is", Kind.REQUEST),
    USER_TESTS("the build was given --tests", Kind.REQUEST),
    ;

    enum class Kind { FALLBACK, REQUEST }

    val id: String = name.lowercase(Locale.ROOT).replace('_', '-')

    companion object {
        /** The rule of a selection that could not be decided. Two of the reasons only back up the change stage. */
        fun forSelection(reason: SelectionUnknownReason): FullRunRule = when (reason) {
            SelectionUnknownReason.CHANGES_UNKNOWN -> ANALYSIS_ERROR
            SelectionUnknownReason.CHANGE_NOT_MAPPED -> UNKNOWN_FILE_TYPE
            SelectionUnknownReason.UNSUPPORTED_FRAMEWORK -> UNSUPPORTED_FRAMEWORK
            SelectionUnknownReason.UNKNOWN_ENGINE -> UNKNOWN_ENGINE
            SelectionUnknownReason.GRAPH_UNKNOWN -> NO_INDEX
            SelectionUnknownReason.ANALYSIS_ERROR -> ANALYSIS_ERROR
        }

        /** The rule with this [id], or null for an id this version does not know. */
        fun byId(id: String): FullRunRule? = entries.firstOrNull { it.id == id }
    }
}
