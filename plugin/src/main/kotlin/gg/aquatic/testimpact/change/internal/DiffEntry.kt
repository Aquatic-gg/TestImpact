package gg.aquatic.testimpact.change.internal

import gg.aquatic.testimpact.change.ChangeStatus

/**
 * One tracked change against the merge base. [oldPath] is the path at the merge base (null for [ChangeStatus.ADDED]),
 * [newPath] the path in the working tree (null for [ChangeStatus.DELETED]); both are set and equal for a modification.
 */
internal data class DiffEntry(
    val status: ChangeStatus,
    val oldPath: String?,
    val newPath: String?,
)
