package gg.aquatic.testimpact.select

import gg.aquatic.testimpact.change.ChangeDetection
import gg.aquatic.testimpact.change.ChangedFile
import gg.aquatic.testimpact.change.FileKind
import gg.aquatic.testimpact.change.FileSnapshot

/**
 * Maps change detection to graph seeds. Only source files seed the graph; a change that cannot be mapped to classes
 * is reported as [Forced] instead of being dropped.
 */
internal object Seeds {

    fun from(detection: ChangeDetection): SeedSet = when (detection) {
        is ChangeDetection.Unknown -> SeedSet(
            emptyList(),
            Forced(SelectionUnknownReason.CHANGES_UNKNOWN, "${detection.reason}: ${detection.detail}"),
        )

        is ChangeDetection.Known -> fromFiles(detection.files)
    }

    private fun fromFiles(files: List<ChangedFile>): SeedSet {
        val seeds = mutableListOf<Seed>()
        var forced: Forced? = null
        for (file in files.sortedBy { it.path }) {
            val kinds = listOfNotNull(file.before?.kind, file.after?.kind)
            val problem = when {
                FileKind.BUILD_LOGIC in kinds -> "build logic change: ${file.path}"
                FileKind.RESOURCE in kinds -> "resource change: ${file.path}"
                FileKind.SOURCE in kinds -> {
                    val seed = sourceSeed(file)
                    if (seed == null) "classes of ${file.path} could not be resolved" else {
                        seeds.add(seed)
                        null
                    }
                }

                else -> null
            }
            if (problem != null && forced == null) {
                forced = Forced(SelectionUnknownReason.CHANGE_NOT_MAPPED, problem)
            }
        }
        val ordered = seeds
            .sortedWith(compareBy({ it.origin }, { it.classes.joinToString(",") }, { it.sourceKeys.joinToString(",") }))
            .distinct()
        return SeedSet(ordered, forced)
    }

    private fun sourceSeed(file: ChangedFile): Seed? {
        val before = file.before?.takeIf { it.kind == FileKind.SOURCE }
        val after = file.after?.takeIf { it.kind == FileKind.SOURCE }
        val sides = if (before != null && after != null && !before.classesResolved && after.classesResolved) {
            listOf(after)
        } else {
            listOfNotNull(before, after)
        }
        if (sides.isEmpty() || sides.any { !it.classesResolved }) return null
        val classes = sides.flatMap { it.classes }.distinct().sorted()
        val keys = sides.mapNotNull { sourceKey(it) }.distinct().sorted()
        return Seed(file.path, classes, keys)
    }

    private fun sourceKey(side: FileSnapshot): String? {
        val first = side.classes.firstOrNull() ?: return null
        val pkg = first.substringBeforeLast('.', "").replace('.', '/')
        val name = side.path.substringAfterLast('/')
        return if (pkg.isEmpty()) name else "$pkg/$name"
    }
}
