package gg.aquatic.testimpact.escalation.internal

import gg.aquatic.testimpact.escalation.EscalationService
import gg.aquatic.testimpact.escalation.state.RedState
import gg.aquatic.testimpact.escalation.state.RedStateFile
import gg.aquatic.testimpact.escalation.state.RunPhase
import gg.aquatic.testimpact.escalation.state.RunResult
import org.gradle.api.tasks.testing.TestDescriptor
import org.gradle.api.tasks.testing.TestResult
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RedListenerTest {
    @TempDir
    lateinit var temp: Path

    private val warnings = mutableListOf<String>()

    private fun descriptor(className: String?, parent: TestDescriptor?): TestDescriptor =
        Proxy.newProxyInstance(javaClass.classLoader, arrayOf(TestDescriptor::class.java)) { _, method, _ ->
            when (method.name) {
                "getClassName" -> className
                "getParent" -> parent
                "getName", "getDisplayName" -> className ?: "root"
                "isComposite" -> false
                "toString" -> "descriptor"
                else -> null
            }
        } as TestDescriptor

    private fun result(type: TestResult.ResultType): TestResult =
        Proxy.newProxyInstance(javaClass.classLoader, arrayOf(TestResult::class.java)) { _, method, _ ->
            when (method.name) {
                "getResultType" -> type
                "getStartTime", "getEndTime", "getTestCount", "getSuccessfulTestCount", "getFailedTestCount", "getSkippedTestCount" -> 0L
                else -> null
            }
        } as TestResult

    private val root = descriptor(null, null)
    private val failed = result(TestResult.ResultType.FAILURE)
    private val passed = result(TestResult.ResultType.SUCCESS)

    private fun service(): EscalationService {
        val project = ProjectBuilder.builder().build()
        return project.gradle.sharedServices
            .registerIfAbsent(EscalationServiceName.value, EscalationService::class.java) {}.get()
    }

    private fun listener(
        phase: RunPhase,
        service: EscalationService? = null,
        file: Path = temp.resolve("t.state"),
    ) = RedListener(file, { phase }, ":test", service) { warnings.add(it) }

    private fun stateOf(file: Path = temp.resolve("t.state")): RedState = RedStateFile.read(file)

    @Test
    fun `a failed test records its outer class`() {
        val listener = listener(RunPhase.FULL)
        listener.beforeSuite(root)
        val suite = descriptor("app.CalcTest", root)
        listener.afterTest(descriptor("app.CalcTest", suite), failed)
        listener.afterTest(descriptor("app.OtherTest", suite), passed)
        listener.afterSuite(root, failed)
        assertEquals(RedState(true, setOf("app.CalcTest"), false, RunPhase.FULL, RunResult.RED), stateOf())
    }

    @Test
    fun `a nested class counts for its outer class`() {
        val listener = listener(RunPhase.PARTIAL)
        listener.beforeSuite(root)
        listener.afterTest(descriptor("app.Outer\$Inner", descriptor("app.Outer\$Inner", root)), failed)
        listener.afterSuite(root, failed)
        assertEquals(setOf("app.Outer"), stateOf().red)
    }

    @Test
    fun `a class whose set-up failed is red`() {
        val listener = listener(RunPhase.FULL)
        listener.beforeSuite(root)
        listener.afterSuite(descriptor("app.SetupTest", root), failed)
        listener.afterSuite(root, failed)
        val state = stateOf()
        assertEquals(setOf("app.SetupTest"), state.red)
        assertFalse(state.unknownFailure)
    }

    @Test
    fun `a failure without a class is unknown`() {
        val listener = listener(RunPhase.FULL)
        listener.beforeSuite(root)
        listener.afterSuite(root, failed)
        val state = stateOf()
        assertTrue(state.unknownFailure)
        assertTrue(state.fullRunRequired)
        assertTrue(state.red.isEmpty())
    }

    @Test
    fun `a test without a class name is unknown`() {
        val listener = listener(RunPhase.FULL)
        listener.beforeSuite(root)
        listener.afterTest(descriptor(null, root), failed)
        listener.afterSuite(root, failed)
        assertTrue(stateOf().unknownFailure)
    }

    @Test
    fun `the start writes running and keeps what was owed`() {
        val file = temp.resolve("t.state")
        Files.writeString(file, RedStateFile.render(RedState(true, setOf("a.A"), false, RunPhase.FULL, RunResult.RED)))
        listener(RunPhase.QUICK).beforeSuite(root)
        assertEquals(RedState(true, setOf("a.A"), false, RunPhase.QUICK, RunResult.RUNNING), stateOf())
    }

    @Test
    fun `a green full run clears the memory`() {
        val file = temp.resolve("t.state")
        Files.writeString(file, RedStateFile.render(RedState(true, setOf("a.A"), false, RunPhase.QUICK, RunResult.GREEN)))
        val listener = listener(RunPhase.FULL)
        listener.beforeSuite(root)
        listener.afterSuite(root, passed)
        assertTrue(stateOf().isVerdict())
    }

    @Test
    fun `a corrupt file warns once and requires a full run`() {
        Files.writeString(temp.resolve("t.state"), "garbage")
        val listener = listener(RunPhase.QUICK)
        listener.beforeSuite(root)
        listener.afterSuite(root, passed)
        assertEquals(1, warnings.size)
        val state = stateOf()
        assertTrue(state.fullRunRequired)
        assertTrue(state.unknownFailure)
        assertEquals(RunPhase.QUICK, state.lastPhase)
    }

    @Test
    fun `the end writes once`() {
        val file = temp.resolve("t.state")
        val listener = listener(RunPhase.FULL)
        listener.beforeSuite(root)
        listener.afterTest(descriptor("app.A", root), failed)
        listener.afterSuite(root, failed)
        val first = Files.readString(file)
        listener.afterTest(descriptor("app.B", root), failed)
        listener.afterSuite(root, failed)
        assertEquals(first, Files.readString(file))
    }

    @Test
    fun `a quick outcome is reported to the service`() {
        val service = service()
        val listener = listener(RunPhase.QUICK, service)
        listener.beforeSuite(root)
        listener.afterTest(descriptor("app.A", root), failed)
        assertNull(service.quickOutcome(":test"))
        listener.afterSuite(root, failed)
        val outcome = assertNotNull(service.quickOutcome(":test"))
        assertEquals(setOf("app.A"), outcome.failures)
        assertTrue(outcome.red)
    }

    @Test
    fun `other phases do not report to the service`() {
        val service = service()
        val listener = listener(RunPhase.FULL, service)
        listener.beforeSuite(root)
        listener.afterSuite(root, passed)
        assertNull(service.quickOutcome(":test"))
    }

    @Test
    fun `a green quick run reports an empty outcome`() {
        val service = service()
        val listener = listener(RunPhase.QUICK, service)
        listener.beforeSuite(root)
        listener.afterSuite(root, passed)
        assertFalse(assertNotNull(service.quickOutcome(":test")).red)
    }

    @Test
    fun `parallel failures are all recorded and written once`() {
        val service = service()
        val listener = listener(RunPhase.QUICK, service)
        listener.beforeSuite(root)
        val threads = 8
        val perThread = 1_000
        val pool = Executors.newFixedThreadPool(threads)
        val start = CountDownLatch(1)
        try {
            val jobs = (0 until threads).map { t ->
                pool.submit {
                    start.await()
                    for (i in 0 until perThread) listener.afterTest(descriptor("app.T${t}_$i", root), failed)
                }
            }
            start.countDown()
            jobs.forEach { it.get(30, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }
        listener.afterSuite(root, failed)
        assertEquals(threads * perThread, assertNotNull(service.quickOutcome(":test")).failures.size)
        assertEquals(RunResult.RED, stateOf().lastResult)
    }
}
