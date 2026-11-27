package gg.aquatic.testimpact.gradle

import org.gradle.testfixtures.ProjectBuilder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TestImpactExtensionTest {

    @Test
    fun `the extension is registered as testImpact`() {
        val project = ProjectBuilder.builder().build()

        project.pluginManager.apply("gg.aquatic.testimpact")

        assertNotNull(project.extensions.findByType(TestImpactExtension::class.java))
        assertNotNull(project.extensions.findByName("testImpact"))
    }

    @Test
    fun `both properties are empty by default`() {
        val project = ProjectBuilder.builder().build()
        project.pluginManager.apply("gg.aquatic.testimpact")

        val extension = project.extensions.getByType(TestImpactExtension::class.java)

        assertTrue(extension.dynamicPackages.get().isEmpty())
        assertTrue(extension.alwaysRun.get().isEmpty())
    }

    @Test
    fun `values set on the properties are read back`() {
        val project = ProjectBuilder.builder().build()
        project.pluginManager.apply("gg.aquatic.testimpact")
        val extension = project.extensions.getByType(TestImpactExtension::class.java)

        extension.dynamicPackages.add("com.example.plugins")
        extension.alwaysRun.addAll("*SmokeTest", "com.example.Slow")

        assertEquals(setOf("com.example.plugins"), extension.dynamicPackages.get())
        assertEquals(setOf("*SmokeTest", "com.example.Slow"), extension.alwaysRun.get())
    }
}
