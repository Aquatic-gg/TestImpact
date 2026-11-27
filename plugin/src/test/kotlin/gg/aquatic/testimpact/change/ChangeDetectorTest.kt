package gg.aquatic.testimpact.change

import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChangeDetectorTest {

    private val srcRoot = "src/main/kotlin"

    private fun input(repo: TempGitRepo, base: String?, executable: String = "git") = DetectionInput(
        projectDir = repo.dir,
        baseRef = base,
        sourceRoots = listOf(repo.dir.resolve(srcRoot), repo.dir.resolve("src/main/java")),
        resourceRoots = listOf(repo.dir.resolve("src/main/resources")),
        classOutputDirs = listOf(repo.base.resolve("classes")),
        buildLogicRoots = emptyList(),
        gitExecutable = executable,
    )

    private fun detect(repo: TempGitRepo, base: String? = "main", dir: Path = repo.dir): ChangeDetection =
        ChangeDetector.detect(input(repo, base).copy(projectDir = dir), repo.env)

    private fun known(detection: ChangeDetection): ChangeDetection.Known {
        if (detection is ChangeDetection.Unknown) throw AssertionError("unexpected $detection")
        return assertIs<ChangeDetection.Known>(detection)
    }

    private fun assertUnknown(reason: UnknownReason, detection: ChangeDetection) {
        assertIs<ChangeDetection.Unknown>(detection)
        assertEquals(reason, detection.reason)
        assertTrue(detection.detail.length <= 500)
    }

    private fun ChangeDetection.Known.file(path: String): ChangedFile =
        files.singleOrNull { it.path == path } ?: throw AssertionError("no $path in ${files.map { it.path }}")

    private fun compiled(repo: TempGitRepo, vararg names: String) {
        for (name in names) repo.writeBytes("classes/${name.replace('.', '/')}.class", ByteArray(0), repo.base)
    }

    private fun kt(pkg: String, vararg types: String) = "package $pkg\n\n" + types.joinToString("") { "class $it\n" }

    private fun large(name: String) =
        "package p\n\nclass $name {\n" + (1..30).joinToString("") { "    fun f$it() = $it\n" } + "}\n"

    private fun path(name: String) = "$srcRoot/p/$name.kt"

    private fun withFeature(files: Map<String, String> = emptyMap(), autocrlf: Boolean? = null, block: (TempGitRepo) -> Unit) {
        TempGitRepo.create(autocrlf = autocrlf).use { repo ->
            repo.write("README.md", "base\n")
            files.forEach { (name, text) -> repo.write(name, text) }
            repo.commitAll("base")
            repo.git("checkout", "-q", "-b", "feature")
            block(repo)
        }
    }

    @Test
    fun addedFilesCommittedStagedAndUntracked() = withFeature { repo ->
        compiled(repo, "p.A", "p.B", "p.C")
        repo.write(path("A"), kt("p", "A"))
        repo.commitAll("committed")
        repo.write(path("B"), kt("p", "B"))
        repo.git("add", path("B"))
        repo.write(path("C"), kt("p", "C"))

        val known = known(detect(repo))
        assertEquals(listOf(path("A"), path("B"), path("C")), known.files.map { it.path })
        assertEquals(
            listOf(ChangeStatus.ADDED, ChangeStatus.ADDED, ChangeStatus.UNTRACKED),
            known.files.map { it.status },
        )
        assertEquals(listOf("p.A"), known.file(path("A")).after?.classes)
        assertEquals(listOf("p.C"), known.file(path("C")).reachedClasses)
        assertTrue(known.files.all { it.before == null && it.after?.classesResolved == true })
        assertTrue(known.files.all { it.after?.kind == FileKind.SOURCE })
    }

    @Test
    fun modifiedFilesCommittedStagedAndUnstaged() = withFeature(
        mapOf(path("X") to kt("p", "X"), path("Y") to kt("p", "Y"), path("Z") to kt("p", "Z")),
    ) { repo ->
        compiled(repo, "p.X", "p.Y", "p.Z", "p.X2", "p.Y2", "p.Z2")
        repo.write(path("X"), kt("p", "X", "X2"))
        repo.commitAll("committed")
        repo.write(path("Y"), kt("p", "Y", "Y2"))
        repo.git("add", path("Y"))
        repo.write(path("Z"), kt("p", "Z", "Z2"))

        val known = known(detect(repo))
        assertEquals(listOf(path("X"), path("Y"), path("Z")), known.files.map { it.path })
        for (name in listOf("X", "Y", "Z")) {
            val file = known.file(path(name))
            assertEquals(ChangeStatus.MODIFIED, file.status)
            assertEquals(listOf("p.$name"), file.before?.classes)
            assertEquals(listOf("p.$name", "p.${name}2"), file.after?.classes)
        }
    }

    @Test
    fun classRemovedFromAModifiedFileStaysInBefore() = withFeature(mapOf(path("M") to kt("p", "Old", "Keep"))) { repo ->
        compiled(repo, "p.Keep")
        repo.write(path("M"), kt("p", "Keep"))
        val file = known(detect(repo)).file(path("M"))
        assertEquals(listOf("p.Keep", "p.Old"), file.before?.classes)
        assertEquals(listOf("p.Keep"), file.after?.classes)
        assertEquals(listOf("p.Keep", "p.Old"), file.reachedClasses)
    }

    @Test
    fun deletedFilesCommittedAndWorktreeOnly() = withFeature(
        mapOf(path("A") to kt("p", "A"), path("B") to kt("p", "B", "B2")),
    ) { repo ->
        repo.git("rm", "-q", path("A"))
        repo.git("commit", "-q", "-m", "delete A")
        Files.delete(repo.dir.resolve(path("B")))

        val known = known(detect(repo))
        assertEquals(listOf(path("A"), path("B")), known.files.map { it.path })
        assertTrue(known.files.all { it.status == ChangeStatus.DELETED && it.after == null })
        assertEquals(listOf("p.A"), known.file(path("A")).reachedClasses)
        assertEquals(listOf("p.B", "p.B2"), known.file(path("B")).reachedClasses)
        assertEquals(listOf("p.B", "p.B2"), known.file(path("B")).before?.classes)
    }

    @Test
    fun renameWithClassRename() = withFeature(mapOf(path("Old") to large("Old"))) { repo ->
        compiled(repo, "p.New")
        repo.git("mv", path("Old"), path("New"))
        repo.write(path("New"), large("New"))
        val known = known(detect(repo))
        val file = known.files.single()
        assertEquals(ChangeStatus.RENAMED, file.status)
        assertEquals(path("Old"), file.before?.path)
        assertEquals(path("New"), file.after?.path)
        assertEquals(listOf("p.Old"), file.before?.classes)
        assertEquals(listOf("p.New"), file.after?.classes)
        assertEquals(listOf("p.New", "p.Old"), file.reachedClasses)
    }

    @Test
    fun renameBelowTheSimilarityThresholdIsDeleteAndAdd() = withFeature(mapOf(path("Old") to large("Old"))) { repo ->
        compiled(repo, "p.New")
        repo.git("rm", "-q", path("Old"))
        repo.write(path("New"), kt("p", "New"))
        repo.git("add", path("New"))
        val known = known(detect(repo))
        assertEquals(listOf(path("New"), path("Old")), known.files.map { it.path })
        assertEquals(ChangeStatus.ADDED, known.file(path("New")).status)
        assertEquals(ChangeStatus.DELETED, known.file(path("Old")).status)
        assertEquals(listOf("p.Old"), known.file(path("Old")).reachedClasses)
        assertEquals(listOf("p.New"), known.file(path("New")).reachedClasses)
    }

    @Test
    fun untrackedFilesHonourGitignore() = withFeature(mapOf(".gitignore" to "ignored/\n*.log\n")) { repo ->
        compiled(repo, "p.Kept")
        repo.write(path("Kept"), kt("p", "Kept"))
        repo.write("$srcRoot/ignored/Hidden.kt", kt("ignored", "Hidden"))
        repo.write("debug.log", "noise\n")
        val known = known(detect(repo))
        assertEquals(listOf(path("Kept")), known.files.map { it.path })
        assertEquals(ChangeStatus.UNTRACKED, known.files.single().status)
    }

    @Test
    fun kindsOfNonSourceFiles() = withFeature { repo ->
        repo.write("src/main/resources/app/config.yml", "a: 1\n")
        repo.write("build.gradle.kts", "// build\n")
        repo.write("docs/readme.md", "docs\n")
        repo.write("src/main/kotlin/p/notes.txt", "notes\n")
        val known = known(detect(repo))
        val resource = known.file("src/main/resources/app/config.yml").after
        assertEquals(FileKind.RESOURCE, resource?.kind)
        assertEquals("app/config.yml", resource?.resourcePath)
        assertEquals(FileKind.BUILD_LOGIC, known.file("build.gradle.kts").after?.kind)
        assertEquals(FileKind.OTHER, known.file("docs/readme.md").after?.kind)
        assertEquals(FileKind.OTHER, known.file("src/main/kotlin/p/notes.txt").after?.kind)
        assertTrue(known.files.all { it.reachedClasses.isEmpty() })
    }

    @Test
    fun lineEndingConfigurationDoesNotChangeTheResult() {
        val results = listOf(true, false).map { autocrlf ->
            var files: List<ChangedFile> = emptyList()
            withFeature(mapOf(path("A") to kt("p", "A", "Gone")), autocrlf = autocrlf) { repo ->
                compiled(repo, "p.A", "p.Fresh")
                repo.writeBytes(path("A"), "package p\r\n\r\nclass A\r\nclass Extra\r\n".toByteArray())
                repo.writeBytes(path("Fresh"), "package p\r\n\r\nclass Fresh\r\n".toByteArray())
                compiled(repo, "p.Extra")
                files = known(detect(repo)).files
            }
            files
        }
        assertEquals(results[0], results[1])
        assertEquals(listOf("p.A", "p.Extra", "p.Gone"), results[0].first { it.path == path("A") }.reachedClasses)
    }

    @Test
    fun unicodeAndSpacePathsWithQuotedGlobalConfig() = withFeature(
        mapOf("$srcRoot/sub dir/ünï.kt" to kt("p", "Uni")),
    ) { repo ->
        repo.git("config", "--global", "core.quotepath", "true")
        compiled(repo, "p.Uni", "p.Two")
        repo.write("$srcRoot/sub dir/ünï.kt", kt("p", "Uni", "Two"))
        repo.write("$srcRoot/sub dir/日本語.kt", kt("p", "Two"))
        val known = known(detect(repo))
        assertEquals(listOf("$srcRoot/sub dir/ünï.kt", "$srcRoot/sub dir/日本語.kt"), known.files.map { it.path })
        assertEquals(listOf("p.Uni"), known.files[0].before?.classes)
        assertEquals(listOf("p.Two", "p.Uni"), known.files[0].after?.classes)
    }

    @Test
    fun projectDirectoryInsideTheRepositoryYieldsRepositoryRelativePaths() = withFeature { repo ->
        compiled(repo, "p.A")
        repo.write(path("A"), kt("p", "A"))
        val inner = repo.dir.resolve("src/main")
        val known = known(detect(repo, dir = inner))
        assertEquals(listOf(path("A")), known.files.map { it.path })
        assertEquals(FileKind.SOURCE, known.files.single().after?.kind)
    }

    @Test
    fun submoduleGitlinkIsAnOtherFile() = withFeature { repo ->
        val sub = repo.sibling("subrepo")
        repo.gitIn(repo.base, "init", "-q", "-b", "main", sub.toString())
        repo.gitIn(sub, "config", "commit.gpgsign", "false")
        repo.write("lib.txt", "lib\n", sub)
        repo.commitAll("lib", sub)
        repo.git("-c", "protocol.file.allow=always", "submodule", "add", "-q", sub.toString(), "libs/sub")
        val known = known(detect(repo))
        val gitlink = known.file("libs/sub")
        assertEquals(ChangeStatus.ADDED, gitlink.status)
        assertEquals(FileKind.OTHER, gitlink.after?.kind)
        assertTrue(gitlink.reachedClasses.isEmpty())
    }

    @Test
    fun afterSideWithoutCompiledClassIsUnresolved() = withFeature { repo ->
        compiled(repo, "p.Built")
        repo.write(path("Built"), kt("p", "Built"))
        repo.write(path("Missing"), kt("p", "Missing"))
        val known = known(detect(repo))
        assertEquals(true, known.file(path("Built")).after?.classesResolved)
        val missing = known.file(path("Missing")).after
        assertEquals(false, missing?.classesResolved)
        assertEquals(emptyList(), missing?.classes)
    }

    @Test
    fun classCompiledOnlyIntoAJarResolves() = withFeature { repo ->
        val jar = repo.base.resolve("dependency.jar")
        ZipOutputStream(Files.newOutputStream(jar)).use { zip ->
            zip.putNextEntry(ZipEntry("p/InJar.class"))
            zip.closeEntry()
        }
        repo.write(path("InJar"), kt("p", "InJar"))
        repo.write(path("NotInJar"), kt("p", "NotInJar"))
        val known = known(ChangeDetector.detect(input(repo, "main").copy(classOutputDirs = listOf(jar)), repo.env))
        assertEquals(true, known.file(path("InJar")).after?.classesResolved)
        assertEquals(listOf("p.InJar"), known.file(path("InJar")).after?.classes)
        assertEquals(false, known.file(path("NotInJar")).after?.classesResolved)
    }

    @Test
    fun sourceOfAModuleOutsideTheClasspathIsNotAnObstacle() = withFeature { repo ->
        val classes = repo.dir.resolve("own/build/classes")
        Files.createDirectories(classes)
        repo.write("far/src/main/java/q/Far.java", "package q;\n\npublic class Far {}\n")
        repo.write("own/src/main/java/q/Near.java", "package q;\n\npublic class Near {}\n")
        val detection = ChangeDetector.detect(
            input(repo, "main").copy(classOutputDirs = listOf(classes), conventionalRoots = true),
            repo.env,
        )
        val known = known(detection)
        val far = known.file("far/src/main/java/q/Far.java").after
        assertEquals(true, far?.classesResolved)
        assertEquals(listOf("q.Far"), far?.classes)
        assertEquals(false, known.file("own/src/main/java/q/Near.java").after?.classesResolved)
    }

    @Test
    fun corruptJarMakesDetectionUnknown() = withFeature { repo ->
        val jar = repo.base.resolve("broken.jar")
        Files.write(jar, "not a zip file".toByteArray())
        repo.write(path("A"), kt("p", "A"))
        val detection = ChangeDetector.detect(input(repo, "main").copy(classOutputDirs = listOf(jar)), repo.env)
        assertUnknown(UnknownReason.ANALYSIS_ERROR, detection)
    }

    @Test
    fun emptyClassOutputCannotVerifyDeclaredClasses() = withFeature { repo ->
        repo.write(path("A"), kt("p", "A"))
        val detection = ChangeDetector.detect(input(repo, "main").copy(classOutputDirs = emptyList()), repo.env)
        assertEquals(false, known(detection).files.single().after?.classesResolved)
    }

    @Test
    fun oversizedSourceIsUnresolved() = withFeature { repo ->
        compiled(repo, "p.Big")
        repo.write(path("Big"), kt("p", "Big") + "// " + "x".repeat(4 * 1024 * 1024) + "\n")
        assertEquals(false, known(detect(repo)).files.single().after?.classesResolved)
    }

    @Test
    fun unreadableBeforeBlobIsUnresolved() = withFeature(mapOf(path("A") to "package p\n\nclass A\n" + "// ${"y".repeat(4 * 1024 * 1024)}\n")) { repo ->
        Files.delete(repo.dir.resolve(path("A")))
        val file = known(detect(repo)).files.single()
        assertEquals(ChangeStatus.DELETED, file.status)
        assertEquals(false, file.before?.classesResolved)
    }

    @Test
    fun repeatedRunsAreEqual() = withFeature(mapOf(path("A") to kt("p", "A"))) { repo ->
        compiled(repo, "p.B", "p.C")
        repo.write(path("A"), kt("p", "A", "A2"))
        repo.write(path("B"), kt("p", "B"))
        repo.write(path("C"), kt("p", "C"))
        val first = detect(repo)
        assertEquals(first, detect(repo))
        val paths = known(first).files.map { it.path }
        assertEquals(listOf(path("A"), path("B"), path("C")), paths)
        assertEquals(paths.sorted(), paths)
    }

    @Test
    fun onTheBaseBranchOnlyUncommittedEditsAreListed() {
        TempGitRepo.create().use { repo ->
            repo.write(path("A"), kt("p", "A"))
            repo.write(path("B"), kt("p", "B"))
            repo.commitAll("base")
            compiled(repo, "p.A", "p.A2")
            repo.write(path("A"), kt("p", "A", "A2"))
            val known = known(detect(repo))
            assertEquals(known.baseCommit, known.mergeBase)
            assertEquals(listOf(path("A")), known.files.map { it.path })
            assertEquals(ChangeStatus.MODIFIED, known.files.single().status)
        }
    }

    @Test
    fun cleanTreeHasAKnownEmptyChangeSet() = withFeature { repo ->
        val known = known(detect(repo))
        assertEquals(emptyList(), known.files)
        assertFalse(known.mergeBase.isEmpty())
    }

    @Test
    fun everyUnknownCaseIsUnknownNeverAnEmptyKnown() {
        TempGitRepo.create(init = false).use { repo ->
            assertUnknown(UnknownReason.NOT_A_REPOSITORY, detect(repo))
        }
        TempGitRepo.create().use { repo ->
            assertUnknown(UnknownReason.NO_HEAD_COMMIT, detect(repo))
        }
        TempGitRepo.create().use { repo ->
            repo.write("a.txt", "one\n")
            repo.commitAll("one")
            assertUnknown(UnknownReason.BASE_REF_NOT_FOUND, detect(repo, base = "missing"))
            assertUnknown(UnknownReason.BASE_REF_NOT_FOUND, detect(repo, base = "--output=x"))
            assertUnknown(
                UnknownReason.GIT_UNAVAILABLE,
                ChangeDetector.detect(input(repo, "main", repo.sibling("no-git").toString()), repo.env),
            )
            repo.write("a.txt", "two\n")
            repo.commitAll("two")
            repo.gitIn(repo.base, "clone", "-q", "--no-local", "--depth", "1", repo.dir.toString(), "shallow")
            assertUnknown(UnknownReason.SHALLOW_CLONE, detect(repo, base = "main", dir = repo.sibling("shallow")))
            repo.git("checkout", "-q", "--detach")
            assertUnknown(UnknownReason.DETACHED_HEAD, detect(repo))
            repo.git("checkout", "-q", "--orphan", "lone")
            repo.git("rm", "-rf", "-q", ".")
            repo.write("b.txt", "other\n")
            repo.commitAll("lone")
            assertUnknown(UnknownReason.NO_MERGE_BASE, detect(repo))
        }
    }

    @Test
    fun inheritedRepositoryEnvironmentIsIgnored() = withFeature { repo ->
        val other = repo.sibling("other")
        repo.gitIn(repo.base, "init", "-q", "-b", "main", other.toString())
        compiled(repo, "p.A")
        repo.write(path("A"), kt("p", "A"))
        val env = repo.env + mapOf("GIT_DIR" to other.resolve(".git").toString(), "GIT_WORK_TREE" to other.toString())
        val known = known(ChangeDetector.detect(input(repo, "main"), env))
        assertEquals(listOf(path("A")), known.files.map { it.path })
        assertNull(known.files.single().before)
    }
}
