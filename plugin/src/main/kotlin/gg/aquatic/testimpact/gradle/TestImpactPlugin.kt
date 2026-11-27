package gg.aquatic.testimpact.gradle

import org.gradle.api.Plugin
import org.gradle.api.Project

/**
 * Entry point of the plugin. It selects nothing yet, so every test still runs.
 */
public class TestImpactPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        target.extensions.create("testImpact", TestImpactExtension::class.java)
    }
}
