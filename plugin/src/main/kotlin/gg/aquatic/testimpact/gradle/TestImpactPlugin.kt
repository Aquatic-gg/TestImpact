package gg.aquatic.testimpact.gradle

import org.gradle.api.Plugin
import org.gradle.api.Project

/**
 * Entry point of the plugin. It registers the `testImpact` extension and the class analysis service but selects nothing
 * yet, so every test still runs.
 */
public class TestImpactPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        target.extensions.create("testImpact", TestImpactExtension::class.java)
        target.gradle.sharedServices.registerIfAbsent("testImpactClassGraph", ClassGraphService::class.java) { spec ->
            spec.parameters.cacheDirectory.set(target.layout.settingsDirectory.dir("build/testimpact/class-cache"))
        }
    }
}
