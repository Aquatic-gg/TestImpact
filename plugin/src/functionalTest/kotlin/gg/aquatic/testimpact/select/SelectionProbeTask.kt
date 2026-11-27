package gg.aquatic.testimpact.select

import gg.aquatic.testimpact.select.gradle.TestTaskCapture
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.UntrackedTask

/**
 * Runs the selector for every captured test task of its project and writes the result of each one to a text file, so a
 * TestKit build can observe the selection without running any test.
 */
@UntrackedTask(because = "The probe is a test seam and must run on every invocation.")
internal abstract class SelectionProbeTask : DefaultTask() {

    @get:Internal
    abstract val captures: ListProperty<TestTaskCapture>

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val runtimeFiles: ConfigurableFileCollection

    @get:Input
    @get:Optional
    abstract val seedNames: Property<String>

    @get:Internal
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun probe() {
        val dir = outputDir.get().asFile
        dir.mkdirs()
        val names = seedNames.orNull.orEmpty().split(',').map(String::trim).filter(String::isNotEmpty).sorted()
        val seeds = SeedSet(names.map { Seed("seed:$it", listOf(it), emptyList()) })
        for (capture in captures.get()) {
            val file = dir.resolve(fileName(capture.taskPath))
            file.writeText(render(TestSelector.select(capture, seeds)))
        }
    }

    private fun render(selection: Selection): String = when (selection) {
        is Selection.Known -> buildString {
            appendLine("known ${selection.recognisedCount} ${selection.candidateCount}")
            for (test in selection.tests) {
                val edges = test.path.edges.joinToString(",") { "${it.from}>${it.to}:${it.kind}" }.ifEmpty { "-" }
                appendLine("selected ${test.className} ${test.reason} ${test.recognised} ${test.path.origin} $edges")
            }
        }

        is Selection.Unknown -> "unknown ${selection.reason} ${selection.detail.replace('\n', ' ')}\n"
    }

    internal companion object {
        fun fileName(taskPath: String): String = taskPath.removePrefix(":").replace(':', '_') + ".txt"
    }
}
