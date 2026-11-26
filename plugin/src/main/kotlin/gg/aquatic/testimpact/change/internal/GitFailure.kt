package gg.aquatic.testimpact.change.internal

import gg.aquatic.testimpact.change.UnknownReason

internal class GitFailure(
    val reason: UnknownReason,
    val detail: String,
) : Exception("$reason: $detail")
