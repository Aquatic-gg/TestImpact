package gg.aquatic.testimpact.select

internal enum class SelectReason {
    /** The test class is reachable from a seed through the dependency graph. */
    REACHED,

    /** A reached class is a global extension point that can affect every test class of the task. */
    GLOBAL,

    /** The class matches an `alwaysRun` pattern. */
    ALWAYS_RUN,
}
