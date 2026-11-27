package gg.aquatic.testimpact.gradle

import gg.aquatic.testimpact.gradle.internal.SelectionSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SelectionSettingsTest {

    private fun settings(
        baseRef: String? = null,
        percent: Int = 60,
        minClasses: Int = 12,
        packages: List<String> = emptyList(),
        alwaysRun: List<String> = emptyList(),
    ) = SelectionSettings.of(baseRef, percent, minClasses, packages, alwaysRun)

    private fun problem(block: () -> Unit): String =
        assertFailsWith<IllegalArgumentException>(block = block).message.orEmpty()

    @Test
    fun `defaults are accepted`() {
        val value = settings()

        assertNull(value.baseRef)
        assertEquals(60, value.wideChangePercent)
        assertEquals(12, value.wideChangeMinClasses)
    }

    @Test
    fun `a normal ref is accepted and an empty one means absent`() {
        assertEquals("origin/main", settings(baseRef = "origin/main").baseRef)
        assertNull(settings(baseRef = "").baseRef)
    }

    @Test
    fun `boundary values are accepted`() {
        settings(baseRef = "a".repeat(255), percent = 1, minClasses = 0)
        settings(percent = 100, minClasses = 1_000_000)
    }

    @Test
    fun `a ref starting with a dash is rejected`() {
        assertTrue(problem { settings(baseRef = "-x") }.contains("baseRef"))
    }

    @Test
    fun `a ref with whitespace or control characters is rejected`() {
        assertTrue(problem { settings(baseRef = "a b") }.contains("baseRef"))
        assertTrue(problem { settings(baseRef = "a\u0000b") }.contains("baseRef"))
        assertTrue(problem { settings(baseRef = "a\nb") }.contains("baseRef"))
    }

    @Test
    fun `a ref of 256 characters is rejected`() {
        assertTrue(problem { settings(baseRef = "a".repeat(256)) }.contains("256"))
    }

    @Test
    fun `a ref with dots at an edge is rejected`() {
        assertTrue(problem { settings(baseRef = "..main") }.contains("baseRef"))
        assertTrue(problem { settings(baseRef = "main..") }.contains("baseRef"))
    }

    @Test
    fun `percent outside 1 to 100 is rejected`() {
        assertTrue(problem { settings(percent = 0) }.contains("wideChangePercent"))
        assertTrue(problem { settings(percent = 101) }.contains("wideChangePercent"))
    }

    @Test
    fun `min classes outside its range is rejected`() {
        assertTrue(problem { settings(minClasses = -1) }.contains("wideChangeMinClasses"))
        assertTrue(problem { settings(minClasses = 1_000_001) }.contains("wideChangeMinClasses"))
    }

    @Test
    fun `seed rule errors are merged with the other problems in one message`() {
        val message = problem { settings(baseRef = "-x", percent = 0, packages = listOf("1bad"), alwaysRun = listOf("a b")) }

        assertTrue(message.contains("baseRef"), message)
        assertTrue(message.contains("wideChangePercent"), message)
        assertTrue(message.contains("dynamicPackages"), message)
        assertTrue(message.contains("alwaysRun"), message)
    }

    @Test
    fun `valid rules are kept`() {
        val value = settings(packages = listOf("com.example"), alwaysRun = listOf("*SmokeTest"))

        assertEquals(listOf("com.example"), value.rules.dynamicPackages)
        assertEquals(listOf("a.SmokeTest"), value.rules.alwaysRun(listOf("a.SmokeTest", "a.Other")))
    }
}
