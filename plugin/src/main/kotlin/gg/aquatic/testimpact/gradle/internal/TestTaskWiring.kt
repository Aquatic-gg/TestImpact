package gg.aquatic.testimpact.gradle.internal

import gg.aquatic.testimpact.gradle.ClassGraphService
import gg.aquatic.testimpact.gradle.SelectionInputsService
import gg.aquatic.testimpact.gradle.TestImpactExtension
import gg.aquatic.testimpact.gradle.TestImpactSelectTask
import gg.aquatic.testimpact.select.gradle.TestTaskCapture
import org.gradle.api.InvalidUserDataException
import org.gradle.api.Project
import org.gradle.api.file.SourceDirectorySet
import org.gradle.api.plugins.ExtensionAware
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.SourceSet
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.testing.Test
import java.io.File
import java.util.concurrent.Callable

/**
 * Connects every Test task of a project to its selection task. Runs once after the project is evaluated: that is when
 * the Test tasks and the user's `testImpact { }` block are known. In mode `off` it adds nothing at all.
 */
internal object TestTaskWiring {
    private const val GRADLE_PROPERTY = "testImpact"
    private const val ENVIRONMENT = "TEST_IMPACT"
    private const val TESTS_OPTION = "--tests"
    private const val KOTLIN_SOURCES = "kotlin"

    fun wire(
        project: Project,
        extension: TestImpactExtension,
        classGraph: Provider<ClassGraphService>,
        selectionInputs: Provider<SelectionInputsService>,
    ) {
        val providers = project.providers
        val mode = try {
            ModeResolution.resolve(
                providers.gradleProperty(GRADLE_PROPERTY).orNull,
                providers.environmentVariable(ENVIRONMENT).orNull,
                extension.mode.orNull,
            )
        } catch (e: IllegalArgumentException) {
            throw InvalidUserDataException("testImpact: ${e.message}", e)
        }
        if (mode == TestImpactMode.OFF) return
        val userTests = project.gradle.startParameter.taskRequests.any { request ->
            request.args.any { it == TESTS_OPTION || it.startsWith("$TESTS_OPTION=") }
        }
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
        val test = project.tasks.named(name, Test::class.java).get()
        val sourceSets = project.extensions.findByType(SourceSetContainer::class.java)
        val select = project.tasks.register(
            "testImpactSelect" + name.replaceFirstChar { it.uppercase() },
            TestImpactSelectTask::class.java,
        ) { task ->
            task.group = "verification"
            task.description = "Selects the classes of '$name' that the changes since the base can reach."
            task.capture.set(
                TestTaskCapture.of(test, project.providers, project.objects, project.gradle.gradleUserHomeDir),
            )
            task.usesService(classGraph)
            task.usesService(selectionInputs)
            task.classGraph.set(classGraph)
            task.selectionInputs.set(selectionInputs)
            task.projectDir.set(project.layout.projectDirectory)
            task.baseRef.set(extension.baseRef)
            task.sourceRoots.from(Callable { roots(sourceSets, ::sources) })
            task.resourceRoots.from(Callable { roots(sourceSets, ::resources) })
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
        test.inputs.file(selection)
            .withPropertyName("testImpactSelection")
            .withPathSensitivity(PathSensitivity.NONE)
        test.onlyIf("TestImpact selected no test class", SelectionNotEmpty(selection))
        test.doFirst(ApplySelection(selection))
    }

    private fun roots(sourceSets: SourceSetContainer?, of: (SourceSet) -> Set<File>): List<File> =
        sourceSets?.flatMap(of).orEmpty()

    private fun sources(sourceSet: SourceSet): Set<File> {
        val kotlin = (sourceSet as ExtensionAware).extensions.findByName(KOTLIN_SOURCES) as? SourceDirectorySet
        return sourceSet.allJava.sourceDirectories.files + kotlin?.sourceDirectories?.files.orEmpty()
    }

    private fun resources(sourceSet: SourceSet): Set<File> = sourceSet.resources.sourceDirectories.files
}
