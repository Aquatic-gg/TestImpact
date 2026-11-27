package gg.aquatic.testimpact.seed

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SeedRulesTest {

    private fun rejected(dynamic: List<String> = emptyList(), always: List<String> = emptyList()): String =
        assertFailsWith<IllegalArgumentException> { SeedRules.of(dynamic, always) }.message.orEmpty()

    @Test
    fun `none has no packages and runs nothing`() {
        assertEquals(emptyList(), SeedRules.NONE.dynamicPackages)
        assertEquals(emptyList(), SeedRules.NONE.alwaysRun(listOf("a.B")))
        assertFalse(SeedRules.NONE.marksDynamic("a.b"))
    }

    @Test
    fun `valid entries are kept sorted and distinct`() {
        val rules = SeedRules.of(listOf("b.c", "a", "b.c", "\$x._y"), listOf("*Check"))
        assertEquals(listOf("\$x._y", "a", "b.c"), rules.dynamicPackages)
    }

    @Test
    fun `a package marks itself and everything below it`() {
        val rules = SeedRules.of(listOf("a.b"), emptyList())
        assertTrue(rules.marksDynamic("a.b"))
        assertTrue(rules.marksDynamic("a.b.c.d"))
        assertFalse(rules.marksDynamic("a"))
        assertFalse(rules.marksDynamic("a.bc"))
        assertFalse(rules.marksDynamic(""))
    }

    @Test
    fun `bad packages are named with the property and each entry`() {
        val message = rejected(dynamic = listOf("a..b", "1abc", "ok.pkg", "a b", "", "x.", "a-b"))
        assertTrue("dynamicPackages" in message)
        for (entry in listOf("'a..b'", "'1abc'", "'a b'", "'x.'", "'a-b'")) assertTrue(entry in message, message)
        assertTrue("blank" in message)
        assertFalse("ok.pkg" in message)
    }

    @Test
    fun `bad patterns are named with the property and each entry`() {
        val message = rejected(always = listOf("a/b", "Good*", "x(y)", "  "))
        assertTrue("alwaysRun" in message)
        assertTrue("'a/b'" in message)
        assertTrue("'x(y)'" in message)
        assertFalse("Good*" in message)
    }

    @Test
    fun `both properties are reported in one failure`() {
        val message = rejected(dynamic = listOf("a..b"), always = listOf("a/b"))
        assertTrue("dynamicPackages" in message && "alwaysRun" in message)
    }

    @Test
    fun `entries over 512 characters and over 1000 entries are rejected`() {
        assertTrue("longer than 512" in rejected(always = listOf("a".repeat(513))))
        SeedRules.of(emptyList(), listOf("a".repeat(512)))
        val many = (1..1001).map { "p$it" }
        assertTrue("1001 entries" in rejected(dynamic = many))
        SeedRules.of((1..1000).map { "p$it" }, emptyList())
    }

    @Test
    fun `a long bad entry is shortened and control characters are not echoed`() {
        val message = rejected(always = listOf("a/" + "b".repeat(300), "x\ny/"))
        assertTrue(message.length < 400, message)
        assertFalse('\n' in message)
    }

    @Test
    fun `alwaysRun is sorted distinct and deterministic`() {
        val rules = SeedRules.of(emptyList(), listOf("*Check", "p.Special"))
        val tests = listOf("q.BCheck", "p.Special", "p.ACheck", "p.Other", "q.BCheck")
        val expected = listOf("p.ACheck", "p.Special", "q.BCheck")
        assertEquals(expected, rules.alwaysRun(tests))
        assertEquals(expected, rules.alwaysRun(tests.reversed()))
    }
}
