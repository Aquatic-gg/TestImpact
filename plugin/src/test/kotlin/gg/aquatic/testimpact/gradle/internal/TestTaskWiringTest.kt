package gg.aquatic.testimpact.gradle.internal

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

class TestTaskWiringTest {
    private val main = setOf(File("build/classes/main"))
    private val test = setOf(File("build/classes/test"))
    private val functional = setOf(File("build/classes/functionalTest"))
    private val sets = listOf(
        main to setOf(File("src/main/java")),
        test to setOf(File("src/test/java")),
        functional to setOf(File("src/functionalTest/java")),
    )

    @Test
    fun `only the source sets whose classes the task runs against are own`() {
        val own = TestTaskWiring.ownSources(sets, test + main)
        assertEquals(listOf(File("src/main/java"), File("src/test/java")), own)
    }

    @Test
    fun `the functional source set is own for its own task only`() {
        assertEquals(listOf(File("src/functionalTest/java")), TestTaskWiring.ownSources(sets, functional))
    }

    @Test
    fun `no class output on the path leaves no own root`() {
        assertEquals(emptyList(), TestTaskWiring.ownSources(sets, emptySet()))
    }
}
