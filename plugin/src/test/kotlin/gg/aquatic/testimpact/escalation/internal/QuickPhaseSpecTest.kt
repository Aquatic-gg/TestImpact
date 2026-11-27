package gg.aquatic.testimpact.escalation.internal

import org.gradle.api.provider.Provider
import org.gradle.api.file.RegularFile
import org.gradle.api.tasks.testing.Test
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class QuickPhaseSpecTest {
    @TempDir
    lateinit var temp: Path

    private val project by lazy { ProjectBuilder.builder().withProjectDir(temp.resolve("p").also(Files::createDirectories).toFile()).build() }

    private var counter = 0

    private fun task(): Test = project.tasks.register("quick${counter++}", Test::class.java).get()

    private fun file(name: String, text: String?): Provider<RegularFile> {
        val path = temp.resolve(name)
        if (text != null) Files.writeString(path, text) else Files.deleteIfExists(path)
        return project.provider { project.layout.projectDirectory.file(path.toString()) }
    }

    private val required = "testimpact-escalation 1\nfull-run-required=true\nunknown-failure=false\nlast-phase=full\nlast-result=red\nred=app.CalcTest\n"
    private val selected = "testimpact-selection 1\nselected\napp.OtherTest\n"

    private fun spec(
        state: String? = required,
        selection: String? = selected,
        auto: Boolean = true,
        userTests: Boolean = false,
    ) = QuickPhaseSpec(file("state", state), file("selection", selection), auto, userTests, emptySet())

    @kotlin.test.Test
    fun `a red class and a selected class run`() = assertTrue(spec().isSatisfiedBy(task()))

    @kotlin.test.Test
    fun `a red class runs without a selection file`() = assertTrue(spec(selection = null).isSatisfiedBy(task()))

    @kotlin.test.Test
    fun `nothing red and nothing selected skips`() {
        assertFalse(spec(state = required.replace("red=app.CalcTest\n", ""), selection = "testimpact-selection 1\nfull\n").isSatisfiedBy(task()))
    }

    @kotlin.test.Test
    fun `a selection alone runs when a full run is owed`() {
        assertTrue(spec(state = required.replace("red=app.CalcTest\n", "")).isSatisfiedBy(task()))
    }

    @kotlin.test.Test
    fun `no state or no owed run skips`() {
        assertFalse(spec(state = null).isSatisfiedBy(task()))
        assertFalse(spec(state = required.replace("full-run-required=true", "full-run-required=false")).isSatisfiedBy(task()))
    }

    @kotlin.test.Test
    fun `a damaged state runs the selection`() {
        assertTrue(spec(state = "garbage").isSatisfiedBy(task()))
        assertFalse(spec(state = "garbage", selection = null).isSatisfiedBy(task()))
    }

    @kotlin.test.Test
    fun `mode other than auto and named tests skip`() {
        assertFalse(spec(auto = false).isSatisfiedBy(task()))
        assertFalse(spec(userTests = true).isSatisfiedBy(task()))
    }

    @kotlin.test.Test
    fun `own command line patterns skip`() {
        val task = task()
        task.setTestNameIncludePatterns(listOf("app.CalcTest"))
        assertFalse(spec().isSatisfiedBy(task))
    }

    @kotlin.test.Test
    fun `the reason is stable`() = assertEquals("TestImpact: no red run to follow up", QuickPhaseSpec.REASON)
}
