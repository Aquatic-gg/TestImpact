package gg.aquatic.testimpact.seed.internal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TestPatternTest {

    private fun matches(pattern: String, name: String): Boolean = TestPattern(pattern).matches(name)

    @Test
    fun `a pattern without a dot matches the simple name only`() {
        assertTrue(matches("FooTest", "a.b.FooTest"))
        assertTrue(matches("FooTest", "FooTest"))
        assertFalse(matches("FooTest", "a.b.BarFooTest"))
        assertFalse(matches("b.FooTest", "a.b.Other"))
    }

    @Test
    fun `a pattern with a dot matches the whole name`() {
        assertTrue(matches("a.b.FooTest", "a.b.FooTest"))
        assertFalse(matches("b.FooTest", "a.b.FooTest"))
        assertTrue(matches("a.b.*", "a.b.c.FooTest"))
        assertTrue(matches("*.FooTest", "a.b.FooTest"))
    }

    @Test
    fun `a star spans dots and dollars`() {
        assertTrue(matches("*Check", "a.b.GreeterCheck"))
        assertTrue(matches("a.*Test", "a.b.c.FooTest"))
        assertTrue(matches("Outer*", "p.Outer\$Inner"))
        assertTrue(matches("*", "p.Anything"))
        assertTrue(matches("a.**.Z", "a.b.Z"))
    }

    @Test
    fun `a star can match nothing and the rest must still match`() {
        assertTrue(matches("Foo*Test", "FooTest"))
        assertFalse(matches("Foo*Test", "FooTes"))
        assertFalse(matches("*Test", "TestX"))
        assertTrue(matches("*a*b*c", "xxaxxbxxc"))
        assertFalse(matches("*a*b*c", "xxaxxcxxb"))
    }

    @Test
    fun `a long pathological pattern finishes fast`() {
        val pattern = "*a".repeat(250) + "b"
        val subject = "a".repeat(5000)
        val started = System.nanoTime()
        assertFalse(matches(pattern, subject))
        assertTrue(System.nanoTime() - started < 2_000_000_000L)
        assertEquals(true, matches("*a".repeat(250), subject))
    }
}
