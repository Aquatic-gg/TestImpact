package gg.aquatic.testimpact.change

import java.nio.file.Path
import java.time.Duration

/**
 * @property classOutputDirs class directories or jars that hold the compiled classes of changed sources.
 * @property conventionalRoots also treat `src/<set>/java|kotlin` as source and `src/<set>/resources` as resource
 * roots when no explicit root matches, for modules whose source sets are not known.
 * @property ownSourceRoots the [sourceRoots] whose classes are on the class path of the task; a changed file there must
 * have compiled classes, a file in any other root is left to the class graph of the task.
 */
internal data class DetectionInput(
    val projectDir: Path,
    val baseRef: String?,
    val sourceRoots: List<Path>,
    val resourceRoots: List<Path>,
    val classOutputDirs: List<Path>,
    val buildLogicRoots: List<Path>,
    val gitExecutable: String = "git",
    val gitTimeout: Duration = Duration.ofSeconds(60),
    val conventionalRoots: Boolean = false,
    val ownSourceRoots: List<Path> = sourceRoots,
)
