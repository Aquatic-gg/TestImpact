package gg.aquatic.testimpact.compat

import org.gradle.util.GradleVersion
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CompatMatrixTest {
    private fun versions(vararg v: String) = v.map { GradleVersion.version(it) }

    @Test
    fun `parses and sorts the version list`() {
        assertEquals(versions("9.0.0", "9.8.2", "9.9.2"), CompatMatrix.parse("9.9.2,9.0.0,9.8.2"))
    }

    @Test
    fun `drops duplicates`() {
        assertEquals(versions("9.0.0", "9.9.2"), CompatMatrix.parse("9.9.2, 9.0.0,9.9.2"))
    }

    @Test
    fun `rejects null, blank and invalid lists naming the property`() {
        for (input in listOf(null, "", "9.0.0,,9.9.2", "abc")) {
            val failure = assertFailsWith<IllegalStateException>("input $input") { CompatMatrix.parse(input) }
            assertContains(failure.message.orEmpty(), "testimpact.gradleVersions")
        }
    }

    @Test
    fun `jdk 17 and 21 keep every version`() {
        for (jdk in listOf(17, 21)) {
            assertEquals(versions("9.0.0", "9.8.2", "9.9.2"), CompatMatrix.versionsFor(jdk, "9.0.0,9.8.2,9.9.2"))
        }
    }

    @Test
    fun `jdk 25 replaces versions below its floor by the floor`() {
        assertEquals(versions("9.1.0", "9.8.2", "9.9.2"), CompatMatrix.versionsFor(25, "9.0.0,9.8.2,9.9.2"))
    }

    @Test
    fun `jdk 25 leaves versions at or above the floor unchanged`() {
        assertEquals(versions("9.1.0", "9.8.2"), CompatMatrix.versionsFor(25, "9.1.0,9.8.2"))
    }

    @Test
    fun `a jdk without a known floor fails naming it`() {
        val failure = assertFailsWith<IllegalStateException> { CompatMatrix.versionsFor(26, "9.0.0") }
        assertContains(failure.message.orEmpty(), "JDK 26")
    }
}
