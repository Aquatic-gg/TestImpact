package gg.aquatic.testimpact.report.gradle

import gg.aquatic.testimpact.gradle.ClassGraphService
import gg.aquatic.testimpact.gradle.SelectionInputsService
import gg.aquatic.testimpact.gradle.internal.SelectionRun
import gg.aquatic.testimpact.gradle.internal.TestImpactMode
import gg.aquatic.testimpact.report.Explain
import gg.aquatic.testimpact.report.PathScrubber
import gg.aquatic.testimpact.report.ReportBuilder
import gg.aquatic.testimpact.report.ReportJson
import gg.aquatic.testimpact.report.ReportText
import org.gradle.api.DefaultTask
import org.gradle.api.InvalidUserDataException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.UntrackedTask
import org.gradle.api.tasks.options.Option
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Runs, for each Test task of its project, the same [SelectionRun] as the task's selection, with the same settings and
 * mode, then writes `report.json` and `report.txt`. It decides at execution time and never fails on an analysis
 * problem: that becomes a full-run rule in the report. Only an invalid setting or option, or a file that cannot be
 * written, fails the task.
 */
@UntrackedTask(because = "It reads the git working tree, which Gradle does not track.")
internal abstract class TestImpactReportTask : DefaultTask() {

    /** The decision paths of the project's Test tasks, built like the ones of their selection tasks. */
    @get:Internal
    abstract val runs: ListProperty<SelectionRun>

    @get:Internal
    abstract val settingsDir: DirectoryProperty

    @get:Internal
    abstract val gradleUserHome: DirectoryProperty

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val runtimeFiles: ConfigurableFileCollection

    @get:Internal
    abstract val classGraph: Property<ClassGraphService>

    @get:Internal
    abstract val selectionInputs: Property<SelectionInputsService>

    @get:Input
    abstract val dynamicPackages: SetProperty<String>

    @get:Input
    abstract val alwaysRun: SetProperty<String>

    @get:Input
    abstract val wideChangePercent: Property<Int>

    @get:Input
    abstract val wideChangeMinClasses: Property<Int>

    /** Name of the resolved [TestImpactMode] of the build. */
    @get:Input
    abstract val mode: Property<String>

    /** Whether the build restricts the run with `--tests`. */
    @get:Input
    abstract val userTests: Property<Boolean>

    @get:Input
    @get:Optional
    @get:Option(option = "base", description = "Git ref to compare the working tree with, such as main.")
    abstract val baseRef: Property<String>

    @get:Input
    @get:Optional
    @get:Option(option = "explain", description = "Explain why a test class is selected, such as com.example.FooTest.")
    abstract val explain: Property<String>

    @get:OutputDirectory
    abstract val reportDir: DirectoryProperty

    @TaskAction
    fun report() {
        val explained = explain.orNull?.let { name -> invalid { Explain.validate(name) } }
        val settings = SelectionRun.settings(
            baseRef.orNull,
            wideChangePercent.get(),
            wideChangeMinClasses.get(),
            dynamicPackages.get(),
            alwaysRun.get(),
        )
        val chosenMode = TestImpactMode.valueOf(mode.get())
        val graphs = classGraph.get()
        val service = selectionInputs.get()
        val outcomes = runs.get().map { run ->
            run.run(settings, chosenMode, userTests.get(), graphs, service).also { outcome ->
                outcome.failure?.let { failure ->
                    logger.warn(
                        "testImpactReport {}: the selection failed, reported as a full run ({})",
                        run.taskPath, outcome.decision.fullDetail,
                    )
                    logger.info("testImpactReport {}: selection failure", run.taskPath, failure)
                }
            }
        }

        val settingsRoot = settingsDir.get().asFile.toPath()
        val scrubber = PathScrubber.of(
            settingsRoot,
            gradleUserHome.get().asFile.toPath(),
            System.getProperty("user.home")?.takeIf(String::isNotBlank)?.let { Path.of(it) },
            ignoreCase = System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true),
        )
        val report = ReportBuilder.build(projectPath(), outcomes, scrubber)

        val directory = reportDir.get().asFile.toPath()
        Files.createDirectories(directory)
        writeAtomically(directory.resolve(JSON_FILE), ReportJson.render(report))
        val text = directory.resolve(TEXT_FILE)
        writeAtomically(text, ReportText.render(report))
        logger.lifecycle("testImpactReport: ${report.project} written to ${shown(settingsRoot, text)}")

        if (explained != null) Explain.explain(report, explained).forEach { logger.lifecycle(it) }
    }

    private fun projectPath(): String = path.substringBeforeLast(':').ifEmpty { ":" }

    private fun <T> invalid(block: () -> T): T = try {
        block()
    } catch (e: IllegalArgumentException) {
        throw InvalidUserDataException(e.message.orEmpty(), e)
    }

    private fun shown(settings: Path, file: Path): String =
        (if (file.startsWith(settings)) settings.relativize(file) else file.fileName).toString().replace('\\', '/')

    @Throws(IOException::class)
    private fun writeAtomically(target: Path, content: String) {
        val temporary = target.resolveSibling(target.fileName.toString() + ".tmp")
        Files.write(temporary, content.toByteArray(StandardCharsets.UTF_8))
        try {
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private companion object {
        const val JSON_FILE = "report.json"
        const val TEXT_FILE = "report.txt"
    }
}
