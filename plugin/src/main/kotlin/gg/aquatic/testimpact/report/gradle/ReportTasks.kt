package gg.aquatic.testimpact.report.gradle

import gg.aquatic.testimpact.change.gradle.DetectionCapture
import gg.aquatic.testimpact.gradle.ClassGraphService
import gg.aquatic.testimpact.select.gradle.TestTaskCapture
import org.gradle.api.Project
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.testing.Test

/** Registers `testImpactReport` in a project and feeds it a capture of each of the project's Test tasks. */
internal object ReportTasks {

    const val TASK_NAME = "testImpactReport"

    fun register(project: Project, classGraph: Provider<ClassGraphService>) {
        val report = project.tasks.register(TASK_NAME, TestImpactReportTask::class.java) { task ->
            task.group = "verification"
            task.description = "Reports which test classes the current changes select, and why."
            task.reportDir.convention(project.layout.buildDirectory.dir("reports/testimpact"))
            task.detection.set(DetectionCapture.of(project))
            task.classGraph.set(classGraph)
            task.usesService(classGraph)
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
