package gg.aquatic.testimpact.select

import gg.aquatic.testimpact.graph.ClassGraphResult
import gg.aquatic.testimpact.graph.ClassGraphScanner
import gg.aquatic.testimpact.graph.ClassProblem
import gg.aquatic.testimpact.graph.DependencyGraph
import gg.aquatic.testimpact.select.gradle.TestTaskCapture
import gg.aquatic.testimpact.select.internal.EngineCatalog
import gg.aquatic.testimpact.select.internal.ReachResult
import gg.aquatic.testimpact.select.internal.ReverseReach
import gg.aquatic.testimpact.select.internal.TestCandidates
import java.nio.file.Path

/**
 * Chooses the test classes of one test task that a set of changes can affect. Pure and stateless: it reads class files
 * through [scan] and builds immutable graphs per call, so it holds no shared state and needs no dispatcher.
 *
 * Anything that cannot be decided soundly (unmapped change, unsupported framework or engine, unreadable classes, any
 * exception) is [Selection.Unknown], which the caller treats as "run everything". Errors are not caught.
 */
internal object TestSelector {
    private const val MAX_PROBLEMS = 20

    /**
     * @param input classpath roots, framework and engines of the task, already resolved.
     * @param seeds the changed classes; forced seeds make the result unknown without any file read.
     * @param scan reads the class files of a list of roots into a graph.
     */
    fun select(
        input: TestTaskInput,
        seeds: SeedSet,
        scan: (List<Path>) -> ClassGraphResult = { ClassGraphScanner.scan(it) },
    ): Selection {
        seeds.forced?.let { return unknown(input, it.reason, it.detail) }
        unsupported(input)?.let { return it }
        return try {
            analyse(input, seeds, scan)
        } catch (e: Exception) {
            unknown(input, SelectionUnknownReason.ANALYSIS_ERROR, "${e.javaClass.name}: ${e.message}")
        }
    }

    /**
     * Selects for a captured task. Reading the classpath happens inside the same guard as the analysis, so a service
     * file or archive that cannot be read becomes [SelectionUnknownReason.ANALYSIS_ERROR] instead of failing the build.
     */
    fun select(
        capture: TestTaskCapture,
        seeds: SeedSet,
        scan: (List<Path>) -> ClassGraphResult = { ClassGraphScanner.scan(it) },
    ): Selection {
        seeds.forced?.let { return Selection.Unknown(capture.taskPath, it.reason, it.detail) }
        val input = try {
            capture.resolve()
        } catch (e: Exception) {
            return Selection.Unknown(
                capture.taskPath,
                SelectionUnknownReason.ANALYSIS_ERROR,
                "${e.javaClass.name}: ${e.message}",
            )
        }
        return select(input, seeds, scan)
    }

    private fun unsupported(input: TestTaskInput): Selection.Unknown? = when (input.framework) {
        TestFrameworkKind.TESTNG, TestFrameworkKind.OTHER ->
            unknown(input, SelectionUnknownReason.UNSUPPORTED_FRAMEWORK, input.framework.name)

        TestFrameworkKind.JUNIT4 -> null
        TestFrameworkKind.JUNIT_PLATFORM -> {
            val unknownEngines = input.engines.filterNot(EngineCatalog::isSupportedEngine)
            when {
                input.engines.isEmpty() ->
                    unknown(input, SelectionUnknownReason.UNKNOWN_ENGINE, "no JUnit Platform engine on the test runtime classpath")

                unknownEngines.isNotEmpty() ->
                    unknown(input, SelectionUnknownReason.UNKNOWN_ENGINE, unknownEngines.sorted().joinToString(", "))

                else -> null
            }
        }
    }

    private fun analyse(input: TestTaskInput, seeds: SeedSet, scan: (List<Path>) -> ClassGraphResult): Selection {
        val tests = scan(input.testRoots)
        val others = scan(input.otherRoots)
        val problems = listOf(tests, others).flatMap { (it as? ClassGraphResult.Unknown)?.problems.orEmpty() }
        if (problems.isNotEmpty()) {
            return unknown(input, SelectionUnknownReason.GRAPH_UNKNOWN, describe(problems))
        }
        val testGraph = (tests as ClassGraphResult.Known).graph
        val otherGraph = (others as ClassGraphResult.Known).graph
        val graph = DependencyGraph.of(
            testGraph.classes.mapNotNull(testGraph::facts) + otherGraph.classes.mapNotNull(otherGraph::facts),
        )

        val candidates = TestCandidates.find(graph, testGraph.classes)
        val reach = ReverseReach.reach(graph, seeds.seeds, candidates.names.toSet())
        val global = if (reach.selected.size < candidates.names.size) reachedGlobal(input, graph, reach) else null

        val selected = candidates.names.mapNotNull { name ->
            val own = reach.pathTo(name)
            when {
                own != null -> SelectedTest(name, own, SelectReason.REACHED, candidates.isRecognised(name))
                global != null -> SelectedTest(name, global, SelectReason.GLOBAL, candidates.isRecognised(name))
                else -> null
            }
        }
        return Selection.Known(input.taskPath, selected, candidates.recognisedCount, candidates.names.size)
    }

    private fun reachedGlobal(input: TestTaskInput, graph: DependencyGraph, reach: ReachResult): ReachPath? {
        val kotest = graph.classes.filter { name ->
            val facts = graph.facts(name) ?: return@filter false
            facts.supertypes.any(EngineCatalog::isKotestGlobalSupertype) ||
                facts.references.any(EngineCatalog::isKotestGlobalReference)
        }
        for (name in (input.globalClasses + kotest).sorted()) {
            for (member in listOf(name) + graph.ownedBy(name)) reach.pathToClass(member)?.let { return it }
        }
        return null
    }

    private fun describe(problems: List<ClassProblem>): String {
        val shown = problems.take(MAX_PROBLEMS).joinToString("; ") { "${it.location}: ${it.message}" }
        val more = problems.size - MAX_PROBLEMS
        return if (more > 0) "$shown; and $more more" else shown
    }

    private fun unknown(input: TestTaskInput, reason: SelectionUnknownReason, detail: String) =
        Selection.Unknown(input.taskPath, reason, detail)
}
