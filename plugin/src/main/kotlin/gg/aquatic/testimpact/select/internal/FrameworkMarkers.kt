package gg.aquatic.testimpact.select.internal

/**
 * Binary names that identify test classes of the supported frameworks. Markers only classify a class as a test; they
 * never decide whether a reached class is selected.
 *
 * kotlin.test's `@Test` is a typealias that the compiler erases to the JUnit 4, JUnit 5 or TestNG annotation of the
 * chosen adapter, so it needs no marker of its own.
 */
internal object FrameworkMarkers {
    private val annotations = setOf(
        "org.junit.jupiter.api.Test",
        "org.junit.jupiter.api.RepeatedTest",
        "org.junit.jupiter.api.TestFactory",
        "org.junit.jupiter.api.TestTemplate",
        "org.junit.jupiter.params.ParameterizedTest",
        "org.junit.Test",
        "org.junit.runner.RunWith",
    )

    private const val JUNIT3_BASE = "junit.framework.TestCase"
    private const val KOTEST_SPEC_PREFIX = "io.kotest.core.spec."

    fun isTestAnnotation(name: String): Boolean = name in annotations

    /** A supertype that makes every subclass a test: JUnit 3 `TestCase` or a Kotest spec style. */
    fun isTestSupertype(name: String): Boolean = name == JUNIT3_BASE || name.startsWith(KOTEST_SPEC_PREFIX)
}
