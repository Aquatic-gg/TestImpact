package gg.aquatic.testimpact.gradle

import gg.aquatic.testimpact.gradle.internal.ModeResolution
import gg.aquatic.testimpact.gradle.internal.TestImpactMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ModeResolutionTest {

    @Test
    fun `no source set gives auto`() {
        assertEquals(TestImpactMode.AUTO, ModeResolution.resolve(null, null, null))
    }

    @Test
    fun `blank values count as unset`() {
        assertEquals(TestImpactMode.OFF, ModeResolution.resolve("  ", "", "off"))
    }

    @Test
    fun `the gradle property beats the environment and the dsl`() {
        assertEquals(TestImpactMode.FULL, ModeResolution.resolve("full", "off", "auto"))
    }

    @Test
    fun `the environment beats the dsl`() {
        assertEquals(TestImpactMode.OFF, ModeResolution.resolve(null, "off", "full"))
    }

    @Test
    fun `the dsl is used when nothing else is set`() {
        assertEquals(TestImpactMode.FULL, ModeResolution.resolve(null, null, "full"))
    }

    @Test
    fun `values are trimmed and case-insensitive`() {
        assertEquals(TestImpactMode.OFF, ModeResolution.resolve("  OFF\t", null, null))
        assertEquals(TestImpactMode.AUTO, ModeResolution.resolve(null, "Auto", null))
    }

    @Test
    fun `an invalid gradle property names the property and the allowed values`() {
        val message = assertFailsWith<IllegalArgumentException> { ModeResolution.resolve("bogus", null, null) }.message
        assertTrue(message!!.contains("Gradle property 'testImpact'"), message)
        assertTrue(message.contains("'bogus'"), message)
        assertTrue(message.contains("auto, off, full"), message)
    }

    @Test
    fun `an invalid environment value names the variable`() {
        val message = assertFailsWith<IllegalArgumentException> { ModeResolution.resolve(null, "x", null) }.message
        assertTrue(message!!.contains("TEST_IMPACT"), message)
    }

    @Test
    fun `an invalid dsl value names the property`() {
        val message = assertFailsWith<IllegalArgumentException> { ModeResolution.resolve(null, null, "yes") }.message
        assertTrue(message!!.contains("testImpact.mode"), message)
    }

    @Test
    fun `only the winning source is checked`() {
        assertEquals(TestImpactMode.OFF, ModeResolution.resolve("off", "bogus", "bogus"))
    }

    @Test
    fun `a hostile value is shortened and stripped of control characters`() {
        val message = assertFailsWith<IllegalArgumentException> {
            ModeResolution.resolve("a\nb" + "x".repeat(200), null, null)
        }.message
        assertTrue(message!!.length < 200, message)
        assertTrue('\n' !in message, message)
    }
}
