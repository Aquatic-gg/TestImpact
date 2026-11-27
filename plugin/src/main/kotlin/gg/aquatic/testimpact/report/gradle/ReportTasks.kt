package gg.aquatic.testimpact.report.gradle

import gg.aquatic.testimpact.change.gradle.DetectionCapture
import gg.aquatic.testimpact.gradle.ClassGraphService
import gg.aquatic.testimpact.gradle.SelectionInputsService
import gg.aquatic.testimpact.gradle.TestImpactExtension
import gg.aquatic.testimpact.gradle.internal.TestTaskWiring
import gg.aquatic.testimpact.select.gradle.TestTaskCapture
import org.gradle.api.Project
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.testing.Test

/** Registers `testImpactReport` in a project and feeds it a capture of each of the project's Test tasks. */
internal object ReportTasks {

    const val TASK_NAME = "testImpactReport"

    fun register(
        project: Project,
        extension: TestImpactExtension,
        classGraph: Provider<ClassGraphService>,
        selectionInputs: Provider<SelectionInputsService>,
    ) {
        val report = project.tasks.register(TASK_NAME, TestImpactReportTask::class.java) { task ->
            task.group = "verification"
            task.description = "Reports which test classes the current changes select, and why."
            task.reportDir.convention(project.layout.buildDirectory.dir("reports/testimpact"))
            task.detection.set(DetectionCapture.of(project))
            task.classGraph.set(classGraph)
            task.usesService(classGraph)
            task.selectionInputs.set(selectionInputs)
            task.usesService(selectionInputs)
            task.baseRef.convention(extension.baseRef)
            task.dynamicPackages.set(extension.dynamicPackages)
            task.alwaysRun.set(extension.alwaysRun)
            task.resourceRoots.from(TestTaskWiring.resourceRoots(project))
        }
        project.afterEvaluate {
            val userHome = project.gradle.gradleUserHomeDir
            report.configure { task ->
                for (test in project.tasks.withType(Test::class.java).toList()) {
                    task.captures.add(TestTaskCapture.of(test, project.providers, project.objects, userHome))
                    task.runtimeFiles.from(test.testClassesDirs, test.classpath)
                }
            }
        }
    }
}
