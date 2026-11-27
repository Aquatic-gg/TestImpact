package gg.aquatic.testimpact.gradle

import gg.aquatic.testimpact.change.ChangeDetection
import gg.aquatic.testimpact.change.ChangeDetector
import gg.aquatic.testimpact.change.DetectionInput
import gg.aquatic.testimpact.seed.SeedResult
import gg.aquatic.testimpact.seed.SeedScanner
import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters
import java.nio.file.Path

/**
 * Shares what several Test tasks of one build ask for again and again: the git diff of a project and the seed facts
 * of a set of roots. Each answer is computed once per distinct request, however many tasks ask at the same time.
 */
internal abstract class SelectionInputsService : BuildService<BuildServiceParameters.None>, AutoCloseable {
    private val detections = OncePerKey<DetectionInput, ChangeDetection> { input -> ChangeDetector.detect(input) }

    private val scans = OncePerKey<Pair<List<String>, List<String>>, SeedResult> { key ->
        SeedScanner.scan(key.first.map(Path::of), key.second.map(Path::of))
    }

    internal val memoizedDetections: Int get() = detections.size

    internal val memoizedScans: Int get() = scans.size

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
        scans.clear()
    }

    private fun keys(roots: List<Path>): List<String> = roots.map { normal(it).toString() }.distinct().sorted()

    private fun normal(path: Path): Path = path.toAbsolutePath().normalize()
}
