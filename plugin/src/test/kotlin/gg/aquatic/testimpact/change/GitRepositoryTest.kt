package gg.aquatic.testimpact.change

import gg.aquatic.testimpact.change.internal.DiffEntry
import gg.aquatic.testimpact.change.internal.GitRead
import gg.aquatic.testimpact.change.internal.GitRepository
import gg.aquatic.testimpact.change.internal.GitRunner
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs

class GitRepositoryTest {

    private fun read(
        repo: TempGitRepo,
        dir: Path = repo.dir,
        base: String? = null,
        env: Map<String, String> = repo.env,
    ): GitRead = GitRepository(GitRunner(envOverrides = env)).read(dir, base)

    private fun unknownOf(read: GitRead): ChangeDetection.Unknown {
        assertIs<GitRead.Failed>(read)
        return read.unknown
    }

    private fun okOf(read: GitRead): GitRead.Ok {
        if (read is GitRead.Failed) throw AssertionError("unexpected ${read.unknown}")
        return assertIs<GitRead.Ok>(read)
    }

    private fun withRepo(autocrlf: Boolean? = null, block: (TempGitRepo) -> Unit) {
        TempGitRepo.create(autocrlf = autocrlf).use(block)
    }

    private fun committedRepo(block: (TempGitRepo) -> Unit) = withRepo {
        it.write("a.txt", "one\n")
        it.commitAll("one")
        block(it)
    }

    @Test
    fun notARepository() {
        TempGitRepo.create(init = false).use { repo ->
            assertEquals(UnknownReason.NOT_A_REPOSITORY, unknownOf(read(repo)).reason)
        }
    }

    @Test
    fun missingDirectoryIsNotARepository() = withRepo { repo ->
        assertEquals(UnknownReason.NOT_A_REPOSITORY, unknownOf(read(repo, dir = repo.sibling("absent"))).reason)
    }

    @Test
    fun missingGitExecutableIsUnavailable() = committedRepo { repo ->
        val read = GitRepository(GitRunner(executable = repo.sibling("no-git").toString())).read(repo.dir, null)
        assertEquals(UnknownReason.GIT_UNAVAILABLE, unknownOf(read).reason)
    }

    @Test
    fun unbornHead() = withRepo { repo ->
        assertEquals(UnknownReason.NO_HEAD_COMMIT, unknownOf(read(repo)).reason)
    }

    @Test
    fun detachedHead() = committedRepo { repo ->
        repo.write("a.txt", "two\n")
        repo.commitAll("two")
        repo.git("checkout", "-q", "--detach")
        assertEquals(UnknownReason.DETACHED_HEAD, unknownOf(read(repo)).reason)
    }

    @Test
    fun shallowCloneIsUnknownEvenWithBasePresent() = committedRepo { repo ->
        repo.write("a.txt", "two\n")
        repo.commitAll("two")
        repo.gitIn(repo.base, "clone", "-q", "--no-local", "--depth", "1", repo.dir.toString(), "shallow")
        val shallow = repo.sibling("shallow")
        assertEquals(UnknownReason.SHALLOW_CLONE, unknownOf(read(repo, dir = shallow)).reason)
        assertEquals(UnknownReason.SHALLOW_CLONE, unknownOf(read(repo, dir = shallow, base = "HEAD")).reason)
    }

    @Test
    fun unusableExplicitBaseRefs() = committedRepo { repo ->
        for (ref in listOf("no-such-branch", "--output=leak.txt", "-h", "-", "", "a\nb", "a\u0001b")) {
            assertEquals(UnknownReason.BASE_REF_NOT_FOUND, unknownOf(read(repo, base = ref)).reason, "ref '$ref'")
        }
        assertEquals(false, Files.exists(repo.dir.resolve("leak.txt")))
    }

    @Test
    fun noCandidateBase() = committedRepo { repo ->
        repo.git("branch", "-m", "dev")
        assertEquals(UnknownReason.BASE_REF_NOT_FOUND, unknownOf(read(repo)).reason)
    }

    @Test
    fun orphanBranchHasNoMergeBase() = committedRepo { repo ->
        repo.git("checkout", "-q", "--orphan", "lone")
        repo.git("rm", "-rf", "-q", ".")
        repo.write("b.txt", "other\n")
        repo.commitAll("lone")
        assertEquals(UnknownReason.NO_MERGE_BASE, unknownOf(read(repo, base = "main")).reason)
    }

    @Test
    fun repositoryOverridesFromTheEnvironmentAreIgnored() = committedRepo { repo ->
        val other = repo.sibling("other")
        repo.gitIn(repo.base, "init", "-q", "-b", "main", other.toString())
        val env = repo.env + mapOf(
            "GIT_DIR" to other.resolve(".git").toString(),
            "GIT_WORK_TREE" to other.toString(),
            "GIT_INDEX_FILE" to other.resolve("bogus-index").toString(),
        )
        val ok = okOf(read(repo, env = env))
        assertEquals(repo.dir.toRealPath(), ok.topLevel.toRealPath())
        assertEquals("main", ok.baseRef)
    }

