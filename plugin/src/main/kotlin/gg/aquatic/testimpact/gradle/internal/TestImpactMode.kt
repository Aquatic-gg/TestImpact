package gg.aquatic.testimpact.gradle.internal

internal enum class TestImpactMode {
    /** Run only the test classes a change can reach. */
    AUTO,

    /** Leave the Test tasks exactly as they are without the plugin. */
    OFF,

    /** Run every test class, but still report the selection line. */
    FULL,
}
