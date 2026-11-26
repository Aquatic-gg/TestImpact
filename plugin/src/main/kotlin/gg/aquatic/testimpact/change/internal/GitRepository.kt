package gg.aquatic.testimpact.change.internal

import gg.aquatic.testimpact.change.ChangeDetection
import gg.aquatic.testimpact.change.ChangeStatus
import gg.aquatic.testimpact.change.UnknownReason
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Reads the raw change set between the merge base with the base ref and the working tree. Never throws: every
 * failure becomes [GitRead.Failed].
 */
internal class GitRepository(private val runner: GitRunner) {

    /**
     * @param wantsBlob whether the content at the merge base of a repo-relative path is needed; blobs over
     * [MAX_BLOB_BYTES] are never loaded.
     */
    fun read(
        projectDir: Path,
        baseRef: String?,
        wantsBlob: (topLevel: Path, path: String) -> Boolean = { _, _ -> true },
    ): GitRead =
        try {
            readOrFail(projectDir, baseRef, wantsBlob)
        } catch (e: GitFailure) {
            GitRead.Failed(ChangeDetection.Unknown(e.reason, e.detail))
        } catch (e: IllegalArgumentException) {
            GitRead.Failed(ChangeDetection.Unknown(UnknownReason.ANALYSIS_ERROR, e.message.orEmpty().take(MAX_DETAIL)))
        }

    private fun readOrFail(projectDir: Path, requestedBase: String?, wantsBlob: (Path, String) -> Boolean): GitRead.Ok {
        if (!Files.isDirectory(projectDir)) fail(UnknownReason.NOT_A_REPOSITORY, "no such directory: $projectDir")
        val top = runner.run(projectDir, listOf("rev-parse", "--show-toplevel"))
        if (top.exitCode != 0) fail(UnknownReason.NOT_A_REPOSITORY, top.detail.ifEmpty { "not a git work tree" })
        val topLevel = try {
            Path.of(text(top.stdout).removeSuffix("\n"))
        } catch (e: InvalidPathException) {
            throw GitFailure(UnknownReason.ANALYSIS_ERROR, "unusable top-level path: ${e.message}".take(MAX_DETAIL))
        }

        val shallow = succeed(topLevel, "rev-parse", "--is-shallow-repository")
        when (shallow) {
            "true" -> fail(UnknownReason.SHALLOW_CLONE, "history is truncated, merge base cannot be trusted")
            "false" -> Unit
            else -> fail(UnknownReason.GIT_FAILED, "unexpected --is-shallow-repository output: ${shallow.take(80)}")
        }

        val symbolic = runner.run(topLevel, listOf("symbolic-ref", "-q", "HEAD"))
        if (symbolic.exitCode == 1) fail(UnknownReason.DETACHED_HEAD, "HEAD is not on a branch")
        if (symbolic.exitCode != 0) fail(UnknownReason.GIT_FAILED, symbolic.detail.ifEmpty { "symbolic-ref failed" })

        resolveCommit(topLevel, "HEAD") ?: fail(UnknownReason.NO_HEAD_COMMIT, "HEAD has no commit")

        val (baseRef, baseCommit) = resolveBase(topLevel, requestedBase)

        val mergeBaseResult = runner.run(topLevel, listOf("merge-base", baseCommit, "HEAD"))
        if (mergeBaseResult.exitCode == 1) fail(UnknownReason.NO_MERGE_BASE, "$baseRef shares no history with HEAD")
        if (mergeBaseResult.exitCode != 0) {
            fail(UnknownReason.GIT_FAILED, mergeBaseResult.detail.ifEmpty { "merge-base failed" })
        }
        val mergeBase = commitId(text(mergeBaseResult.stdout))

        val (entries, untracked) = withIndexCopy(topLevel) { env ->
            // The refresh makes stat-dirty but identical files drop out; it only ever touches the copy.
            succeedBytes(topLevel, env, "update-index", "-q", "--refresh")
            val diff = succeedBytes(
                topLevel, env, "diff-index", "-z", "--name-status", "--find-renames", "--no-ext-diff", "--no-textconv",
                "--no-relative", "--ignore-submodules=none", mergeBase, "--",
            )
            val others = succeedBytes(topLevel, env, "ls-files", "-z", "--others", "--exclude-standard")
            GitOutput.nameStatusZ(diff) to GitOutput.nulList(others)
        }
        val before = readBefore(topLevel, mergeBase, entries, wantsBlob)
        return GitRead.Ok(baseRef, baseCommit, mergeBase, topLevel, entries, untracked, before)
    }

