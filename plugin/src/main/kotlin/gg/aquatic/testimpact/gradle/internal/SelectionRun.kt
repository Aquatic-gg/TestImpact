package gg.aquatic.testimpact.gradle.internal

import gg.aquatic.testimpact.change.DetectionInput
import gg.aquatic.testimpact.fallback.FallbackCapture
import gg.aquatic.testimpact.fallback.FallbackRules
import gg.aquatic.testimpact.gradle.ClassGraphService
import gg.aquatic.testimpact.gradle.SelectionInputsService
import gg.aquatic.testimpact.gradle.TaskSelection
import gg.aquatic.testimpact.select.Seeds
import gg.aquatic.testimpact.select.TestSelector
import gg.aquatic.testimpact.select.gradle.TestTaskCapture
import org.gradle.api.InvalidUserDataException
import org.gradle.api.Project
import org.gradle.api.file.FileCollection
import org.gradle.api.file.SourceDirectorySet
import org.gradle.api.plugins.ExtensionAware
import org.gradle.api.tasks.SourceSet
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.testing.Test
import java.io.File
import java.util.concurrent.Callable

/**
 * The one decision path of a Test task, shared by its selection task and by `testImpactReport`, so the report never
 * answers differently from the build: change detection on the task's own source roots, the selection, then the
 * decision with `mode` and the wide-change rule. The fallback rules (an untrusted change set, a file the plugin cannot
 * map, a task without history) decide right after change detection and skip the selection.
 *
 * Captured at configuration time; it holds no project or task field, so it is a configuration cache value. The file
 * collections are resolved when the cache entry is stored.
 *
 * Threading: [run] reads git, class files and archives, so call it at execution time only.
 */
internal class SelectionRun private constructor(
    val capture: TestTaskCapture,
    private val projectDir: File,
    private val settingsDir: File,
    private val sourceRoots: FileCollection,
    private val ownSourceRoots: FileCollection,
    private val resourceRoots: FileCollection,
    private val fallback: FallbackCapture,
) {

    val taskPath: String get() = capture.taskPath

    /**
     * Never throws an [Exception]: a failure becomes an `analysis-error` decision that carries it in
     * [SelectionOutcome.failure]. Errors are not caught.
     *
     * @param userTests whether the build restricts the run with `--tests`.
     */
    fun run(
        settings: SelectionSettings,
        mode: TestImpactMode,
        userTests: Boolean,
        classGraph: ClassGraphService,
        inputs: SelectionInputsService,
    ): SelectionOutcome {
        val started = System.nanoTime()
        return try {
            SelectionDecision.shortCircuit(taskPath, mode, userTests, millisSince(started))?.let { SelectionOutcome(it) }
                ?: analyse(settings, mode, classGraph, inputs, started)
        } catch (e: Exception) {
            val detail = TaskSelection.cap("${e.javaClass.name}: ${e.message}")
            SelectionOutcome(SelectionDecision.pluginError(taskPath, mode, detail, millisSince(started)), failure = e)
        }
    }

    private fun analyse(
        settings: SelectionSettings,
        mode: TestImpactMode,
        classGraph: ClassGraphService,
        inputs: SelectionInputsService,
        started: Long,
    ): SelectionOutcome {
        val input = capture.resolve()
        val resources = resourceRoots.files.map(File::toPath)
        val settingsRoot = settingsDir.toPath()
        val detection = inputs.changes(
            DetectionInput(
                projectDir = projectDir.toPath(),
                baseRef = settings.baseRef,
                sourceRoots = sourceRoots.files.map(File::toPath),
                ownSourceRoots = ownSourceRoots.files.map(File::toPath),
                resourceRoots = resources,
                classOutputDirs = input.testRoots + input.otherRoots,
                buildLogicRoots = BUILD_LOGIC_DIRECTORIES.map(settingsRoot::resolve),
                conventionalRoots = true,
                settingsDir = settingsRoot,
            ),
        )
        val detectionMillis = millisSince(started)
        FallbackRules.beforeSelection(detection, fallback.facts())?.let { verdict ->
            val decision = SelectionDecision.fallback(taskPath, mode, verdict, detection, millisSince(started))
            return SelectionOutcome(
                decision,
                detection,
                detectionMillis = detectionMillis,
                changeRule = verdict.rule.takeIf { verdict.changeWide },
            )
        }
        val seeds = Seeds.from(detection)
        val selection = TestSelector.select(input, seeds, classGraph::graph, settings.rules) { roots ->
            inputs.seeds(roots, resources)
        }
        val decision = SelectionDecision.decide(selection, detection, settings, mode, false, millisSince(started))
        return SelectionOutcome(decision, detection, seeds, selection, detectionMillis)
    }

    private fun millisSince(started: Long): Long = (System.nanoTime() - started) / NANOS_PER_MILLI

    companion object {
        private const val KOTLIN_SOURCES = "kotlin"
        private const val NANOS_PER_MILLI = 1_000_000L
        private val BUILD_LOGIC_DIRECTORIES = listOf("buildSrc", "build-logic", "gradle")

        /**
         * Validates the settings of a run the same way for the build and the report.
         *
         * @throws InvalidUserDataException naming every invalid setting
         */
        fun settings(
            baseRef: String?,
            wideChangePercent: Int,
            wideChangeMinClasses: Int,
            dynamicPackages: Collection<String>,
            alwaysRun: Collection<String>,
        ): SelectionSettings = try {
            SelectionSettings.of(baseRef, wideChangePercent, wideChangeMinClasses, dynamicPackages, alwaysRun)
        } catch (e: IllegalArgumentException) {
            throw InvalidUserDataException("testImpact: ${e.message}", e)
        }

        fun of(project: Project, test: Test): SelectionRun {
            val capture = TestTaskCapture.of(test, project.providers, project.objects, project.gradle.gradleUserHomeDir)
            val sourceSets = project.extensions.findByType(SourceSetContainer::class.java)
            val objects = project.objects
            val own = Callable {
                ownSources(
                    sourceSets?.map { it.output.classesDirs.files to sources(it) }.orEmpty(),
                    capture.testClassesDirs.files + capture.classpath.files,
                )
            }
            return SelectionRun(
                capture,
                project.projectDir,
                project.layout.settingsDirectory.asFile,
                objects.fileCollection().from(Callable { sourceSets?.flatMap(::sources).orEmpty() }),
                objects.fileCollection().from(own),
                objects.fileCollection().from(Callable { sourceSets?.flatMap(::resources).orEmpty() }),
                FallbackCapture.of(project, test),
            )
        }

        /** The source roots of the source sets (class directories to sources) whose class output the task runs against. */
        internal fun ownSources(sets: List<Pair<Set<File>, Set<File>>>, onPath: Set<File>): List<File> =
            sets.filter { (classes, _) -> classes.any { it in onPath } }.flatMap { it.second }

        private fun sources(sourceSet: SourceSet): Set<File> {
            val kotlin = (sourceSet as ExtensionAware).extensions.findByName(KOTLIN_SOURCES) as? SourceDirectorySet
            return sourceSet.allJava.sourceDirectories.files + kotlin?.sourceDirectories?.files.orEmpty()
        }

        private fun resources(sourceSet: SourceSet): Set<File> = sourceSet.resources.sourceDirectories.files
    }
}
