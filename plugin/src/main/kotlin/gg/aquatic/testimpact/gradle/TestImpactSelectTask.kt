package gg.aquatic.testimpact.gradle

import gg.aquatic.testimpact.change.DetectionInput
import gg.aquatic.testimpact.gradle.internal.AtomicFile
import gg.aquatic.testimpact.gradle.internal.SelectionDecision
import gg.aquatic.testimpact.gradle.internal.SelectionFile
import gg.aquatic.testimpact.gradle.internal.SelectionSettings
import gg.aquatic.testimpact.gradle.internal.TaskSelectionCodec
import gg.aquatic.testimpact.gradle.internal.TestImpactMode
import gg.aquatic.testimpact.select.Seeds
import gg.aquatic.testimpact.select.TestSelector
import gg.aquatic.testimpact.select.gradle.TestTaskCapture
import org.gradle.api.DefaultTask
import org.gradle.api.InvalidUserDataException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.UntrackedTask
import java.io.File

/**
 * Decides which test classes one Test task runs. It runs on every build, after the compile tasks and before the Test
 * task, because the answer depends on git and not on declared inputs. It writes [selectionFile], which the Test task
 * takes as an input, and [resultFile], the full record for reports.
 */
@UntrackedTask(because = "The selection depends on the git state of the working tree, which Gradle does not track.")
internal abstract class TestImpactSelectTask : DefaultTask() {

    /** The Test task this task decides for. */
    @get:Internal
    abstract val capture: Property<TestTaskCapture>

    @get:Internal
    abstract val classGraph: Property<ClassGraphService>

    @get:Internal
    abstract val selectionInputs: Property<SelectionInputsService>

    @get:Internal
    abstract val projectDir: DirectoryProperty

    @get:Input
    @get:Optional
    abstract val baseRef: Property<String>

    /** Source directories of the project's own source sets, Java and Kotlin. */
    @get:Internal
    abstract val sourceRoots: ConfigurableFileCollection

    /** Resource directories of the project's own source sets. */
    @get:Internal
    abstract val resourceRoots: ConfigurableFileCollection

    @get:Input
    abstract val dynamicPackages: SetProperty<String>

    @get:Input
    abstract val alwaysRun: SetProperty<String>

    @get:Input
    abstract val wideChangePercent: Property<Int>

    @get:Input
    abstract val wideChangeMinClasses: Property<Int>

    /** Name of a [TestImpactMode], never `OFF`: that mode adds no task. */
    @get:Input
    abstract val mode: Property<String>

    /** Whether the build restricts the run with `--tests`. */
    @get:Input
    abstract val userTests: Property<Boolean>

    @get:OutputFile
    abstract val selectionFile: RegularFileProperty

    @get:OutputFile
    abstract val resultFile: RegularFileProperty

    @TaskAction
    fun select() {
        val started = System.nanoTime()
        val task = capture.get()
        val taskPath = task.taskPath
        val chosenMode = TestImpactMode.valueOf(mode.get())
        val settings = try {
            SelectionSettings.of(
                baseRef.orNull,
                wideChangePercent.get(),
                wideChangeMinClasses.get(),
                dynamicPackages.get(),
                alwaysRun.get(),
            )
        } catch (e: IllegalArgumentException) {
            throw InvalidUserDataException("testImpact: ${e.message}", e)
        }
        val decision = try {
            SelectionDecision.shortCircuit(taskPath, chosenMode, userTests.get(), millisSince(started))
                ?: analyse(task, settings, chosenMode, started)
        } catch (e: Exception) {
            val detail = "${e.javaClass.name}: ${e.message}"
            logger.warn("TestImpact {}: the selection failed, the whole task runs ({})", taskPath, detail)
            logger.info("TestImpact {}: selection failure", taskPath, e)
            SelectionDecision.pluginError(taskPath, chosenMode, TaskSelection.cap(detail), millisSince(started))
        }
        AtomicFile.write(resultFile.get().asFile.toPath(), TaskSelectionCodec.write(decision))
        AtomicFile.write(selectionFile.get().asFile.toPath(), SelectionFile.render(decision))
        selectionInputs.get().markSelected(taskPath)
        logger.lifecycle("TestImpact {}: {}", taskPath, decision.consoleLine())
    }

    private fun analyse(
        capture: TestTaskCapture,
        settings: SelectionSettings,
        mode: TestImpactMode,
        started: Long,
    ): TaskSelection {
        val input = capture.resolve()
        val resources = resourceRoots.files.map(File::toPath)
        val detection = selectionInputs.get().changes(
            DetectionInput(
                projectDir = projectDir.get().asFile.toPath(),
                baseRef = settings.baseRef,
                sourceRoots = sourceRoots.files.map(File::toPath),
                resourceRoots = resources,
                classOutputDirs = input.testRoots + input.otherRoots,
                buildLogicRoots = emptyList(),
                conventionalRoots = true,
            ),
        )
        val service = selectionInputs.get()
        val selection = TestSelector.select(
            input,
            Seeds.from(detection),
            classGraph.get()::graph,
            settings.rules,
        ) { roots -> service.seeds(roots, resources) }
        return SelectionDecision.decide(selection, detection, settings, mode, false, millisSince(started))
    }

    private fun millisSince(started: Long): Long = (System.nanoTime() - started) / 1_000_000
}
