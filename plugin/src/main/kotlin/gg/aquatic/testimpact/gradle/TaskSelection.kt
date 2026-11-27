package gg.aquatic.testimpact.gradle

import gg.aquatic.testimpact.gradle.internal.TestImpactMode

/**
 * The selection of one Test task: what changed, what was selected and why. It is written next to the task and read
 * back to apply the selection and to report it.
 *
 * @property formatVersion version of the stored layout.
 * @property taskPath path of the Test task, for example `:app:test`.
 * @property mode the mode the task ran under.
 * @property outcome what the task does.
 * @property changedFiles changed file paths, or null when the changes could not be determined or were not computed
 * (a mode or a `--tests` request that decides before looking at git).
 * @property candidateCount concrete top-level test classes of the task, or null when they were not analysed.
 * @property selected the selected classes sorted by name; for a [TaskOutcome.FULL] outcome it holds the partial
 * selection that was discarded (for `wide-change` and `not-recognised`) or nothing.
 * @property fullRule id of the reason the whole task runs, set exactly for [TaskOutcome.FULL]: the `id` of a
 * [gg.aquatic.testimpact.fallback.FullRunRule].
 * @property fullDetail human-readable detail of [fullRule], at most [MAX_DETAIL] characters.
 * @property analysisMillis wall-clock time spent selecting.
 */
internal data class TaskSelection(
    val taskPath: String,
    val mode: TestImpactMode,
    val outcome: TaskOutcome,
    val changedFiles: List<String>?,
    val candidateCount: Int?,
    val selected: List<SelectedEntry>,
    val fullRule: String?,
    val fullDetail: String?,
    val analysisMillis: Long,
    val formatVersion: Int = FORMAT_VERSION,
) {
    init {
        require(formatVersion == FORMAT_VERSION) { "unsupported selection format version $formatVersion" }
        require((outcome == TaskOutcome.FULL) == (fullRule != null)) { "fullRule is set exactly for a full outcome" }
        require((fullDetail?.length ?: 0) <= MAX_DETAIL) { "fullDetail is longer than $MAX_DETAIL characters" }
    }

    /**
     * One line for the build log: `changed N, selected X of Y test classes`, followed by `, full: <rule>: <detail>`
     * when the whole task runs. N is `?` when the changes are unknown; when the test classes were not analysed the
     * middle part reads `selected all test classes`. A full run counts every candidate as selected because every
     * class runs.
     */
    fun consoleLine(): String {
        val changed = changedFiles?.size?.toString() ?: "?"
        val chosen = if (outcome == TaskOutcome.FULL) candidateCount else selected.size
        val middle = if (chosen == null || candidateCount == null) {
            "selected all test classes"
        } else {
            "selected $chosen of $candidateCount test classes"
        }
        val detail = if (fullDetail.isNullOrEmpty()) "" else ": $fullDetail"
        val full = if (fullRule == null) "" else ", full: $fullRule$detail"
        return "changed $changed, $middle$full"
    }

    companion object {
        const val FORMAT_VERSION: Int = 1
        const val MAX_DETAIL: Int = 500

        /** Cuts [detail] to [MAX_DETAIL] characters and replaces line breaks so it stays on one log line. */
        fun cap(detail: String): String {
            val flat = detail.map { if (it == '\n' || it == '\r') ' ' else it }.joinToString("")
            return if (flat.length > MAX_DETAIL) flat.take(MAX_DETAIL - 3) + "..." else flat
        }
    }
}
