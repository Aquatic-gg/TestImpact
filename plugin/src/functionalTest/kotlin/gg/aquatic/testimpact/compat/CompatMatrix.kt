package gg.aquatic.testimpact.compat

import org.gradle.util.GradleVersion

/**
 * The Gradle versions the functional tests run against, derived from the `testimpact.gradleVersions`
 * system property and the JDK the tests run on.
 */
internal object CompatMatrix {
    const val PROPERTY = "testimpact.gradleVersions"

    /** The oldest Gradle that runs on a JDK, keyed by the first JDK feature version it applies to. */
    private val floors: Map<Int, GradleVersion> = mapOf(25 to GradleVersion.version("9.1.0"))

    fun parse(property: String?): List<GradleVersion> {
        val entries = property?.split(',')?.map { it.trim() }
            ?: throw IllegalStateException("$PROPERTY is not set")
        val versions = entries.map { entry ->
            check(entry.isNotEmpty()) { "$PROPERTY contains a blank entry: '$property'" }
            try {
                GradleVersion.version(entry)
            } catch (e: IllegalArgumentException) {
                throw IllegalStateException("$PROPERTY contains an invalid version '$entry'", e)
            }
        }
        return versions.distinct().sorted()
    }

    fun versionsFor(javaFeature: Int, property: String? = System.getProperty(PROPERTY)): List<GradleVersion> {
        check(javaFeature <= floors.keys.max()) { "add the Gradle floor for JDK $javaFeature to CompatMatrix" }
        val floor = floors.filterKeys { it <= javaFeature }.maxByOrNull { it.key }?.value
        val versions = parse(property)
        if (floor == null) return versions
        return versions.map { if (it < floor) floor else it }.distinct().sorted()
    }
}
