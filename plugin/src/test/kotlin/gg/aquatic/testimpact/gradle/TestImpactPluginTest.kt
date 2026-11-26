package gg.aquatic.testimpact.gradle

import org.gradle.testfixtures.ProjectBuilder
import kotlin.test.Test
import kotlin.test.assertEquals
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
}
