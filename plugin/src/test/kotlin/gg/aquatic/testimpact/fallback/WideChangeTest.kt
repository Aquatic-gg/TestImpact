package gg.aquatic.testimpact.fallback

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WideChangeTest {

    @Test
    fun `a share above the percent is wide and the share at it is not`() {
        assertFalse(WideChange.exceeds(selected = 7, candidates = 12, percent = 60, minClasses = 12))
        assertTrue(WideChange.exceeds(selected = 8, candidates = 12, percent = 60, minClasses = 12))
    }

    @Test
    fun `fewer candidates than the minimum are never wide`() {
        assertFalse(WideChange.exceeds(selected = 11, candidates = 11, percent = 1, minClasses = 12))
    }

    @Test
    fun `percent 100 is never wide`() {
        assertFalse(WideChange.exceeds(selected = 50, candidates = 50, percent = 100, minClasses = 0))
    }

    @Test
    fun `no candidates are not wide even with a zero minimum`() {
        assertFalse(WideChange.exceeds(selected = 0, candidates = 0, percent = 0, minClasses = 0))
    }

    @Test
    fun `a million candidates do not overflow`() {
        assertTrue(WideChange.exceeds(selected = 20_000_000, candidates = 1_000_000, percent = 1, minClasses = 1))
        assertFalse(WideChange.exceeds(selected = 10_000, candidates = 1_000_000, percent = 1, minClasses = 1))
    }
}
