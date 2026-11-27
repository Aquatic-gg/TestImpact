package gg.aquatic.testimpact.select.internal

import gg.aquatic.testimpact.graph.DependencyGraph

/**
 * The classes of the test roots that can be selected, and which of them framework markers identify as tests.
 *
 * A candidate is a concrete top-level class: abstract classes, interfaces, annotations, enums, synthetic classes and
 * modules are left out because a test engine never runs them directly; their concrete subclasses are candidates and
 * are reached through the supertype edge. Nested, inner and anonymous classes fold into their top-level owner.
 */
internal class TestCandidates private constructor(
    /** Candidate class names, sorted. */
    val names: List<String>,
    private val recognised: Set<String>,
) {
    val recognisedCount: Int get() = recognised.size

    fun isRecognised(name: String): Boolean = name in recognised

    companion object {
        private const val ACC_INTERFACE = 0x0200
        private const val ACC_ABSTRACT = 0x0400
        private const val ACC_SYNTHETIC = 0x1000
        private const val ACC_ANNOTATION = 0x2000
        private const val ACC_ENUM = 0x4000
        private const val ACC_MODULE = 0x8000
        private const val EXCLUDED = ACC_INTERFACE or ACC_ABSTRACT or ACC_SYNTHETIC or ACC_ANNOTATION or ACC_ENUM or ACC_MODULE

        /**
         * @param testRootClasses every class name scanned from the test roots, nested classes included.
         */
        fun find(graph: DependencyGraph, testRootClasses: Collection<String>): TestCandidates {
            val names = testRootClasses.asSequence()
                .filter { graph.owner(it) == it }
                .filter { name -> graph.facts(name)?.let { it.access and EXCLUDED == 0 } == true }
                .distinct()
                .sorted()
                .toList()
            if (names.isEmpty()) return TestCandidates(names, emptySet())
            val recognisedClasses = recognisedClasses(graph)
            val recognised = names.filterTo(HashSet()) { name ->
                graph.ownedBy(name).any { it in recognisedClasses } || name in recognisedClasses
            }
            return TestCandidates(names, recognised)
        }

        /**
         * Every scanned class that is a test itself or inherits from one. Computed with worklists over reverse edges, so
         * hostile supertype or annotation cycles cannot recurse and every class is visited once.
         */
        private fun recognisedClasses(graph: DependencyGraph): Set<String> {
            val markedAnnotations = markedAnnotations(graph)
            val subtypes = HashMap<String, MutableList<String>>()
            val result = HashSet<String>()
            val queue = ArrayDeque<String>()
            for (name in graph.classes) {
                val facts = graph.facts(name) ?: continue
                for (supertype in facts.supertypes) subtypes.getOrPut(supertype) { ArrayList() }.add(name)
                val direct = facts.supertypes.any(FrameworkMarkers::isTestSupertype) ||
                    facts.references.any { FrameworkMarkers.isTestAnnotation(it) || it in markedAnnotations }
                if (direct && result.add(name)) queue.add(name)
            }
            while (queue.isNotEmpty()) {
                val current = queue.removeFirst()
                for (sub in subtypes[current].orEmpty()) if (result.add(sub)) queue.add(sub)
            }
            return result
        }

        /** Scanned annotation types that carry a test marker directly or through another such annotation. */
        private fun markedAnnotations(graph: DependencyGraph): Set<String> {
            val usedBy = HashMap<String, MutableList<String>>()
            val marked = HashSet<String>()
            val queue = ArrayDeque<String>()
            for (name in graph.classes) {
                val facts = graph.facts(name) ?: continue
                if (facts.access and ACC_ANNOTATION == 0) continue
                for (reference in facts.references) usedBy.getOrPut(reference) { ArrayList() }.add(name)
                if (facts.references.any(FrameworkMarkers::isTestAnnotation) && marked.add(name)) queue.add(name)
            }
            while (queue.isNotEmpty()) {
                val current = queue.removeFirst()
                for (user in usedBy[current].orEmpty()) if (marked.add(user)) queue.add(user)
            }
            return marked
        }
    }
}
