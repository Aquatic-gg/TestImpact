package gg.aquatic.testimpact.change

/**
 * A file that differs from the merge base. [before] is null for added and untracked files, [after] for deleted ones.
 */
internal data class ChangedFile(
    val status: ChangeStatus,
    val before: FileSnapshot?,
    val after: FileSnapshot?,
) {
    /** Path of the new side, or of the old side when the file is gone. */
    val path: String
        get() = (after ?: before)?.path ?: ""

    /** Every class name that either side declares; a nested class `N$...` is covered by its top-level `N`. */
    val reachedClasses: List<String>
        get() = ((before?.classes ?: emptyList()) + (after?.classes ?: emptyList())).distinct().sorted()
}
