package gg.aquatic.testimpact.gradle.internal

import gg.aquatic.testimpact.fallback.FallbackCapture
import gg.aquatic.testimpact.fallback.RecordHistory
import gg.aquatic.testimpact.gradle.ClassGraphService
import gg.aquatic.testimpact.gradle.SelectionInputsService
import gg.aquatic.testimpact.gradle.TestImpactExtension
import gg.aquatic.testimpact.gradle.TestImpactSelectTask
import org.gradle.api.InvalidUserDataException
import org.gradle.api.Project
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.testing.Test

/**
 * Connects every Test task of a project to its selection task. Runs once after the project is evaluated: that is when
 * the Test tasks and the user's `testImpact { }` block are known. In mode `off` it adds nothing at all.
 */
internal object TestTaskWiring {
    private const val GRADLE_PROPERTY = "testImpact"
    private const val ENVIRONMENT = "TEST_IMPACT"
    private const val TESTS_OPTION = "--tests"

    fun wire(
        project: Project,
        extension: TestImpactExtension,
        classGraph: Provider<ClassGraphService>,
        selectionInputs: Provider<SelectionInputsService>,
    ) {
        val mode = mode(project, extension)
        if (mode == TestImpactMode.OFF) return
        val userTests = userTests(project)
        for (name in project.tasks.withType(Test::class.java).names.toList()) {
            try {
                wireTask(project, extension, classGraph, selectionInputs, name, mode, userTests)
            } catch (e: Exception) {
                project.logger.warn(
                    "TestImpact: could not wire task '{}' in {}, it runs as without the plugin ({}: {})",
                    name, project.path, e.javaClass.name, e.message,
                )
            }
        }
    }

    private fun wireTask(
        project: Project,
        extension: TestImpactExtension,
        classGraph: Provider<ClassGraphService>,
        selectionInputs: Provider<SelectionInputsService>,
        name: String,
        mode: TestImpactMode,
        userTests: Boolean,
    ) {
        val testTask = project.tasks.named(name, Test::class.java)
        val select = project.tasks.register(
            "testImpactSelect" + name.replaceFirstChar { it.uppercase() },
            TestImpactSelectTask::class.java,
        ) { task ->
            task.group = "verification"
            task.description = "Selects the classes of '$name' that the changes since the base can reach."
            val run = SelectionRun.of(project, testTask.get())
            task.selectionRun.set(run)
            task.dependsOn(run.capture.testClassesDirs, run.capture.classpath)
            task.usesService(classGraph)
            task.usesService(selectionInputs)
            task.classGraph.set(classGraph)
            task.selectionInputs.set(selectionInputs)
            task.baseRef.set(extension.baseRef)
            task.dynamicPackages.set(extension.dynamicPackages)
            task.alwaysRun.set(extension.alwaysRun)
            task.wideChangePercent.set(extension.wideChangePercent)
            task.wideChangeMinClasses.set(extension.wideChangeMinClasses)
            task.mode.set(mode.name)
            task.userTests.set(userTests)
            task.selectionFile.set(project.layout.buildDirectory.file("testimpact/selection/$name.sel"))
            task.resultFile.set(project.layout.buildDirectory.file("testimpact/selection/$name.txt"))
        }
        val selection = select.flatMap { it.selectionFile }
        testTask.configure { test ->
            test.usesService(selectionInputs)
            test.dependsOn(select)
            test.inputs.file(selection.filter { it.asFile.isFile })
                .withPropertyName("testImpactSelection")
                .withPathSensitivity(PathSensitivity.NONE)
                .optional()
            // The file of an earlier build stays on disk when the selection task is excluded; without this marker such
            // a run would look like the partial run that wrote the file.
            val path = test.path
            test.inputs.property("testImpactSelectionRan", selectionInputs.map { it.hasSelected(path) })
            val baseline = test.filter.includePatterns.toSet()
            test.onlyIf("TestImpact selected no test class", SelectionNotEmpty(selection, selectionInputs, baseline))
            test.doFirst(ApplySelection(selection, selectionInputs, baseline))
            test.doLast(RecordHistory(selection, selectionInputs, baseline, FallbackCapture.of(project, test)))
        }
    }

    /**
     * The mode of the build: the Gradle property, then the environment, then the `testImpact` block, then `auto`.
     *
     * @throws InvalidUserDataException naming where an invalid value came from
     */
    internal fun mode(project: Project, extension: TestImpactExtension): TestImpactMode {
        val providers = project.providers
        return try {
            ModeResolution.resolve(
                providers.gradleProperty(GRADLE_PROPERTY).orNull,
                providers.environmentVariable(ENVIRONMENT).orNull,
                extension.mode.orNull,
            )
        } catch (e: IllegalArgumentException) {
            throw InvalidUserDataException("testImpact: ${e.message}", e)
        }
    }

    /** Whether `--tests` was given for any task of the build. */
    internal fun userTests(project: Project): Boolean = project.gradle.startParameter.taskRequests.any { request ->
        request.args.any { it == TESTS_OPTION || it.startsWith("$TESTS_OPTION=") }
    }
}
