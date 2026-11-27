package gg.aquatic.testimpact.report.gradle

import gg.aquatic.testimpact.change.ChangeDetector
import gg.aquatic.testimpact.change.gradle.DetectionCapture
import gg.aquatic.testimpact.gradle.ClassGraphService
import gg.aquatic.testimpact.gradle.SelectionInputsService
import gg.aquatic.testimpact.report.Explain
import gg.aquatic.testimpact.report.PathScrubber
import gg.aquatic.testimpact.report.ReportBuilder
import gg.aquatic.testimpact.report.ReportJson
import gg.aquatic.testimpact.report.ReportText
import gg.aquatic.testimpact.seed.SeedRules
import gg.aquatic.testimpact.select.Seeds
import gg.aquatic.testimpact.select.Selection
import gg.aquatic.testimpact.select.TestSelector
import gg.aquatic.testimpact.select.gradle.TestTaskCapture
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
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Detects the changes of the working tree and selects, for each Test task of its project, the test classes they reach,
 * then writes `report.json` and `report.txt`. It selects at execution time and never fails on an analysis problem: that
 * becomes a full-run rule in the report. Only an invalid option or a file that cannot be written fails the task.
 */
@UntrackedTask(because = "It reads the git working tree, which Gradle does not track.")
internal abstract class TestImpactReportTask : DefaultTask() {

    @get:Internal
    abstract val captures: ListProperty<TestTaskCapture>

    @get:Internal
    abstract val detection: Property<DetectionCapture>

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val runtimeFiles: ConfigurableFileCollection

    @get:Internal
    abstract val classGraph: Property<ClassGraphService>

    @get:Internal
    abstract val selectionInputs: Property<SelectionInputsService>

    /** Resource directories of the project's source sets, scanned for resource links like the selection does. */
    @get:Internal
    abstract val resourceRoots: ConfigurableFileCollection

    @get:Input
    abstract val dynamicPackages: SetProperty<String>

    @get:Input
    abstract val alwaysRun: SetProperty<String>

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
        val base = baseRef.orNull?.takeIf { it.isNotEmpty() }
        val rules = invalid { SeedRules.of(dynamicPackages.get(), alwaysRun.get()) }
        if (base != null && base.length > MAX_BASE_LENGTH) {
            throw InvalidUserDataException("--base expects a git ref of at most $MAX_BASE_LENGTH characters")
        }

        val capture = detection.get()
        val tasks = captures.get()
        val classpathDirs = tasks
            .flatMap { it.testClassesDirs.files + it.classpath.files }
            .filter(File::isDirectory)
            .map(File::toPath)

        val detectionStart = System.nanoTime()
        val changes = ChangeDetector.detect(capture.resolve(classpathDirs, base))
        val seeds = Seeds.from(changes)
        val detectionMs = millisSince(detectionStart)

        val selectionMs = HashMap<String, Long>()
        val selections = ArrayList<Selection>(tasks.size)
        val graphs = classGraph.get()
        val service = selectionInputs.get()
        val resources = resourceRoots.files.map(File::toPath)
        for (task in tasks) {
            val start = System.nanoTime()
            selections.add(
                TestSelector.select(task, seeds, graphs::graph, rules) { roots -> service.seeds(roots, resources) },
            )
            selectionMs[task.taskPath] = millisSince(start)
        }

        val scrubber = PathScrubber.of(
            capture.settingsDir.toPath(),
            capture.gradleUserHome.toPath(),
            System.getProperty("user.home")?.takeIf(String::isNotBlank)?.let { Path.of(it) },
            ignoreCase = System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true),
        )
        val report = ReportBuilder.build(projectPath(), changes, seeds, selections, detectionMs, selectionMs, scrubber)

        val directory = reportDir.get().asFile.toPath()
        Files.createDirectories(directory)
        writeAtomically(directory.resolve(JSON_FILE), ReportJson.render(report))
        val text = directory.resolve(TEXT_FILE)
        writeAtomically(text, ReportText.render(report))
        logger.lifecycle("testImpactReport: ${report.project} written to ${shown(capture.settingsDir.toPath(), text)}")

        if (explained != null) Explain.explain(report, explained).forEach { logger.lifecycle(it) }
    }

    private fun projectPath(): String = path.substringBeforeLast(':').ifEmpty { ":" }

    private fun <T> invalid(block: () -> T): T = try {
        block()
    } catch (e: IllegalArgumentException) {
        throw InvalidUserDataException(e.message.orEmpty(), e)
    }

    private fun millisSince(start: Long): Long = (System.nanoTime() - start) / NANOS_PER_MILLI

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
        const val MAX_BASE_LENGTH = 256
        const val NANOS_PER_MILLI = 1_000_000L
    }
}
