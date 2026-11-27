package gg.aquatic.testimpact.gradle

import gg.aquatic.testimpact.change.ChangeDetection
import gg.aquatic.testimpact.change.ChangeDetector
import gg.aquatic.testimpact.change.DetectionInput
import gg.aquatic.testimpact.change.internal.GitRead
import gg.aquatic.testimpact.seed.SeedResult
import gg.aquatic.testimpact.seed.SeedScanner
import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/**
 * Shares what several Test tasks of one build ask for again and again: the git state of a repository and the seed
 * facts of each root. Each answer is computed once per distinct request, however many tasks ask at the same time; the
 * git state is read once per repository and base ref, and every project derives its own changes from it.
 */
internal abstract class SelectionInputsService : BuildService<BuildServiceParameters.None>, AutoCloseable {
    /** The repository of a project directory, a seam for tests. */
    internal var topLevelOf: (Path) -> Path? = { dir ->
        ChangeDetector.topLevel(DetectionInput(dir, null, emptyList(), emptyList(), emptyList(), emptyList()))
    }

    /** The git state of a repository against a base ref, a seam for tests. */
    internal var gitReadOf: (Path, String?) -> GitRead = { top, baseRef ->
        ChangeDetector.readGit(DetectionInput(top, baseRef, emptyList(), emptyList(), emptyList(), emptyList()))
    }

    private val topLevels = OncePerKey<Path, Path> { dir -> topLevelOf(dir) ?: dir }

    private val gitReads = OncePerKey<Pair<Path, String?>, GitRead> { key -> gitReadOf(key.first, key.second) }

    private val detections = OncePerKey<DetectionInput, ChangeDetection> { input ->
        val top = topLevels.get(input.projectDir)
        ChangeDetector.derive(gitReads.get(top to input.baseRef), input)
    }

    private val rootScans = OncePerKey<Pair<String, Boolean>, SeedResult> { (root, isClassRoot) ->
        val path = Path.of(root)
        if (isClassRoot) SeedScanner.scan(listOf(path), emptyList()) else SeedScanner.scan(emptyList(), listOf(path))
    }

    private val scans = OncePerKey<Pair<List<String>, List<String>>, SeedResult> { key ->
        SeedScanner.merge(key.first.map { rootScans.get(it to true) } + key.second.map { rootScans.get(it to false) })
    }

    private val selected: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** Records that the selection task of a Test task ran in this build. */
    fun markSelected(taskPath: String) {
        selected.add(taskPath)
    }

    /** False when the selection task of a Test task did not run in this build, for example under `-x`. */
    fun hasSelected(taskPath: String): Boolean = taskPath in selected

    internal val memoizedDetections: Int get() = detections.size

    internal val memoizedScans: Int get() = scans.size

    internal val memoizedRootScans: Int get() = rootScans.size

    internal val memoizedGitReads: Int get() = gitReads.size

    fun changes(input: DetectionInput): ChangeDetection = detections.get(
        input.copy(
            projectDir = normal(input.projectDir),
            sourceRoots = input.sourceRoots.map(::normal).distinct(),
            resourceRoots = input.resourceRoots.map(::normal).distinct(),
            classOutputDirs = input.classOutputDirs.map(::normal).distinct(),
            buildLogicRoots = input.buildLogicRoots.map(::normal).distinct(),
        ),
    )

    fun seeds(classRoots: List<Path>, resourceRoots: List<Path>): SeedResult =
        scans.get(keys(classRoots) to keys(resourceRoots))

    override fun close() {
        detections.clear()
        gitReads.clear()
        topLevels.clear()
        scans.clear()
        rootScans.clear()
        selected.clear()
    }

    private fun keys(roots: List<Path>): List<String> = roots.map { normal(it).toString() }.distinct().sorted()

    private fun normal(path: Path): Path = path.toAbsolutePath().normalize()
}
