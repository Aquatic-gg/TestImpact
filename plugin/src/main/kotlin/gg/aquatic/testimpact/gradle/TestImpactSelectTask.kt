package gg.aquatic.testimpact.gradle

import gg.aquatic.testimpact.gradle.internal.AtomicFile
import gg.aquatic.testimpact.gradle.internal.SelectionFile
import gg.aquatic.testimpact.gradle.internal.SelectionRun
import gg.aquatic.testimpact.gradle.internal.TaskSelectionCodec
import gg.aquatic.testimpact.gradle.internal.TestImpactMode
import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.UntrackedTask

/**
 * Decides which test classes one Test task runs. It runs on every build, after the compile tasks and before the Test
 * task, because the answer depends on git and not on declared inputs. It writes [selectionFile], which the Test task
 * takes as an input, and [resultFile], the full record for reports.
 */
@UntrackedTask(because = "The selection depends on the git state of the working tree, which Gradle does not track.")
internal abstract class TestImpactSelectTask : DefaultTask() {

    /** The decision path of the Test task this task decides for, shared with `testImpactReport`. */
    @get:Internal
    abstract val selectionRun: Property<SelectionRun>

    @get:Internal
    abstract val classGraph: Property<ClassGraphService>

    @get:Internal
    abstract val selectionInputs: Property<SelectionInputsService>

    @get:Input
    @get:Optional
    abstract val baseRef: Property<String>

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
        val run = selectionRun.get()
        val taskPath = run.taskPath
        val settings = SelectionRun.settings(
            baseRef.orNull,
            wideChangePercent.get(),
            wideChangeMinClasses.get(),
            dynamicPackages.get(),
            alwaysRun.get(),
        )
        val outcome = run.run(
            settings,
            TestImpactMode.valueOf(mode.get()),
            userTests.get(),
            classGraph.get(),
            selectionInputs.get(),
        )
        val decision = outcome.decision
        outcome.failure?.let { failure ->
            logger.warn("TestImpact {}: the selection failed, the whole task runs ({})", taskPath, decision.fullDetail)
            logger.info("TestImpact {}: selection failure", taskPath, failure)
        }
        AtomicFile.write(resultFile.get().asFile.toPath(), TaskSelectionCodec.write(decision))
        AtomicFile.write(selectionFile.get().asFile.toPath(), SelectionFile.render(decision))
        selectionInputs.get().markSelected(taskPath)
        logger.lifecycle("TestImpact {}: {}", taskPath, decision.consoleLine())
    }
}
