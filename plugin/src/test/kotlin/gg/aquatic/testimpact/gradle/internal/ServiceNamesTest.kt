package gg.aquatic.testimpact.gradle.internal

import gg.aquatic.testimpact.gradle.SelectionInputsService
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Test
import java.io.File
import java.net.URL
import java.net.URLClassLoader
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

class ServiceNamesTest {
    private fun namesFromFreshLoader(): Pair<String, String> {
        val urls: Array<URL> = System.getProperty("java.class.path").split(File.pathSeparator).map { File(it).toURI().toURL() }.toTypedArray()
        URLClassLoader(urls, ClassLoader.getPlatformClassLoader()).use { loader ->
            val type = Class.forName(ServiceNames::class.java.name, true, loader)
            val instance = type.getField("INSTANCE").get(null)
            fun read(prefix: String): String =
                type.methods.single { it.name.startsWith(prefix) }.invoke(instance) as String
            return read("getClassGraph") to read("getSelectionInputs")
        }
    }

    @Test
    fun `one class loader keeps its names`() {
        assertEquals(ServiceNames.classGraph, ServiceNames.classGraph)
        assertNotEquals(ServiceNames.classGraph, ServiceNames.selectionInputs)
        assertTrue(ServiceNames.classGraph.startsWith("testImpactClassGraph-"))
        assertTrue(ServiceNames.selectionInputs.startsWith("testImpactSelectionInputs-"))
    }

    @Test
    fun `two class loaders get distinct names`() {
        val first = namesFromFreshLoader()
        val second = namesFromFreshLoader()
        assertNotEquals(first.first, second.first)
        assertNotEquals(first.second, second.second)
        assertNotEquals(first.first, ServiceNames.classGraph)
        assertNotEquals(first.second, ServiceNames.selectionInputs)
    }

    @Test
    fun `distinct names register separate services`() {
        val project = ProjectBuilder.builder().build()
        val foreignName = namesFromFreshLoader().second
        val services = project.gradle.sharedServices
        val ours = services.registerIfAbsent(ServiceNames.selectionInputs, SelectionInputsService::class.java) {}
        val foreign = services.registerIfAbsent(foreignName, SelectionInputsService::class.java) {}
        assertNotSame(ours.get(), foreign.get())
    }
}
