package gg.aquatic.testimpact.change

/**
 * Outcome of change detection. [Unknown] means the change set cannot be trusted, so the caller must run everything.
 */
internal sealed interface ChangeDetection {

    /**
     * @property files changed files sorted by path with an ordinal comparison.
     */
    data class Known(
        val baseRef: String,
        val baseCommit: String,
        val mergeBase: String,
        val files: List<ChangedFile>,
    ) : ChangeDetection

    data class Unknown(
        val reason: UnknownReason,
        val detail: String,
    ) : ChangeDetection
}
