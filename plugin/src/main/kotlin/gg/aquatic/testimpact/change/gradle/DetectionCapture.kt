package gg.aquatic.testimpact.change.gradle

import gg.aquatic.testimpact.change.DetectionInput
import gg.aquatic.testimpact.change.gradle.internal.BuildLayoutScan
import org.gradle.api.Project
import org.gradle.api.file.FileCollection
import org.gradle.api.plugins.JavaPluginExtension
import java.io.File
import java.nio.file.Path
import java.util.concurrent.Callable

/**
 * What the build tells change detection about one project, captured at configuration time. Like
 * `TestTaskCapture` it holds no project or task field, so it is a configuration cache value; the file collections are
 * resolved when the cache entry is stored.
 *
 * Every Kotlin or Java file below the settings directory counts as a source, so a changed file of any module is
 * mapped to its classes, except the build logic: buildSrc, build-logic, gradle and nested builds (a directory with its
 * own settings file) count as build logic and force a full run. Resource and compiled class directories of the other projects come from a bounded layout
 * scan, because project dependencies reach a classpath as jars that hide their class files. Anything the scan misses
 * is reported as unresolved and selects a full run.
 *
 * Threading: [resolve] reads the file system, so call it at execution time only.
 */
internal class DetectionCapture private constructor(
    val settingsDir: File,
    val gradleUserHome: File,
    private val sourceDirs: FileCollection,
    private val resourceDirs: FileCollection,
) {

    /**
     * @param classpathDirs directories of the test classpaths, searched for the compiled classes of a changed source.
     * @param baseRef the ref to compare with, null for the detector's default bases.
     */
    fun resolve(classpathDirs: List<Path>, baseRef: String?): DetectionInput {
        val root = settingsDir.toPath()
        val layout = BuildLayoutScan.scan(root)
        return DetectionInput(
            projectDir = root,
            baseRef = baseRef,
            sourceRoots = (listOf(root) + sourceDirs.files.map(File::toPath)).distinct(),
            resourceRoots = (resourceDirs.files.map(File::toPath) + layout.resourceRoots).distinct(),
            classOutputDirs = (classpathDirs + layout.classOutputDirs).distinct(),
            buildLogicRoots = (BUILD_LOGIC_DIRECTORIES.map(root::resolve) + layout.buildLogicRoots).distinct(),
        )
    }

    companion object {
        private val BUILD_LOGIC_DIRECTORIES = listOf("buildSrc", "build-logic", "gradle")

        fun of(project: Project): DetectionCapture {
            val sourceSets = Callable { project.extensions.findByType(JavaPluginExtension::class.java)?.sourceSets.orEmpty() }
            val sources = project.objects.fileCollection().from(Callable { sourceSets.call().flatMap { it.allJava.srcDirs } })
            val resources = project.objects.fileCollection().from(Callable { sourceSets.call().flatMap { it.resources.srcDirs } })
            return DetectionCapture(
                project.layout.settingsDirectory.asFile,
                project.gradle.gradleUserHomeDir,
                sources,
                resources,
            )
        }
    }
}
