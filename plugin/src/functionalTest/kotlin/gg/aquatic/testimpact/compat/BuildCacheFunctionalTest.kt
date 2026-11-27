package gg.aquatic.testimpact.compat

import gg.aquatic.testimpact.gradle.GitFixture
import gg.aquatic.testimpact.gradle.ImpactFixture
import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.copyTo
import kotlin.io.path.createDirectories
import kotlin.io.path.isDirectory
import kotlin.io.path.relativeTo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BuildCacheFunctionalTest {

    @TempDir
    lateinit var projectDir: Path

    @TempDir
    lateinit var cacheDir: Path

    @TempDir
    lateinit var otherDir: Path

    @Test
    fun `a cached partial run is restored only for the same selection`() {
        prepare()

        val first = run(projectDir, "test")
        assertEquals(TaskOutcome.SUCCESS, first.task(":test")?.outcome, first.output)

        val restored = run(projectDir, "clean", "test")
        assertEquals(TaskOutcome.FROM_CACHE, restored.task(":test")?.outcome, restored.output)
        assertTrue(Files.exists(report(projectDir, "app.CalcTest")))
        assertFalse(Files.exists(report(projectDir, "app.OtherTest")))

        val script = Files.readString(projectDir.resolve(BUILD_SCRIPT))
        Files.writeString(projectDir.resolve(BUILD_SCRIPT), script + WIDER_SELECTION)
        val wider = run(projectDir, "clean", "test")
        assertEquals(TaskOutcome.SUCCESS, wider.task(":test")?.outcome, wider.output)
        assertTrue(Files.exists(report(projectDir, "app.OtherTest")))

        Files.writeString(projectDir.resolve(BUILD_SCRIPT), script)
        val back = run(projectDir, "clean", "test")
        assertEquals(TaskOutcome.FROM_CACHE, back.task(":test")?.outcome, back.output)
        assertFalse(Files.exists(report(projectDir, "app.OtherTest")))
    }

    @Test
    fun `a relocated checkout with the same change restores the cached run`() {
        prepare()
        val first = run(projectDir, "test")
        assertEquals(TaskOutcome.SUCCESS, first.task(":test")?.outcome, first.output)

        copyTree(projectDir, otherDir)
        val relocated = run(otherDir, "clean", "test")

        assertEquals(TaskOutcome.FROM_CACHE, relocated.task(":test")?.outcome, relocated.output)
        assertTrue(Files.exists(report(otherDir, "app.CalcTest")))
        assertFalse(Files.exists(report(otherDir, "app.OtherTest")))
    }

    @Test
    fun `a relocated checkout with another selection runs the tests`() {
        prepare()
        val first = run(projectDir, "test")
        assertEquals(TaskOutcome.SUCCESS, first.task(":test")?.outcome, first.output)

        copyTree(projectDir, otherDir)
        Files.writeString(otherDir.resolve(BUILD_SCRIPT), Files.readString(otherDir.resolve(BUILD_SCRIPT)) + WIDER_SELECTION)
        val relocated = run(otherDir, "clean", "test")

        assertEquals(TaskOutcome.SUCCESS, relocated.task(":test")?.outcome, relocated.output)
        assertTrue(Files.exists(report(otherDir, "app.OtherTest")))
    }

    private fun prepare() {
        val cache = cacheDir.toString().replace('\\', '/')
        ImpactFixture.singleModule(projectDir, settingsExtra = "buildCache { local { directory = file(\"$cache\") } }\n")
        GitFixture.init(projectDir)
        ImpactFixture.file(projectDir, "src/main/java/app/Calc.java", ImpactFixture.calc(2))
    }

    private fun copyTree(from: Path, to: Path) {
        Files.walk(from).use { paths ->
            paths.filter { it.relativeTo(from).firstOrNull()?.toString() !in BUILD_STATE }.forEach { source ->
                val target = to.resolve(source.relativeTo(from).toString())
                if (source.isDirectory()) target.createDirectories() else source.copyTo(target, overwrite = true)
            }
        }
    }

    private fun report(root: Path, className: String): Path = root.resolve("build/test-results/test/TEST-$className.xml")

    private fun run(dir: Path, vararg args: String): BuildResult =
        GradleRunner.create()
            .withProjectDir(dir.toFile())
            .withPluginClasspath()
            .withArguments(args.toList() + listOf("--build-cache", "--configuration-cache", "--warning-mode=fail", "--no-watch-fs", "--stacktrace"))
            .forwardOutput()
            .build()

    private companion object {
        val BUILD_STATE = setOf("build", ".gradle", ".kotlin")

        const val BUILD_SCRIPT = "build.gradle.kts"
        const val WIDER_SELECTION = "\ntestImpact { alwaysRun.add(\"*OtherTest\") }\n"
    }
}
