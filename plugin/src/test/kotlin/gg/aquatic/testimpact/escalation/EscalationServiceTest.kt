package gg.aquatic.testimpact.escalation

import gg.aquatic.testimpact.escalation.internal.EscalationServiceName
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EscalationServiceTest {
    private fun service(): EscalationService =
        ProjectBuilder.builder().build().gradle.sharedServices
            .registerIfAbsent(EscalationServiceName.value, EscalationService::class.java) {}.get()

    @Test
    fun `an outcome is kept per test path`() {
        val service = service()
        service.quickFinished(":a:test", setOf("x.B", "x.A"), false)
        service.quickFinished(":b:test", emptySet(), true)
        assertEquals(listOf("x.A", "x.B"), service.quickFailures(":a:test").toList())
        assertTrue(assertNotNull(service.quickOutcome(":b:test")).red)
        assertTrue(service.quickFailures(":b:test").isEmpty())
        assertNull(service.quickOutcome(":c:test"))
        assertTrue(service.quickFailures(":c:test").isEmpty())
    }

    @Test
    fun `concurrent paths do not mix`() {
        val service = service()
        val pool = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        try {
            val jobs = (0 until 8).map { t ->
                pool.submit {
                    start.await()
                    for (i in 0 until 500) {
                        service.quickFinished(":p$t:test", setOf("c.C$t"), false)
                        assertEquals(setOf("c.C$t"), service.quickFailures(":p$t:test"))
                    }
                }
            }
            start.countDown()
            jobs.forEach { it.get(30, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `close clears the outcomes`() {
        val service = service()
        service.quickFinished(":test", setOf("x.A"), false)
        service.close()
        assertNull(service.quickOutcome(":test"))
    }

    @Test
    fun `the service name is stable within a class loader`() {
        assertEquals(EscalationServiceName.value, EscalationServiceName.value)
        assertTrue(EscalationServiceName.value.startsWith("testImpactEscalation-"))
        assertNotEquals(EscalationServiceName.value, "testImpactEscalation-")
    }
}