    private fun <T> withIndexCopy(topLevel: Path, block: (Map<String, String>) -> T): T {
        val located = runner.run(topLevel, listOf("rev-parse", "--git-path", "index"))
        if (located.exitCode != 0) fail(UnknownReason.GIT_FAILED, located.detail.ifEmpty { "rev-parse --git-path failed" })
        val index = try {
            topLevel.resolve(text(located.stdout).removeSuffix("\n"))
        } catch (e: InvalidPathException) {
            throw GitFailure(UnknownReason.ANALYSIS_ERROR, "unusable index path: ${e.message}".take(MAX_DETAIL))
        }
        var copy: Path? = null
        try {
            copy = Files.createTempFile("testimpact-index", ".tmp")
            Files.copy(index, copy, StandardCopyOption.REPLACE_EXISTING)
            return block(mapOf("GIT_INDEX_FILE" to copy.toString()))
        } catch (e: IOException) {
            throw GitFailure(UnknownReason.GIT_FAILED, "cannot copy the index: ${e.message}".take(MAX_DETAIL))
        } finally {
            copy?.let {
                for (leftover in listOf(it, it.resolveSibling(it.fileName.toString() + ".lock"))) {
                    try {
                        Files.deleteIfExists(leftover)
                    } catch (_: IOException) {
                        leftover.toFile().deleteOnExit()
                    }
                }
            }
        }
    }

    private fun resolveBase(topLevel: Path, requested: String?): Pair<String, String> {
        if (requested != null) {
            if (requested.isEmpty() || requested.startsWith("-") || requested.any { it.isISOControl() }) {
                fail(UnknownReason.BASE_REF_NOT_FOUND, "unusable base ref")
            }
            val commit = resolveCommit(topLevel, requested)
                ?: fail(UnknownReason.BASE_REF_NOT_FOUND, "base ref not found: $requested")
            return requested to commit
        }
        for (candidate in DEFAULT_BASES) {
            val commit = resolveCommit(topLevel, candidate) ?: continue
            return candidate to commit
        }
        return fail(UnknownReason.BASE_REF_NOT_FOUND, "none of ${DEFAULT_BASES.joinToString()} exists")
    }

    private fun resolveCommit(topLevel: Path, ref: String): String? {
        val result = runner.run(topLevel, listOf("rev-parse", "--verify", "-q", "$ref^{commit}"))
        if (result.exitCode != 0) return null
        return commitId(text(result.stdout))
    }

    private fun readBefore(
        topLevel: Path,
        mergeBase: String,
        entries: List<DiffEntry>,
        wantsBlob: (Path, String) -> Boolean,
    ): Map<String, ByteArray?> {
        val before = LinkedHashMap<String, ByteArray?>()
        val queried = ArrayList<String>()
        for (entry in entries) {
            val path = entry.oldPath ?: continue
            if (path in before) continue
            before[path] = null
            if (!path.contains('\n') && wantsBlob(topLevel, path)) queried += path
        }
        if (queried.isEmpty()) return before
        val request = queried.joinToString("") { "$mergeBase:$it\n" }.toByteArray(StandardCharsets.UTF_8)
        val sizes = GitOutput.batchCheck(catFile(topLevel, "--batch-check", request), queried.size)
        val fetched = queried.filterIndexed { i, _ -> sizes[i]?.let { it <= MAX_BLOB_BYTES } == true }
        if (fetched.isEmpty()) return before
        val fetchRequest = fetched.joinToString("") { "$mergeBase:$it\n" }.toByteArray(StandardCharsets.UTF_8)
        val blobs = GitOutput.catFileBatch(catFile(topLevel, "--batch", fetchRequest), fetched.size)
        fetched.forEachIndexed { i, path -> before[path] = blobs[i] }
        return before
    }

    private fun catFile(topLevel: Path, mode: String, request: ByteArray): ByteArray {
        val result = runner.run(topLevel, listOf("cat-file", mode), request)
        if (result.exitCode != 0) fail(UnknownReason.GIT_FAILED, result.detail.ifEmpty { "cat-file failed" })
        return result.stdout
    }

    private fun succeed(dir: Path, vararg args: String): String = text(succeedBytes(dir, emptyMap(), *args)).trim()

    private fun succeedBytes(dir: Path, env: Map<String, String>, vararg args: String): ByteArray {
        val result = runner.run(dir, args.toList(), extraEnv = env)
        if (result.exitCode != 0) fail(UnknownReason.GIT_FAILED, result.detail.ifEmpty { "${args.first()} failed" })
        return result.stdout
    }

    private fun commitId(output: String): String {
        val id = output.trim()
        if (!COMMIT_ID.matches(id)) fail(UnknownReason.GIT_FAILED, "unexpected commit id: ${id.take(80)}")
        return id
    }

    private fun text(bytes: ByteArray): String = String(bytes, StandardCharsets.UTF_8)

    private fun fail(reason: UnknownReason, detail: String): Nothing = throw GitFailure(reason, detail.take(MAX_DETAIL))

    companion object {
        const val MAX_BLOB_BYTES = 4L * 1024 * 1024
        private const val MAX_DETAIL = 500
        private val DEFAULT_BASES = listOf("origin/HEAD", "origin/main", "origin/master", "main", "master")
        private val COMMIT_ID = Regex("[0-9a-f]{40,64}")
    }
}
