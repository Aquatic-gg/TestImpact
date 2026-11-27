package gg.aquatic.testimpact.escalation

import gg.aquatic.testimpact.escalation.internal.EscalationServiceName
import gg.aquatic.testimpact.escalation.internal.NotEscalated
import gg.aquatic.testimpact.escalation.internal.QuickPhaseAction
import gg.aquatic.testimpact.escalation.internal.QuickPhaseSpec
import gg.aquatic.testimpact.escalation.internal.QuickTaskSettings
import gg.aquatic.testimpact.escalation.internal.RedRecorder
import gg.aquatic.testimpact.escalation.state.RunPhase
import gg.aquatic.testimpact.gradle.internal.TestImpactMode
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.file.RegularFile
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.TaskProvider
import org.gradle.api.tasks.testing.Test

/** Registers the quick task and the service of a Test task. The quick task is a companion Test task named `testImpactQuick<Name>`. */
internal object EscalationWiring {
    private const val PREFIX = "testImpactQuick"
    private const val MARKER = "testImpactQuickTask"

    /**
     * Wires the red-run follow-up of [testTask]. A failure here, such as a task that already has the quick task's name,
     * leaves the task with the selection alone: one warning, and null.
     */
    fun wire(
        project: Project,
        testTask: TaskProvider<Test>,
        select: TaskProvider<*>,
        selection: Provider<RegularFile>,
        mode: TestImpactMode,
        userTests: Boolean,
    ): Escalation? = try {
        val name = testTask.name
        val quickName = PREFIX + name.replaceFirstChar { it.uppercase() }
        val state = project.layout.buildDirectory.file("testimpact/escalation/$name.state")
        val service = project.gradle.sharedServices.registerIfAbsent(EscalationServiceName.value, EscalationService::class.java) {}
        val userPath = project.absoluteProjectPath(name)
        val requestedByName = project.gradle.startParameter.taskRequests.any { request ->
            request.args.any { it == quickName || it.endsWith(":$quickName") }
        }
        val own = mutableSetOf<Any>(select)
        val quick = project.tasks.register(quickName, Test::class.java) { task ->
            val user = testTask.get()
            val baseline = user.filter.includePatterns.toSet()
            task.group = "verification"
            task.description = "Runs the red and the selected classes of '$name' before the full run."
            task.extensions.extraProperties.set(MARKER, true)
            QuickTaskSettings.copy(user, task, own)
            task.usesService(service)
            task.dependsOn(select)
            task.onlyIf(QuickPhaseSpec.REASON, QuickPhaseSpec(state, selection, mode == TestImpactMode.AUTO, userTests || launched(project), baseline))
            task.outputs.upToDateWhen(NotEscalated.Never)
            task.outputs.cacheIf("TestImpact: the quick phase is no verdict", NotEscalated.Never)
            task.doFirst(RedRecorder(state, service, userPath, RunPhase.QUICK, baseline))
            task.doFirst(QuickPhaseAction(state, selection, requestedByName))
            task.doLast(QuickPhaseAction.Done(service, userPath, requestedByName))
        }
        own.add(quick)
        Escalation(state, service, quick, project.absoluteProjectPath(quickName))
    } catch (e: Exception) {
        project.logger.warn(
            "TestImpact: could not add the quick task for '{}' in {}, it follows the selection only ({}: {})",
            testTask.name, project.path, e.javaClass.name, e.message,
        )
        null
    }

    /**
     * A build that names no task and has no default task still runs Test tasks when a launcher (an IDE, the Tooling API's
     * test launcher) selects them, and it adds the tests it names to the user's task only after the task graph is made.
     * The quick phase must not run, and fail the launch, for tests the launcher did not name.
     */
    private fun launched(project: Project): Boolean =
        project.gradle.startParameter.taskRequests.isEmpty() && project.defaultTasks.isEmpty()

    /** Whether [task] is a quick task of this plugin; the report leaves those out. */
    fun isQuick(task: Task): Boolean = task.extensions.extraProperties.has(MARKER)
}
