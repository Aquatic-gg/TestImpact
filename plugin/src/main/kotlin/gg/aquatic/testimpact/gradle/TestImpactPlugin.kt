package gg.aquatic.testimpact.gradle

import gg.aquatic.testimpact.gradle.internal.ServiceNames
import gg.aquatic.testimpact.gradle.internal.TestTaskWiring
import org.gradle.api.Plugin
import org.gradle.api.Project

/**
 * Entry point of the plugin. It registers the `testImpact` extension and the shared services, then connects every
 * Test task to its selection once the project is evaluated.
 */
public class TestImpactPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        val extension = target.extensions.create("testImpact", TestImpactExtension::class.java)
        extension.mode.convention("auto")
        extension.wideChangePercent.convention(60)
        extension.wideChangeMinClasses.convention(12)
        val sharedServices = target.gradle.sharedServices
        val classGraph = sharedServices.registerIfAbsent(ServiceNames.classGraph, ClassGraphService::class.java) { spec ->
            spec.parameters.cacheDirectory.set(target.layout.settingsDirectory.dir("build/testimpact/class-cache"))
        }
        val selectionInputs = sharedServices.registerIfAbsent(ServiceNames.selectionInputs, SelectionInputsService::class.java) {}
        target.afterEvaluate { TestTaskWiring.wire(target, extension, classGraph, selectionInputs) }
    }
}
