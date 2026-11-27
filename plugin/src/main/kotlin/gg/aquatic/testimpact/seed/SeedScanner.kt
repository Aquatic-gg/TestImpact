package gg.aquatic.testimpact.seed

import gg.aquatic.testimpact.graph.ClassGraphScanner
import gg.aquatic.testimpact.graph.ClassProblem
import gg.aquatic.testimpact.graph.internal.classfile.MalformedClassException
import gg.aquatic.testimpact.seed.internal.ResourceTokens
import gg.aquatic.testimpact.seed.internal.SeedEntry
import gg.aquatic.testimpact.seed.internal.SeedFactsReader
import gg.aquatic.testimpact.seed.internal.SeedRoots
import java.io.IOException
import java.nio.file.Path

/**
 * Reads class roots and resource roots into [SeedInputs]. Inputs are untrusted: any class, archive, link or
 * descriptor that cannot be read becomes a [ClassProblem] and the result is [SeedResult.Unknown], never partial.
 * Class roots supply classes and the resources beside them; resource roots supply resources only. Only descriptors
 * are read, other resources contribute their path; a path found in several roots becomes one resource holding the
 * union of its tokens. The result does not depend on the order of the roots.
 */
internal object SeedScanner {
    const val DEFAULT_MAX_RESOURCE_BYTES: Int = 8 * 1024 * 1024

    fun scan(
        classRoots: List<Path>,
        resourceRoots: List<Path>,
        maxClassBytes: Int = ClassGraphScanner.DEFAULT_MAX_CLASS_BYTES,
        maxResourceBytes: Int = DEFAULT_MAX_RESOURCE_BYTES,
    ): SeedResult {
        val classes = ArrayList<SeedFacts>()
        val resources = ArrayList<ResourceFacts>()
        val problems = ArrayList<ClassProblem>()
        for (root in classRoots) {
            SeedRoots.walk(root, problems) { entry ->
                if (entry.isClass) readClass(entry, maxClassBytes, classes, problems)
                else readResource(entry, maxResourceBytes, resources, problems)
            }
        }
        for (root in resourceRoots) {
            SeedRoots.walk(root, problems) { entry ->
                if (!entry.isClass) readResource(entry, maxResourceBytes, resources, problems)
            }
        }
        if (problems.isNotEmpty()) {
            return SeedResult.Unknown(problems.sortedWith(compareBy({ it.location }, { it.message })))
        }
        return SeedResult.Known(SeedInputs(classes.sortedBy { it.name }, mergeByPath(resources)))
    }

    private fun mergeByPath(resources: List<ResourceFacts>): List<ResourceFacts> =
        resources.groupBy { it.path }.toSortedMap().map { (path, same) ->
            if (same.size == 1) same[0]
            else ResourceFacts(path, same.flatMap { it.tokens }.distinct().sorted(), same.any { it.descriptor })
        }

    private fun readClass(entry: SeedEntry, cap: Int, facts: MutableList<SeedFacts>, problems: MutableList<ClassProblem>) {
        try {
            val bytes = entry.open().use { it.readNBytes(cap + 1) }
            if (bytes.size > cap) {
                problems.add(ClassProblem(entry.location, "class file larger than $cap bytes"))
                return
            }
            facts.add(SeedFactsReader.read(bytes))
        } catch (e: MalformedClassException) {
            problems.add(ClassProblem(entry.location, "malformed class: ${describe(e)}"))
        } catch (e: IOException) {
            problems.add(ClassProblem(entry.location, "cannot read: ${describe(e)}"))
        } catch (e: RuntimeException) {
            problems.add(ClassProblem(entry.location, "unexpected failure: ${describe(e)}"))
        }
    }

    private fun readResource(entry: SeedEntry, cap: Int, resources: MutableList<ResourceFacts>, problems: MutableList<ClassProblem>) {
        if (!ResourceTokens.isDescriptor(entry.name)) {
            resources.add(ResourceFacts(entry.name, emptyList(), false))
            return
        }
        try {
            val bytes = entry.open().use { it.readNBytes(cap + 1) }
            if (bytes.size > cap) {
                problems.add(ClassProblem(entry.location, "descriptor larger than $cap bytes"))
                return
            }
            resources.add(ResourceFacts(entry.name, ResourceTokens.scan(bytes), true))
        } catch (e: IOException) {
            problems.add(ClassProblem(entry.location, "cannot read: ${describe(e)}"))
        } catch (e: RuntimeException) {
            problems.add(ClassProblem(entry.location, "unexpected failure: ${describe(e)}"))
        }
    }

    private fun describe(e: Exception): String = e.message ?: e.javaClass.simpleName
}
