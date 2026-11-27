package gg.aquatic.testimpact.select

import gg.aquatic.testimpact.select.gradle.TestTaskCapture
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.testing.Test

/** Registers `selectionProbe` in a project and feeds it a capture of each of the project's test tasks. */
class SelectionProbePlugin : Plugin<Project> {

    override fun apply(project: Project) {
        val probe = project.tasks.register("selectionProbe", SelectionProbeTask::class.java) { task ->
            task.seedNames.convention(project.providers.gradleProperty("impactSeeds"))
            task.outputDir.convention(project.layout.buildDirectory.dir("impact-probe"))
        }
        project.afterEvaluate {
            val userHome = project.gradle.gradleUserHomeDir
            val tests = project.tasks.withType(Test::class.java).toList()
            probe.configure { task ->
                for (test in tests) {
                    task.captures.add(TestTaskCapture.of(test, project.providers, project.objects, userHome))
                    task.runtimeFiles.from(test.testClassesDirs, test.classpath)
                }
            }
        }
    }
}