    @Test
    fun indexFileIsUnchanged() = committedRepo { repo ->
        repo.write("b.txt", "two\n")
        repo.commitAll("two")
        Files.setLastModifiedTime(repo.dir.resolve("a.txt"), FileTime.fromMillis(1_000_000_000_000L))
        repo.write("c.txt", "untracked\n")
        val index = repo.dir.resolve(".git").resolve("index")
        val before = Files.readAllBytes(index)
        okOf(read(repo))
        assertContentEquals(before, Files.readAllBytes(index))
    }

    @Test
    fun touchedButUnchangedFilesAreNotListedAndTheRealIndexStaysUntouched() = committedRepo { repo ->
        repo.write("b.txt", "two\n")
        repo.commitAll("two")
        val a = repo.dir.resolve("a.txt")
        Files.setLastModifiedTime(a, FileTime.fromMillis(1_000_000_000_000L))
        repo.write("b.txt", "changed\n")
        val index = repo.dir.resolve(".git").resolve("index")
        val bytes = Files.readAllBytes(index)
        val mtime = Files.getLastModifiedTime(index)
        val ok = okOf(read(repo))
        assertEquals(listOf(DiffEntry(ChangeStatus.MODIFIED, "b.txt", "b.txt")), ok.entries)
        assertContentEquals(bytes, Files.readAllBytes(index))
        assertEquals(mtime, Files.getLastModifiedTime(index))
        assertEquals(emptyList(), Files.list(repo.dir.resolve(".git")).use { s -> s.filter { it.fileName.toString().endsWith(".lock") }.toList() })
    }

    @Test
    fun splitIndexIsRead() = committedRepo { repo ->
        repo.git("update-index", "--split-index")
        repo.write("a.txt", "edited\n")
        val ok = okOf(read(repo))
        assertEquals(listOf(DiffEntry(ChangeStatus.MODIFIED, "a.txt", "a.txt")), ok.entries)
    }

    @Test
    fun missingIndexFileIsGitFailed() = committedRepo { repo ->
        Files.delete(repo.dir.resolve(".git").resolve("index"))
        assertEquals(UnknownReason.GIT_FAILED, unknownOf(read(repo)).reason)
    }

    @Test
    fun largeBlobsAreNeverLoaded() = committedRepo { repo ->
        repo.writeBytes("big.bin", ByteArray(5 * 1024 * 1024) { (it % 251).toByte() })
        repo.writeBytes("small.bin", ByteArray(10) { 7 })
        repo.commitAll("blobs")
        Files.delete(repo.dir.resolve("big.bin"))
        Files.delete(repo.dir.resolve("small.bin"))
        val ok = okOf(read(repo))
        assertEquals(setOf("big.bin", "small.bin"), ok.entries.mapNotNull { it.oldPath }.toSet())
        assertEquals(null, ok.before["big.bin"])
        assertContentEquals(ByteArray(10) { 7 }, ok.before["small.bin"])
    }

    @Test
    fun onlyWantedBlobsAreRead() = committedRepo { repo ->
        repo.write("a.txt", "edited\n")
        val ok = okOf(GitRepository(GitRunner(envOverrides = repo.env)).read(repo.dir, null) { _, _ -> false })
        assertEquals(mapOf("a.txt" to null), ok.before)
    }

    @Test
    fun linkedWorktreeBehavesLikeTheMainOne()= committedRepo { repo ->
        val linked = repo.sibling("linked")
        repo.git("worktree", "add", "-q", "-b", "feature", linked.toString())
        repo.write("a.txt", "edited in the linked tree\n", linked)
        val ok = okOf(read(repo, dir = linked, base = "main"))
        assertEquals(linked.toRealPath(), ok.topLevel.toRealPath())
        assertEquals(listOf(DiffEntry(ChangeStatus.MODIFIED, "a.txt", "a.txt")), ok.entries)
        assertContentEquals("one\n".toByteArray(), ok.before["a.txt"])
    }

