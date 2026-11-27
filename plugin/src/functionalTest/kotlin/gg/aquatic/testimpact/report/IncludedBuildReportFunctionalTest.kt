package gg.aquatic.testimpact.report

import gg.aquatic.testimpact.fallback.FallbackFixture
import groovy.json.JsonSlurper
import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/** A change inside an included build can reach no test classpath, so it must select a full run and never zero tests. */
class IncludedBuildReportFunctionalTest {

    private lateinit var projectDir: Path

    @BeforeEach
    fun writeFixture() {
        projectDir = Files.createTempDirectory("included-build-fixture")
        write("settings.gradle.kts", "rootProject.name = \"app\"\nincludeBuild(\"conventions\")\n")
        write("build.gradle.kts", "plugins {\n    java\n    id(\"gg.aquatic.testimpact\")\n}\n")
        write("src/main/java/app/App.java", "package app;\n\npublic class App {\n}\n")
        write("conventions/settings.gradle.kts", "rootProject.name = \"conventions\"\n")
        write("conventions/build.gradle.kts", "")
        write("conventions/src/main/java/conv/Conv.java", "package conv;\n\npublic class Conv {\n    int value = 1;\n}\n")
        write("conventions/build/classes/java/main/conv/Conv.class", "placeholder")
        write(".gitignore", ".gradle/\n/build/\n")
        git("init", "-b", "main")
        git("add", "-A")
        git("add", "-f", "conventions/build/classes/java/main/conv/Conv.class")
        git("-c", "user.name=t", "-c", "user.email=t@t", "-c", "commit.gpgsign=false", "commit", "-m", "initial")
        git("checkout", "-b", "feature")
        FallbackFixture.primeHistory(projectDir)
    }

    @AfterEach
    fun cleanUp() {
        projectDir.toFile().deleteRecursively()
    }

    @Test
    fun `a changed source of an included build runs the task in full`() {
        val conv = projectDir.resolve("conventions/src/main/java/conv/Conv.java")
        Files.writeString(conv, Files.readString(conv).replace("value = 1", "value = 2"))

        GradleRunner.create().withProjectDir(projectDir.toFile()).withPluginClasspath()
            .withArguments("testImpactReport", "--base", "main", "--warning-mode=fail", "--no-watch-fs", "--stacktrace")
            .forwardOutput().build()

        val file = projectDir.resolve("build/reports/testimpact/report.json")
        @Suppress("UNCHECKED_CAST")
        val report = JsonSlurper().parseText(Files.readString(file)) as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val forced = assertNotNull(report["forced"]) as Map<String, Any?>
        assertEquals("BUILD_LOGIC", forced["reason"])
        @Suppress("UNCHECKED_CAST")
        val task = (report["tasks"] as List<Map<String, Any?>>).single { it["path"] == ":test" }
        assertEquals("full", task["result"])
    }

    private fun write(relative: String, content: String) {
        val target = projectDir.resolve(relative)
        Files.createDirectories(target.parent)
        Files.writeString(target, content)
    }

    private fun git(vararg args: String) {
        val process = ProcessBuilder(listOf("git") + args)
            .directory(projectDir.toFile())
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .start()
        check(process.waitFor(60, TimeUnit.SECONDS) && process.exitValue() == 0) { "git ${args.joinToString(" ")} failed" }
    }
}
