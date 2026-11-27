package gg.aquatic.testimpact.gradle

import org.gradle.api.Project
import org.gradle.api.internal.project.ProjectInternal
import org.gradle.testfixtures.ProjectBuilder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TestImpactPluginTest {

    @Test
    fun `applying the plugin id registers TestImpactPlugin`() {
        val project = ProjectBuilder.builder().build()

        project.pluginManager.apply("gg.aquatic.testimpact")

        assertTrue(project.plugins.hasPlugin(TestImpactPlugin::class.java))
    }

    @Test
    fun `applying the plugin adds no tasks`() {
        val project = ProjectBuilder.builder().build()
        val before = project.tasks.names.toSet()

        project.pluginManager.apply("gg.aquatic.testimpact")

        assertEquals(before, project.tasks.names.toSet())
    }

    private fun evaluated(mode: String?): Project {
        val project = ProjectBuilder.builder().build()
        project.pluginManager.apply("java")
        project.pluginManager.apply("gg.aquatic.testimpact")
        if (mode != null) project.extensions.getByType(TestImpactExtension::class.java).mode.set(mode)
        (project as ProjectInternal).evaluate()
        return project
    }

    @Test
    fun `an evaluated project gets a selection task and the Test task takes its file as input`() {
        val project = evaluated(null)
        val test = project.tasks.getByName("test") as org.gradle.api.tasks.testing.Test

        assertTrue("testImpactSelectTest" in project.tasks.names)
        val select = project.tasks.getByName("testImpactSelectTest") as TestImpactSelectTask
        assertEquals("AUTO", select.mode.get())
        assertTrue(test.taskDependencies.getDependencies(test).contains(select))
    }

    @Test
    fun `the selection task waits for the classes it analyses`() {
        val project = evaluated(null)
        val select = project.tasks.getByName("testImpactSelectTest")

        val names = select.taskDependencies.getDependencies(select).map { it.name }

        assertTrue("compileTestJava" in names && "compileJava" in names, names.toString())
    }

    @Test
    fun `mode off in the DSL adds no task, input or action`() {
        val control = ProjectBuilder.builder().build().also {
            it.pluginManager.apply("java")
            (it as ProjectInternal).evaluate()
        }
        val project = evaluated("off")
        val test = project.tasks.getByName("test") as org.gradle.api.tasks.testing.Test
        val stock = control.tasks.getByName("test") as org.gradle.api.tasks.testing.Test

        assertEquals(control.tasks.names.toSet(), project.tasks.names.toSet())
        assertEquals(stock.taskDependencies.getDependencies(stock).map { it.name }, test.taskDependencies.getDependencies(test).map { it.name })
        assertEquals(stock.actions.size, test.actions.size)
    }

    @Test
    fun `mode full still wires the task so the build reports the line`() {
        assertTrue("testImpactSelectTest" in evaluated("FULL").tasks.names)
    }

    @Test
    fun `an invalid mode fails the evaluation naming its source`() {
        val failure = assertFailsWith<Exception> { evaluated("bogus") }
        val messages = generateSequence<Throwable>(failure) { it.cause }.mapNotNull { it.message }.toList()
        assertTrue(messages.any { "testImpact.mode" in it && "bogus" in it }, messages.toString())
    }
}
