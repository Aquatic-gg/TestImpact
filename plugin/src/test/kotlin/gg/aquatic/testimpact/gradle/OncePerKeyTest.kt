package gg.aquatic.testimpact.gradle

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class OncePerKeyTest {
    @Test
    fun `concurrent callers compute once`() {
        val calls = AtomicInteger()
        val release = CountDownLatch(1)
        val memo = OncePerKey<String, Any> {
            calls.incrementAndGet()
            release.await(10, TimeUnit.SECONDS)
            Any()
        }
        val threads = 8
        val pool = Executors.newFixedThreadPool(threads)
        try {
            val started = CountDownLatch(threads)
            val futures = (0 until threads).map {
                pool.submit<Any> {
                    started.countDown()
                    memo.get("k")
                }
            }
            started.await(10, TimeUnit.SECONDS)
            release.countDown()
            val values = futures.map { it.get(30, TimeUnit.SECONDS) }
            values.forEach { assertSame(values.first(), it) }
            assertEquals(1, calls.get())
            assertEquals(1, memo.size)
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `a failure is not kept and the next call retries`() {
        val calls = AtomicInteger()
        val memo = OncePerKey<String, String> {
            if (calls.incrementAndGet() == 1) throw IllegalStateException("first") else "ok"
        }
        assertFailsWith<IllegalStateException> { memo.get("k") }
        assertEquals(0, memo.size)
        assertEquals("ok", memo.get("k"))
        assertEquals("ok", memo.get("k"))
        assertEquals(2, calls.get())
    }

    @Test
    fun `keys are independent and clear forgets them`() {
        val calls = AtomicInteger()
        val memo = OncePerKey<List<String>, Int> { calls.incrementAndGet() }
        val first = memo.get(listOf("a", "b"))
        assertEquals(first, memo.get(listOf("a", "b")))
        assertEquals(first + 1, memo.get(listOf("b", "a")))
        assertEquals(2, memo.size)
        memo.clear()
        assertEquals(0, memo.size)
    }
}
