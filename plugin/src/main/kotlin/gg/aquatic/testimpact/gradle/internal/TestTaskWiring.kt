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
        val testTask = project.tasks.named(name, Test::class.java)
        val sourceSets = project.extensions.findByType(SourceSetContainer::class.java)
        val select = project.tasks.register(
            "testImpactSelect" + name.replaceFirstChar { it.uppercase() },
            TestImpactSelectTask::class.java,
        ) { task ->
            task.group = "verification"
            task.description = "Selects the classes of '$name' that the changes since the base can reach."
            val capture = TestTaskCapture.of(testTask.get(), project.providers, project.objects, project.gradle.gradleUserHomeDir)
            task.capture.set(capture)
            task.dependsOn(capture.testClassesDirs, capture.classpath)
            task.usesService(classGraph)
            task.usesService(selectionInputs)
            task.classGraph.set(classGraph)
            task.selectionInputs.set(selectionInputs)
            task.projectDir.set(project.layout.projectDirectory)
            task.baseRef.set(extension.baseRef)
            task.sourceRoots.from(Callable { roots(sourceSets, ::sources) })
            task.ownSourceRoots.from(
                Callable {
                    ownSources(
                        sourceSets?.map { it.output.classesDirs.files to sources(it) }.orEmpty(),
                        capture.testClassesDirs.files + capture.classpath.files,
                    )
                },
            )
            task.resourceRoots.from(resourceRoots(project))
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
        }
    }

    /** The resource directories scanned for links, shared by the selection tasks and the report. */
    internal fun resourceRoots(project: Project): Callable<List<File>> =
        Callable { roots(project.extensions.findByType(SourceSetContainer::class.java), ::resources) }

    private fun roots(sourceSets: SourceSetContainer?, of: (SourceSet) -> Set<File>): List<File> =
        sourceSets?.flatMap(of).orEmpty()

    /** The source roots of the source sets (class directories to sources) whose class output the task runs against. */
    internal fun ownSources(sets: List<Pair<Set<File>, Set<File>>>, onPath: Set<File>): List<File> =
        sets.filter { (classes, _) -> classes.any { it in onPath } }.flatMap { it.second }

    private fun sources(sourceSet: SourceSet): Set<File> {
        val kotlin = (sourceSet as ExtensionAware).extensions.findByName(KOTLIN_SOURCES) as? SourceDirectorySet
        return sourceSet.allJava.sourceDirectories.files + kotlin?.sourceDirectories?.files.orEmpty()
    }

    private fun resources(sourceSet: SourceSet): Set<File> = sourceSet.resources.sourceDirectories.files
}
