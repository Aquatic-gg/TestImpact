package gg.aquatic.testimpact.change

import gg.aquatic.testimpact.change.internal.DiffEntry
import gg.aquatic.testimpact.change.internal.FileClassifier
import gg.aquatic.testimpact.change.internal.GitRead
import gg.aquatic.testimpact.change.internal.GitRepository
import gg.aquatic.testimpact.change.internal.GitRunner
import gg.aquatic.testimpact.change.internal.RepoPaths
import gg.aquatic.testimpact.change.internal.SourceScanner
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.util.zip.ZipFile

/**
 * Turns the git view of a project into changed files with the classes and resources they touch. Never throws.
 */
internal object ChangeDetector {

    private const val MAX_DETAIL = 500

    /** @param envOverrides environment for the git processes, a hook for tests. */
    fun detect(input: DetectionInput, envOverrides: Map<String, String> = emptyMap()): ChangeDetection =
        derive(readGit(input, envOverrides), input)

    /**
     * The git half of [detect]. The result depends on the repository and the base ref, not on the roots of [input], so
     * several projects of one repository can share it. Never throws.
     */
    fun readGit(input: DetectionInput, envOverrides: Map<String, String> = emptyMap()): GitRead =
        try {
            GitRepository(GitRunner(input.gitExecutable, input.gitTimeout, envOverrides))
                .read(input.projectDir, input.baseRef) { _, path -> path.endsWith(".kt") || path.endsWith(".java") }
        } catch (e: Exception) {
            GitRead.Failed(failure(e))
        }

    /** The top-level directory of the repository around the project of [input], or null when there is none. */
    fun topLevel(input: DetectionInput, envOverrides: Map<String, String> = emptyMap()): Path? =
        try {
            GitRepository(GitRunner(input.gitExecutable, input.gitTimeout, envOverrides)).topLevel(input.projectDir)
        } catch (_: Exception) {
            null
        }

    /** The roots-dependent half of [detect]: classifies the files of [read] for the project of [input]. Never throws. */
    fun derive(read: GitRead, input: DetectionInput): ChangeDetection =
        when (read) {
            is GitRead.Failed -> read.unknown
            is GitRead.Ok -> try {
                deriveOrThrow(read, input)
            } catch (e: Exception) {
                failure(e)
            }
        }

    private fun failure(e: Exception): ChangeDetection.Unknown = ChangeDetection.Unknown(
        UnknownReason.ANALYSIS_ERROR,
        "${e.javaClass.simpleName}: ${e.message.orEmpty()}".take(MAX_DETAIL),
    )

    private fun deriveOrThrow(read: GitRead.Ok, input: DetectionInput): ChangeDetection {
        val paths = RepoPaths(read.topLevel)
        val classifier = FileClassifier(
            input.sourceRoots.mapNotNull(paths::relativize),
            input.resourceRoots.mapNotNull(paths::relativize),
            input.buildLogicRoots.mapNotNull(paths::relativize),
            input.conventionalRoots,
        )
        val snapshots = Snapshots(read, classifier, input.classOutputDirs, input.ownSourceRoots)

        val files = ArrayList<ChangedFile>(read.entries.size + read.untracked.size)
        for (entry in read.entries) files += snapshots.changed(entry)
        for (path in read.untracked) files += ChangedFile(ChangeStatus.UNTRACKED, null, snapshots.after(path))
        files.sortWith(compareBy<ChangedFile> { it.path }.thenBy { it.status })
        return ChangeDetection.Known(read.baseRef, read.baseCommit, read.mergeBase, files)
    }

    private class Snapshots(
        private val read: GitRead.Ok,
        private val classifier: FileClassifier,
        private val classOutputDirs: List<Path>,
        sourceRoots: List<Path>,
    ) {
        private val rootReal: Path = read.topLevel.toRealPath()
        private val ownRoots: List<Path> = sourceRoots.map(::real)
        private val jarEntries = HashMap<Path, Set<String>>()

        fun changed(entry: DiffEntry): ChangedFile {
            val before = entry.oldPath?.let(::before)
            val after = entry.newPath?.let(::after)
            return ChangedFile(entry.status, before, after)
        }

        fun before(path: String): FileSnapshot {
            val snapshot = classifier.classify(path)
            if (snapshot.kind != FileKind.SOURCE) return snapshot
            val blob = read.before[path]
            if (blob == null) return unresolved(snapshot)
            return declared(snapshot, blob, verify = false)
        }

        fun after(path: String): FileSnapshot {
            val snapshot = classifier.classify(path)
            if (snapshot.kind != FileKind.SOURCE) return snapshot
            val bytes = readWorktree(path) ?: return unresolved(snapshot)
            return declared(snapshot, bytes, verify = true)
        }

        private fun declared(snapshot: FileSnapshot, bytes: ByteArray, verify: Boolean): FileSnapshot {
            val names = try {
                SourceScanner.scan(snapshot.path, String(bytes, StandardCharsets.UTF_8)).binaryNames
            } catch (_: IllegalArgumentException) {
                return unresolved(snapshot)
            }
            if (!verify || names.isEmpty() || names.all(::compiled)) return snapshot.copy(classes = names)
            // A file of the project itself must have its classes; for any other file the selector decides, because
            // only the task's class graph tells whether the file is related to it at all.
            if (ownRoots.any { real(read.topLevel.resolve(snapshot.path)).startsWith(it) }) return unresolved(snapshot)
            return snapshot.copy(classes = names, classesCompiled = false)
        }

        private fun real(path: Path): Path {
            var existing: Path? = path.toAbsolutePath().normalize()
            val rest = ArrayDeque<Path>()
            while (existing != null && !Files.exists(existing)) {
                existing.fileName?.let { rest.addFirst(it) }
                existing = existing.parent
            }
            val base = try {
                existing?.toRealPath() ?: path.toAbsolutePath().normalize()
            } catch (_: IOException) {
                path.toAbsolutePath().normalize()
            }
            return rest.fold(base) { acc, part -> acc.resolve(part) }
        }

        private fun unresolved(snapshot: FileSnapshot): FileSnapshot = snapshot.copy(classesResolved = false)

        private fun compiled(binaryName: String): Boolean {
            val relative = binaryName.replace('.', '/') + ".class"
            return classOutputDirs.any { root ->
                if (Files.isDirectory(root)) Files.isRegularFile(root.resolve(relative))
                else relative in jarEntries.getOrPut(root) { jarEntryNames(root) }
            }
        }

        private fun jarEntryNames(jar: Path): Set<String> {
            if (!Files.isRegularFile(jar)) return emptySet()
            ZipFile(jar.toFile()).use { zip ->
                val names = HashSet<String>()
                for (entry in zip.entries()) if (!entry.isDirectory) names += entry.name
                return names
            }
        }

        private fun readWorktree(path: String): ByteArray? {
            try {
                val real = read.topLevel.resolve(path).toRealPath()
                if (!real.startsWith(rootReal) || !Files.isRegularFile(real)) return null
                if (Files.size(real) > GitRepository.MAX_BLOB_BYTES) return null
                return Files.readAllBytes(real)
            } catch (_: IOException) {
                return null
            } catch (_: InvalidPathException) {
                return null
            }
        }
    }
}
