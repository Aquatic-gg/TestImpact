package gg.aquatic.testimpact.escalation.internal

import gg.aquatic.testimpact.escalation.EscalationService
import org.gradle.api.Project
import org.gradle.api.file.RegularFile
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.testing.Test
import org.gradle.testfixtures.ProjectBuilder
import java.nio.file.Files
import java.nio.file.Path

/** A ProjectBuilder project with Test tasks, state files and a real escalation service, for the wiring tests. */
internal class EscalationTestProject(private val temp: Path) {
    val project: Project = ProjectBuilder.builder().withProjectDir(temp.resolve("p").also(Files::createDirectories).toFile()).build()

    val service: Provider<EscalationService> =
        project.gradle.sharedServices.registerIfAbsent("escalationTest", EscalationService::class.java) {}

    private var counter = 0

    fun task(name: String = "test${counter++}"): Test = project.tasks.register(name, Test::class.java).get()

    fun state(text: String?): Provider<RegularFile> {
        val path = temp.resolve("state")
        if (text != null) Files.writeString(path, text) else Files.deleteIfExists(path)
        return project.provider { project.layout.projectDirectory.file(path.toString()) }
    }

    companion object {
        const val REQUIRED = "testimpact-escalation 1\nfull-run-required=true\nunknown-failure=false\nlast-phase=full\nlast-result=red\nred=app.CalcTest\n"
        val CLEAN = REQUIRED.replace("full-run-required=true", "full-run-required=false").replace("red=app.CalcTest\n", "").replace("last-result=red", "last-result=green")
    }
}
