package gg.aquatic.testimpact.report.gradle

import gg.aquatic.testimpact.gradle.ClassGraphService
import gg.aquatic.testimpact.gradle.SelectionInputsService
import gg.aquatic.testimpact.gradle.TestImpactExtension
import gg.aquatic.testimpact.gradle.internal.SelectionRun
import gg.aquatic.testimpact.gradle.internal.TestTaskWiring
import org.gradle.api.Project
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.testing.Test

/**
 * Registers `testImpactReport` in a project and feeds it the [SelectionRun] of each of the project's Test tasks, with
 * the settings and the mode the selection tasks get.
 */
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
            task.settingsDir.set(project.layout.settingsDirectory)
            task.gradleUserHome.set(project.gradle.gradleUserHomeDir)
            task.classGraph.set(classGraph)
            task.usesService(classGraph)
            task.selectionInputs.set(selectionInputs)
            task.usesService(selectionInputs)
            task.baseRef.convention(extension.baseRef)
            task.dynamicPackages.set(extension.dynamicPackages)
            task.alwaysRun.set(extension.alwaysRun)
            task.wideChangePercent.set(extension.wideChangePercent)
            task.wideChangeMinClasses.set(extension.wideChangeMinClasses)
        }
        project.afterEvaluate {
            report.configure { task ->
                task.mode.set(TestTaskWiring.mode(project, extension).name)
                task.userTests.set(TestTaskWiring.userTests(project))
                for (test in project.tasks.withType(Test::class.java).toList()) {
                    task.runs.add(SelectionRun.of(project, test))
                    task.runtimeFiles.from(test.testClassesDirs, test.classpath)
                }
            }
        }
    }
}
