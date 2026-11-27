package gg.aquatic.testimpact.gradle

import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class TestImpactExtensionFunctionalTest {

    @TempDir
    lateinit var projectDir: Path

    @Test
    fun `a build script can set both properties and the configuration cache is reused`() {
        projectDir.resolve("settings.gradle.kts").writeText("rootProject.name = \"fixture\"\n")
        projectDir.resolve("build.gradle.kts").writeText(
            """
            plugins { id("gg.aquatic.testimpact") }

            testImpact {
                dynamicPackages.add("com.example.plugins")
                alwaysRun.addAll("*IntegrationTest", "com.example.SlowTest")
            }

            check(testImpact.dynamicPackages.get() == setOf("com.example.plugins"))
            check(testImpact.alwaysRun.get() == setOf("*IntegrationTest", "com.example.SlowTest"))
            """.trimIndent() + "\n",
        )

        val first = run()
        val second = run()

        assertEquals(TaskOutcome.SUCCESS, first.task(":help")?.outcome)
        assertEquals(TaskOutcome.SUCCESS, second.task(":help")?.outcome)
        assertContains(second.output, "Reusing configuration cache")
    }

    private fun run() = GradleRunner.create()
        .withProjectDir(projectDir.toFile())
        .withPluginClasspath()
        .withArguments("help", "--configuration-cache", "--warning-mode=fail", "--no-watch-fs", "--stacktrace")
        .forwardOutput()
        .build()
}
