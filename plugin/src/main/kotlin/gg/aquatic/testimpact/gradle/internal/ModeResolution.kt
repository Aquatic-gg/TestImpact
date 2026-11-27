package gg.aquatic.testimpact.gradle.internal

import java.util.Locale

/**
 * Chooses the mode from its three sources: the Gradle property `-PtestImpact`, the environment variable
 * `TEST_IMPACT` and the `mode` property of the extension, in that order of precedence.
 */
internal object ModeResolution {
    private const val GRADLE_PROPERTY = "Gradle property 'testImpact'"
    private const val ENVIRONMENT = "environment variable 'TEST_IMPACT'"
    private const val DSL = "testImpact.mode"
    private const val MAX_SHOWN = 40

    /**
     * Blank values count as unset.
     *
     * @throws IllegalArgumentException when the winning source holds a value that is not `auto`, `off` or `full`;
     * the message names the source and the allowed values
     */
    fun resolve(gradleProperty: String?, env: String?, dsl: String?): TestImpactMode {
        val sources = listOf(GRADLE_PROPERTY to gradleProperty, ENVIRONMENT to env, DSL to dsl)
        for ((source, raw) in sources) {
            val value = raw?.trim().orEmpty()
            if (value.isNotEmpty()) return parse(source, value)
        }
        return TestImpactMode.AUTO
    }

    private fun parse(source: String, value: String): TestImpactMode {
        val lower = value.lowercase(Locale.ROOT)
        return TestImpactMode.entries.firstOrNull { it.name.lowercase(Locale.ROOT) == lower }
            ?: throw IllegalArgumentException(
                "$source is '${shown(value)}', expected one of: " +
                    TestImpactMode.entries.joinToString(", ") { it.name.lowercase(Locale.ROOT) },
            )
    }

    private fun shown(value: String): String {
        val clean = value.map { if (it.isISOControl()) '?' else it }.joinToString("")
        return if (clean.length > MAX_SHOWN) clean.take(MAX_SHOWN) + "..." else clean
    }
}
