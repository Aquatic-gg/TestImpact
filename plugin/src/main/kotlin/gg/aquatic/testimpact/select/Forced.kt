package gg.aquatic.testimpact.select

/**
 * A change that cannot be mapped to classes, so every test task has to be treated as unknown.
 */
internal data class Forced(
    val reason: SelectionUnknownReason,
    val detail: String,
)
