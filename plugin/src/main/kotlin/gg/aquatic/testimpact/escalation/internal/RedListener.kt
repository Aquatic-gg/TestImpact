package gg.aquatic.testimpact.escalation.internal

import gg.aquatic.testimpact.escalation.EscalationService
import gg.aquatic.testimpact.escalation.state.RedState
import gg.aquatic.testimpact.escalation.state.RedStateFile
import gg.aquatic.testimpact.escalation.state.RunPhase
import gg.aquatic.testimpact.gradle.internal.AtomicFile
import org.gradle.api.tasks.testing.TestDescriptor
import org.gradle.api.tasks.testing.TestListener
import org.gradle.api.tasks.testing.TestResult
import java.io.IOException
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Records the failed classes of one Test task run into its state file. The state is written when the run starts
 * (`last-result=running`, so a killed build leaves a trace) and once when it ends. A failure is recorded under its outer
 * class, since the selection names outer classes and the pattern `X$*` reruns the nested ones; a class whose set-up
 * failed counts as red too; a failure no class can be named for marks the state unknown.
 *
 * Callbacks may arrive from several threads: the work per event is a set insert, and the file is touched only at the
 * start and the end of the run.
 *
 * @param phase decides what the run covers; asked once, when the run starts.
 * @param testPath the Test task the outcome belongs to, also for a quick run.
 * @param service receives the outcome of a [RunPhase.QUICK] run.
 */
internal class RedListener(
    private val stateFile: Path,
    private val phase: () -> RunPhase,
    private val testPath: String,
    private val service: EscalationService?,
    private val warn: (String) -> Unit,
) : TestListener {
    private val failures: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val unknownTest = AtomicBoolean(false)
    private val written = AtomicBoolean(false)
    private val started = AtomicReference<Start?>(null)

    private class Start(val phase: RunPhase, val before: RedState)

    override fun beforeSuite(suite: TestDescriptor) {
        if (suite.parent != null) return
        val runPhase = phase()
        val before = load()
        started.set(Start(runPhase, before))
        write(before.started(runPhase))
    }

    override fun afterSuite(suite: TestDescriptor, result: TestResult) {
        if (result.resultType != TestResult.ResultType.FAILURE) {
            if (suite.parent == null) finish()
            return
        }
        if (suite.parent == null) {
            if (failures.isEmpty()) unknownTest.set(true)
            finish()
        } else {
            suite.className?.let { failures.add(outerClass(it)) }
        }
    }

    override fun beforeTest(testDescriptor: TestDescriptor) = Unit

    override fun afterTest(testDescriptor: TestDescriptor, result: TestResult) {
        if (result.resultType != TestResult.ResultType.FAILURE) return
        val name = testDescriptor.className
        if (name == null) unknownTest.set(true) else failures.add(outerClass(name))
    }

    private fun finish() {
        if (!written.compareAndSet(false, true)) return
        val start = started.get() ?: return
        val (recorded, leftOut) = RedStateFile.fit(failures)
        val unknown = unknownTest.get() || leftOut
        try {
            write(start.before.finished(start.phase, recorded, unknown))
        } finally {
            if (start.phase == RunPhase.QUICK) service?.quickFinished(testPath, recorded, unknown)
        }
    }

    private fun load(): RedState {
        val state = RedStateFile.read(stateFile)
        if (state.isCorrupt()) warn("TestImpact $testPath: the red-run memory could not be read, a full run is required")
        return state
    }

    private fun write(state: RedState) {
        try {
            AtomicFile.write(stateFile, RedStateFile.render(state))
        } catch (e: IOException) {
            warn("TestImpact $testPath: the red-run memory could not be written (${e.message})")
        }
    }

    private fun outerClass(name: String): String {
        val dollar = name.indexOf('$')
        return if (dollar > 0) name.substring(0, dollar) else name
    }
}
