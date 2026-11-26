package gg.aquatic.testimpact.change

internal enum class UnknownReason {
    NOT_A_REPOSITORY,
    GIT_UNAVAILABLE,
    GIT_FAILED,
    NO_HEAD_COMMIT,
    DETACHED_HEAD,
    SHALLOW_CLONE,
    BASE_REF_NOT_FOUND,
    NO_MERGE_BASE,
    ANALYSIS_ERROR,
}
