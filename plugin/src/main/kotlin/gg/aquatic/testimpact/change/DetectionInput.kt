package gg.aquatic.testimpact.change

import java.nio.file.Path
import java.time.Duration

internal data class DetectionInput(
    val projectDir: Path,
    val baseRef: String?,
    val sourceRoots: List<Path>,
    val resourceRoots: List<Path>,
    val classOutputDirs: List<Path>,
    val buildLogicRoots: List<Path>,
    val gitExecutable: String = "git",
    val gitTimeout: Duration = Duration.ofSeconds(60),
)
