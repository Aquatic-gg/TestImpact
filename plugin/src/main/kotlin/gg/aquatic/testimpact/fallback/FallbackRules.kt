package gg.aquatic.testimpact.fallback

import gg.aquatic.testimpact.change.ChangeDetection
import gg.aquatic.testimpact.change.ChangedFile
import gg.aquatic.testimpact.change.FileKind
import gg.aquatic.testimpact.change.FileSnapshot
import gg.aquatic.testimpact.change.UnknownReason

/**
 * The rules that send a task to a full run before any selection happens: a change set that cannot be trusted, a
 * changed file the plugin cannot map to classes, and a task without history. Pure, so it runs without Gradle.
 */
internal object FallbackRules {

    /**
     * @property detail what to tell the user, naming the first path that triggered the rule.
     * @property changeWide whether the rule comes from the change set alone, so it is the same for every task of the
     * project; the history rules depend on the task and are not.
     */
    data class Verdict(val rule: FullRunRule, val detail: String, val changeWide: Boolean)

    private val CHANGE_RULES = listOf(
        FullRunRule.DEPENDENCY_CHANGE,
        FullRunRule.TOOLCHAIN_CHANGE,
        FullRunRule.BUILD_LOGIC,
        FullRunRule.UNKNOWN_FILE_TYPE,
        FullRunRule.RESOURCE_CHANGE,
        FullRunRule.NO_INDEX,
    )

    private val TOOLCHAIN_PATHS = listOf("gradle/wrapper/gradle-wrapper.properties", "gradle/gradle-daemon-jvm.properties")
    private val TOOLCHAIN_NAMES = setOf(".java-version", ".tool-versions", ".sdkmanrc", "mise.toml", ".mise.toml")
    private val SOURCE_EXTENSIONS = setOf("java", "kt", "groovy", "scala")

    fun beforeSelection(detection: ChangeDetection, facts: FallbackFacts): Verdict? =
        forChanges(detection) ?: forHistory(facts)

    /** The detection failure, then the changed files; null when every changed file is one the selection can map. */
    fun forChanges(detection: ChangeDetection): Verdict? = when (detection) {
        is ChangeDetection.Unknown -> Verdict(
            if (detection.reason.failedAnalysis()) FullRunRule.ANALYSIS_ERROR else FullRunRule.NO_BASE,
            "${detection.reason}: ${detection.detail}",
            true,
        )
        is ChangeDetection.Known -> forFiles(detection.files)
    }

    /** The task has never had a green full run, or ran it with another Java launcher. */
    fun forHistory(facts: FallbackFacts): Verdict? {
        val record = facts.history
            ?: return Verdict(FullRunRule.FIRST_RUN, "no earlier full run of this task is recorded", false)
        val before = record.toolchain
        val now = facts.toolchain
        if (!before.isNullOrEmpty() && !now.isNullOrEmpty() && before != now) {
            return Verdict(FullRunRule.TOOLCHAIN_CHANGE, "the Java launcher changed from $before to $now", false)
        }
        return null
    }

    private fun UnknownReason.failedAnalysis(): Boolean = when (this) {
        UnknownReason.GIT_FAILED, UnknownReason.ANALYSIS_ERROR -> true
        UnknownReason.NOT_A_REPOSITORY,
        UnknownReason.GIT_UNAVAILABLE,
        UnknownReason.NO_HEAD_COMMIT,
        UnknownReason.DETACHED_HEAD,
        UnknownReason.SHALLOW_CLONE,
        UnknownReason.BASE_REF_NOT_FOUND,
        UnknownReason.NO_MERGE_BASE,
        -> false
    }

    private fun forFiles(files: List<ChangedFile>): Verdict? {
        val byRule = files.sortedBy { it.path }.mapNotNull { file -> ruleOf(file)?.let { it to file.path } }
        val rule = CHANGE_RULES.firstOrNull { wanted -> byRule.any { it.first == wanted } } ?: return null
        val paths = byRule.filter { it.first == rule }.map { it.second }
        val more = if (paths.size > 1) " and ${paths.size - 1} more" else ""
        return Verdict(rule, "${paths.first()}$more", true)
    }

    private fun ruleOf(file: ChangedFile): FullRunRule? {
        val sides = listOfNotNull(file.before, file.after)
        val rules = sides.mapNotNull(::ruleOf).toMutableList()
        if (sides.any { it.kind == FileKind.SOURCE } && unmapped(file)) rules.add(FullRunRule.NO_INDEX)
        return rules.minByOrNull { CHANGE_RULES.indexOf(it) }
    }

    private fun ruleOf(side: FileSnapshot): FullRunRule? {
        val path = side.path
        val name = path.substringAfterLast('/')
        return when {
            name.endsWith(".versions.toml") || name.endsWith(".lockfile") || name == "verification-metadata.xml" ->
                FullRunRule.DEPENDENCY_CHANGE
            name in TOOLCHAIN_NAMES || TOOLCHAIN_PATHS.any { path == it || path.endsWith("/$it") } ->
                FullRunRule.TOOLCHAIN_CHANGE
            side.kind == FileKind.BUILD_LOGIC -> FullRunRule.BUILD_LOGIC
            side.kind == FileKind.RESOURCE -> FullRunRule.RESOURCE_CHANGE
            side.kind == FileKind.OTHER && (name.substringAfterLast('.', "") in SOURCE_EXTENSIONS || inSourceTree(path)) ->
                FullRunRule.UNKNOWN_FILE_TYPE
            else -> null
        }
    }

    /** True for a path `.../src/<set>/<something>`. */
    private fun inSourceTree(path: String): Boolean {
        val segments = path.split('/')
        val src = segments.indexOf("src")
        return src >= 0 && segments.size - src >= 3
    }

    /** Whether the classes of a changed source file cannot start a graph walk, judged like the seed mapping does. */
    private fun unmapped(file: ChangedFile): Boolean {
        val before = file.before?.takeIf { it.kind == FileKind.SOURCE }
        val after = file.after?.takeIf { it.kind == FileKind.SOURCE }
        val sides = if (before != null && after != null && !before.classesResolved && after.classesResolved) {
            listOf(after)
        } else {
            listOfNotNull(before, after)
        }
        return sides.isEmpty() || sides.any { !it.classesResolved || it.classes.isEmpty() }
    }
}
