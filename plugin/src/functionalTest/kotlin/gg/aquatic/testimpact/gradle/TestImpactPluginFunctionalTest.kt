package gg.aquatic.testimpact.gradle

import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals

class TestImpactPluginFunctionalTest {

    @TempDir
    lateinit var projectDir: Path

    @Test
    fun `a minimal project applies the plugin and builds`() {
        projectDir.resolve("settings.gradle.kts").writeText("rootProject.name = \"fixture\"\n")
        projectDir.resolve("build.gradle.kts").writeText(
            """
            plugins { id("gg.aquatic.testimpact") }

            check(pluginManager.hasPlugin("gg.aquatic.testimpact"))
            """.trimIndent() + "\n",
        )

        val result = GradleRunner.create()
            .withProjectDir(projectDir.toFile())
            .withPluginClasspath()
            .withArguments("help", "--configuration-cache", "--warning-mode=fail", "--no-watch-fs", "--stacktrace")
            .forwardOutput()
            .build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":help")?.outcome)
    }
}
