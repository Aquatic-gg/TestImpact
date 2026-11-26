package gg.aquatic.testimpact.change.internal

import gg.aquatic.testimpact.change.ChangeDetection
import java.nio.file.Path

internal sealed interface GitRead {

    /**
     * @property before blob content at the merge base by repo-relative path; null when it could not be read.
     */
    class Ok(
        val baseRef: String,
        val baseCommit: String,
        val mergeBase: String,
        val topLevel: Path,
        val entries: List<DiffEntry>,
        val untracked: List<String>,
        val before: Map<String, ByteArray?>,
    ) : GitRead

    class Failed(val unknown: ChangeDetection.Unknown) : GitRead
}
