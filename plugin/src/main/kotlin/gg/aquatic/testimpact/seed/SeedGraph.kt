package gg.aquatic.testimpact.seed

import gg.aquatic.testimpact.seed.internal.PackageNames

/**
 * Immutable graph of the reverse edges bytecode references do not show: services, class and package names held in
 * string constants, descriptors, resource lookups and reflection. Nodes are binary class names and resource nodes
 * (see [resourceNode]). Fan-out over packages is resolved per query by walking the enclosing packages, so storage
 * stays linear. [dependents] keeps the contract of `DependencyGraph.dependents`: sorted by the dependent, one edge
 * per pair with the lowest [SeedKind], never a self edge, unscanned names are valid arguments.
 */
internal class SeedGraph private constructor(
    private val byName: Map<String, SeedFacts>,
    private val resources: List<ResourceFacts>,
    private val rules: SeedRules,
) {
    /** Scanned class names, sorted. */
    val classes: List<String> = byName.keys.sorted()

    private val dynamicNames: Set<String> = classes.filterTo(HashSet()) { dynamicByFacts(it) }
    private val holders: Map<String, List<String>> = buildHolders()
    private val tokenResources: Map<String, List<String>> = buildTokenResources()
    private val serviceFiles: Map<String, String> = buildServiceFiles()
    private val providedBy: Map<String, List<String>> = buildProvidedBy()
    private val dynamicByPackage: Map<String, List<String>> = classes.filter { it in dynamicNames }.groupBy { PackageNames.of(it) }
    private val loaders: List<String> = classes.filter { byName.getValue(it).loadsResources }
    private val stringLoaders: List<String> = loaders.filter { byName.getValue(it).buildsStrings }

    /** True when [name] was scanned and either calls reflection APIs or lies under a `dynamicPackages` entry. */
    fun isDynamic(name: String): Boolean = name in dynamicNames

    /** What depends on [node], a class name or a resource node. */
    fun dependents(node: String): List<SeedEdge> {
        val found = HashMap<String, SeedKind>()
        if (node.startsWith("/")) resourceDependents(node, found) else classDependents(node, found)
        found.remove(node)
        return found.map { SeedEdge(it.key, node, it.value) }.sortedWith(compareBy({ it.from }, { it.kind }))
    }

    private fun classDependents(name: String, found: MutableMap<String, SeedKind>) {
        val packages = PackageNames.withAncestors(PackageNames.of(name))
        providedBy[name]?.forEach { found.keep(it, SeedKind.SERVICE) }
        holders[name]?.forEach { found.keep(it, SeedKind.CLASS_NAME) }
        tokenResources[name]?.forEach { found.keep(it, SeedKind.DESCRIPTOR) }
        for (pkg in packages) {
            if ('.' in pkg) {
                holders[pkg]?.forEach { found.keep(it, SeedKind.PACKAGE_NAME) }
                tokenResources[pkg]?.forEach { found.keep(it, SeedKind.DESCRIPTOR) }
            }
            dynamicByPackage[pkg]?.forEach { found.keep(it, SeedKind.DYNAMIC) }
        }
    }

    private fun resourceDependents(node: String, found: MutableMap<String, SeedKind>) {
        val path = node.substring(1)
        serviceFiles[node]?.let { found.keep(it, SeedKind.SERVICE) }
        var named = false
        for (suffix in suffixes(path)) {
            holders[suffix]?.forEach {
                named = true
                found.keep(it, SeedKind.RESOURCE_NAME)
            }
        }
        (if (named) stringLoaders else loaders).forEach { found.keep(it, SeedKind.RESOURCE_LOADER) }
        val directory = path.substringBeforeLast('/', "").replace('/', '.')
        for (pkg in PackageNames.withAncestors(directory)) {
            dynamicByPackage[pkg]?.forEach { found.keep(it, SeedKind.DYNAMIC) }
        }
    }

    private fun MutableMap<String, SeedKind>.keep(from: String, kind: SeedKind) {
        merge(from, kind) { a, b -> if (a <= b) a else b }
    }

    private fun dynamicByFacts(name: String): Boolean =
        byName.getValue(name).dynamic || rules.marksDynamic(PackageNames.of(name))

    private fun buildHolders(): Map<String, List<String>> {
        val result = HashMap<String, MutableList<String>>()
        for (name in classes) {
            for (literal in byName.getValue(name).literals) {
                val list = result.getOrPut(normalise(literal)) { ArrayList() }
                if (list.lastOrNull() != name) list.add(name)
            }
        }
        return result
    }

    private fun buildTokenResources(): Map<String, List<String>> {
        val result = HashMap<String, MutableList<String>>()
        for (resource in resources) {
            if (!resource.descriptor) continue
            val node = resourceNode(resource.path)
            for (token in resource.tokens) result.getOrPut(token) { ArrayList() }.add(node)
        }
        return result
    }

    private fun buildServiceFiles(): Map<String, String> {
        val result = HashMap<String, String>()
        for (resource in resources) {
            val service = resource.path.removePrefix(SERVICES_DIRECTORY)
            if (service != resource.path && service.isNotEmpty() && '/' !in service) {
                result[resourceNode(resource.path)] = service
            }
        }
        return result
    }

    private fun buildProvidedBy(): Map<String, List<String>> {
        val result = HashMap<String, MutableList<String>>()
        for (name in classes) {
            for (provision in byName.getValue(name).provides) {
                for (provider in provision.providers) result.getOrPut(provider) { ArrayList() }.add(provision.service)
            }
        }
        return result
    }

    private fun suffixes(path: String): List<String> {
        val result = arrayListOf(path)
        var from = path.indexOf('/')
        while (from >= 0) {
            result.add(path.substring(from + 1))
            from = path.indexOf('/', from + 1)
        }
        return result
    }

    companion object {
        private const val SERVICES_DIRECTORY = "META-INF/services/"

        /**
         * Builds the graph. Facts sharing a name (the same class or resource on several roots) are merged into the
         * union of their facts, so the result does not depend on the order of the arguments.
         */
        fun of(classes: Collection<SeedFacts>, resources: Collection<ResourceFacts>, rules: SeedRules): SeedGraph =
            SeedGraph(
                classes.groupBy { it.name }.mapValues { mergeClass(it.value) },
                resources.groupBy { it.path }.map { mergeResource(it.value) }.sortedBy { it.path },
                rules,
            )

        fun of(inputs: SeedInputs, rules: SeedRules): SeedGraph = of(inputs.classes, inputs.resources, rules)

        /** The node of a resource: its path below the root with forward slashes, preceded by one slash. */
        fun resourceNode(resourcePath: String): String = "/" + resourcePath.replace('\\', '/').removePrefix("/")

        private fun normalise(literal: String): String = literal.removePrefix("/").removeSuffix(".")

        private fun mergeClass(group: List<SeedFacts>): SeedFacts {
            if (group.size == 1) return group[0]
            return SeedFacts(
                name = group[0].name,
                literals = group.flatMap { it.literals }.distinct().sorted(),
                dynamic = group.any { it.dynamic },
                loadsResources = group.any { it.loadsResources },
                buildsStrings = group.any { it.buildsStrings },
                provides = group.flatMap { it.provides }.groupBy { it.service }
                    .map { (service, parts) -> ServiceProvision(service, parts.flatMap { it.providers }.distinct().sorted()) }
                    .sortedBy { it.service },
            )
        }

        private fun mergeResource(group: List<ResourceFacts>): ResourceFacts {
            if (group.size == 1) return group[0]
            return ResourceFacts(
                path = group[0].path,
                tokens = group.flatMap { it.tokens }.distinct().sorted(),
                descriptor = group.any { it.descriptor },
            )
        }
    }
}