    @Test
    fun defaultBaseOrder() = committedRepo { repo ->
        repo.write("a.txt", "two\n")
        repo.commitAll("two")
        repo.gitIn(repo.base, "clone", "-q", "--no-local", repo.dir.toString(), "clone")
        val clone = repo.sibling("clone")
        fun base() = okOf(read(repo, dir = clone)).baseRef

        assertEquals("origin/HEAD", base())
        repo.gitIn(clone, "remote", "set-head", "origin", "-d")
        assertEquals("origin/main", base())
        repo.gitIn(clone, "update-ref", "refs/remotes/origin/master", "HEAD")
        assertEquals("origin/main", base())
        repo.gitIn(clone, "update-ref", "-d", "refs/remotes/origin/main")
        assertEquals("origin/master", base())
        repo.gitIn(clone, "update-ref", "-d", "refs/remotes/origin/master")
        assertEquals("main", base())
        repo.gitIn(clone, "branch", "master")
        assertEquals("main", base())
        repo.gitIn(clone, "branch", "-m", "main", "dev")
        assertEquals("master", base())
    }

    @Test
    fun readsEveryKindOfChangeAgainstTheMergeBase() = withRepo { repo ->
        val content = (1..40).joinToString("") { "line $it of the file\n" }
        repo.write(".gitignore", "ignored.txt\n")
        repo.write("a.txt", "a\r\nb\r\n")
        repo.write("b.txt", "to be deleted\n")
        repo.write("c.txt", content)
        repo.write("sub dir/ünï.txt", "unicode\n")
        val mergeBase = repo.commitAll("base")
        repo.git("config", "--global", "core.quotepath", "true")
        repo.git("checkout", "-q", "-b", "feature")
        repo.write("b2.txt", "committed on the branch\n")
        repo.commitAll("branch")

        repo.write("a.txt", "a\r\nchanged\r\n")
        repo.dir.resolve("b.txt").toFile().delete()
        repo.git("mv", "c.txt", "d.txt")
        repo.write("e.txt", "staged add\n")
        repo.git("add", "e.txt")
        repo.write("sub dir/ünï.txt", "unicode changed\n")
        repo.write("f.txt", "untracked\n")
        repo.write("ignored.txt", "ignored\n")

        val ok = okOf(read(repo, base = "main"))
        assertEquals("main", ok.baseRef)
        assertEquals(mergeBase, ok.mergeBase)
        assertEquals(mergeBase, ok.baseCommit)
        assertEquals(
            setOf(
                DiffEntry(ChangeStatus.MODIFIED, "a.txt", "a.txt"),
                DiffEntry(ChangeStatus.DELETED, "b.txt", null),
                DiffEntry(ChangeStatus.RENAMED, "c.txt", "d.txt"),
                DiffEntry(ChangeStatus.ADDED, null, "e.txt"),
                DiffEntry(ChangeStatus.ADDED, null, "b2.txt"),
                DiffEntry(ChangeStatus.MODIFIED, "sub dir/ünï.txt", "sub dir/ünï.txt"),
            ),
            ok.entries.toSet(),
        )
        assertEquals(listOf("f.txt"), ok.untracked)
        assertContentEquals("a\r\nb\r\n".toByteArray(), ok.before["a.txt"])
        assertContentEquals("to be deleted\n".toByteArray(), ok.before["b.txt"])
        assertContentEquals(content.toByteArray(), ok.before["c.txt"])
        assertContentEquals("unicode\n".toByteArray(), ok.before["sub dir/ünï.txt"])
        assertEquals(setOf("a.txt", "b.txt", "c.txt", "sub dir/ünï.txt"), ok.before.keys)
    }

    @Test
    fun projectDirectoryInsideTheRepositoryResolvesToTheTopLevel() = committedRepo { repo ->
        repo.write("nested/deep/x.txt", "x\n")
        repo.commitAll("nested")
        repo.write("nested/deep/x.txt", "y\n")
        val ok = okOf(read(repo, dir = repo.dir.resolve("nested/deep")))
        assertEquals(repo.dir.toRealPath(), ok.topLevel.toRealPath())
        assertEquals(listOf(DiffEntry(ChangeStatus.MODIFIED, "nested/deep/x.txt", "nested/deep/x.txt")), ok.entries)
    }

    @Test
    fun cleanTreeOnTheBaseBranchHasNoChanges() = committedRepo { repo ->
        val ok = okOf(read(repo))
        assertEquals(emptyList(), ok.entries)
        assertEquals(emptyList(), ok.untracked)
        assertEquals(emptyMap(), ok.before)
    }

    @Test
    fun autocrlfDoesNotChangeTheBeforeBlob() {
        for (autocrlf in listOf(true, false)) {
            withRepo(autocrlf = autocrlf) { repo ->
                repo.writeBytes("a.txt", "x\ny\n".toByteArray())
                repo.commitAll("one")
                repo.writeBytes("a.txt", "x\nz\n".toByteArray())
                val ok = okOf(read(repo))
                assertEquals(listOf(DiffEntry(ChangeStatus.MODIFIED, "a.txt", "a.txt")), ok.entries)
                assertContentEquals("x\ny\n".toByteArray(), ok.before["a.txt"])
            }
        }
    }
}
