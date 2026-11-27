package gg.aquatic.testimpact.gradle

import org.gradle.tooling.GradleConnector
import org.gradle.util.GradleVersion
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TestImpactLauncherFunctionalTest {

    @TempDir
    lateinit var projectDir: Path

    @TempDir
    lateinit var scriptDir: Path

    @Test
    fun `a test launched through the tooling api runs on a clean tree`() {
        ImpactFixture.singleModule(projectDir, pluginLine = "")
        GitFixture.init(projectDir)

        val output = ByteArrayOutputStream()
        GradleConnector.newConnector()
            .forProjectDirectory(projectDir.toFile())
            .useGradleVersion(GradleVersion.current().version)
            .connect()
            .use { connection ->
                connection.newTestLauncher()
                    .withTaskAndTestClasses(":test", listOf("app.CalcTest"))
                    .withArguments("--init-script", initScript().toString(), "--no-watch-fs")
                    .setStandardOutput(output)
                    .setStandardError(output)
                    .run()
            }

        assertTrue(Files.exists(projectDir.resolve("build/test-results/test/TEST-app.CalcTest.xml")), output.toString())
        assertFalse(Files.exists(projectDir.resolve("build/test-results/test/TEST-app.OtherTest.xml")))
    }

    private fun initScript(): Path = ImpactFixture.initScript(scriptDir)
}
