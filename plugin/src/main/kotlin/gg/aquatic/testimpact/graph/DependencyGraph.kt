package gg.aquatic.testimpact.graph

/**
 * Immutable class dependency graph over [ClassFacts]. Explicit edges (supertype, reference, inline) come from the
 * facts; [EdgeKind.CONSTANT] and [EdgeKind.TYPEALIAS] edges are not stored but derived from definer lists at query
 * time, so the graph stays linear in size. Every query returns lists sorted by the other class name, with one edge
 * per pair (the lowest [EdgeKind] wins) and never a self edge. Names that were not scanned are valid arguments.
 */
internal class DependencyGraph private constructor(private val byName: Map<String, ClassFacts>) {
    /** Scanned class names, sorted. */
    val classes: List<String> = byName.keys.sorted()

    private val owners: Map<String, String> = computeOwners()
    private val owned: Map<String, List<String>> = classes.groupBy { owners.getValue(it) }
    private val sources: Map<String, String> = buildMap {
        for (name in classes) sourceKey(byName.getValue(name))?.let { put(name, it) }
    }
    private val inPackage: Map<String, List<String>> = classes.groupBy { packageOf(it) }
    private val inSource: Map<String, List<String>> = classes.filter { it in sources }.groupBy { sources.getValue(it) }
    private val explicitDependents: Map<String, List<Edge>> = buildExplicitDependents()
    private val constants = definers { it.constantScope }
    private val aliases = definers { it.typeAliasScope }

    fun facts(name: String): ClassFacts? = byName[name]

    /** What [name] depends on: its own references plus every constant and typealias definer that may reach it. */
    fun dependencies(name: String): List<Edge> {
        val facts = byName[name] ?: return emptyList()
        val found = HashMap<String, EdgeKind>()
        fun put(to: String, kind: EdgeKind) {
            if (to != name) found.merge(to, kind) { a, b -> if (a <= b) a else b }
        }
        facts.supertypes.forEach { put(it, EdgeKind.SUPERTYPE) }
        facts.references.forEach { put(it, EdgeKind.REFERENCE) }
        facts.inlineSources.forEach { put(it, EdgeKind.INLINE) }
        constants.coverers(name).forEach { put(it, EdgeKind.CONSTANT) }
        if (facts.isKotlin) aliases.coverers(name).forEach { put(it, EdgeKind.TYPEALIAS) }
        return found.map { Edge(name, it.key, it.value) }.sortedWith(compareBy({ it.to }, { it.kind }))
    }

    /** What depends on [name]: the classes mentioning it plus, for a definer, every class inside its scope. */
    fun dependents(name: String): List<Edge> {
        val found = HashMap<String, EdgeKind>()
        fun put(from: String, kind: EdgeKind) {
            if (from != name) found.merge(from, kind) { a, b -> if (a <= b) a else b }
        }
        explicitDependents[name]?.forEach { put(it.from, it.kind) }
        val facts = byName[name]
        if (facts != null) {
            facts.constantScope?.let { scope -> usersOf(name, scope, kotlinOnly = false).forEach { put(it, EdgeKind.CONSTANT) } }
            facts.typeAliasScope?.let { scope -> usersOf(name, scope, kotlinOnly = true).forEach { put(it, EdgeKind.TYPEALIAS) } }
        }
        return found.map { Edge(it.key, name, it.value) }.sortedWith(compareBy({ it.from }, { it.kind }))
    }

    /**
     * The top-level class [name] is declared in, found by following [ClassFacts.outer]; [name] itself for a
     * top-level class or an unscanned one. An outer chain that loops resolves to the smallest name of the loop.
     */
    fun owner(name: String): String = owners[name] ?: name

    /** Scanned classes whose [owner] is [owner], sorted. */
    fun ownedBy(owner: String): List<String> = owned[owner].orEmpty()

    /**
     * Package path plus source file name (`p/Foo.kt`), shared by every class generated from that file; null when
     * [name] was not scanned or has no SourceFile attribute.
     */
    fun sourceOf(name: String): String? = sources[name]

    private fun computeOwners(): Map<String, String> {
        val resolved = HashMap<String, String>()
        for (start in classes) {
            if (start in resolved) continue
            val path = ArrayList<String>()
            val owner = followOuterChain(start, path, resolved)
            for (member in path) resolved[member] = owner
        }
        return resolved
    }

    /** Walks the outer chain from [start], appending every visited class to [path]; stops at a known owner, a top-level class or a loop. */
    private fun followOuterChain(start: String, path: MutableList<String>, resolved: Map<String, String>): String {
        val index = HashMap<String, Int>()
        var current = start
        while (true) {
            resolved[current]?.let { return it }
            val seen = index[current]
            if (seen != null) return path.subList(seen, path.size).min()
            index[current] = path.size
            path.add(current)
            current = byName[current]?.outer ?: return current
        }
    }

    private fun buildExplicitDependents(): Map<String, List<Edge>> {
        val result = HashMap<String, MutableList<Edge>>()
        for (name in classes) {
            val facts = byName.getValue(name)
            fun add(to: String, kind: EdgeKind) {
                if (to != name) result.getOrPut(to) { ArrayList() }.add(Edge(name, to, kind))
            }
            facts.supertypes.forEach { add(it, EdgeKind.SUPERTYPE) }
            facts.references.forEach { add(it, EdgeKind.REFERENCE) }
            facts.inlineSources.forEach { add(it, EdgeKind.INLINE) }
        }
        return result
    }

    private fun usersOf(definer: String, scope: ReachScope, kotlinOnly: Boolean): List<String> {
        val candidates = when (scope) {
            ReachScope.ALL -> classes
            ReachScope.PACKAGE -> inPackage[packageOf(definer)].orEmpty()
            ReachScope.FILE -> {
                val sameSource = sources[definer]?.let { inSource[it] }.orEmpty()
                sameSource + ownedBy(owner(definer))
            }
        }
        return candidates.filter { it != definer && (!kotlinOnly || byName[it]?.isKotlin == true) }
    }

    private fun definers(scopeOf: (ClassFacts) -> ReachScope?): Definers {
        val everywhere = ArrayList<String>()
        val byPackage = HashMap<String, MutableList<String>>()
        val bySource = HashMap<String, MutableList<String>>()
        val byOwner = HashMap<String, MutableList<String>>()
        for (name in classes) {
            when (scopeOf(byName.getValue(name)) ?: continue) {
                ReachScope.ALL -> everywhere.add(name)
                ReachScope.PACKAGE -> byPackage.getOrPut(packageOf(name)) { ArrayList() }.add(name)
                ReachScope.FILE -> {
                    sources[name]?.let { bySource.getOrPut(it) { ArrayList() }.add(name) }
                    byOwner.getOrPut(owners.getValue(name)) { ArrayList() }.add(name)
                }
            }
        }
        return Definers(everywhere, byPackage, bySource, byOwner)
    }

    private inner class Definers(
        val everywhere: List<String>,
        val byPackage: Map<String, List<String>>,
        val bySource: Map<String, List<String>>,
        val byOwner: Map<String, List<String>>,
    ) {
        fun coverers(user: String): Set<String> {
            val result = HashSet<String>(everywhere)
            byPackage[packageOf(user)]?.let { result.addAll(it) }
            sources[user]?.let { key -> bySource[key]?.let { result.addAll(it) } }
            byOwner[owners.getValue(user)]?.let { result.addAll(it) }
            result.remove(user)
            return result
        }
    }

    companion object {
        /**
         * Builds the graph. Facts sharing a name (the same class on several roots) are merged into the union of
         * their sets with the widest scopes, so the result does not depend on the order of [facts].
         */
        fun of(facts: Collection<ClassFacts>): DependencyGraph =
            DependencyGraph(facts.groupBy { it.name }.mapValues { merge(it.value) })

        private fun packageOf(name: String): String = name.substringBeforeLast('.', "")

        private fun sourceKey(facts: ClassFacts): String? {
            val file = facts.sourceFile ?: return null
            val pkg = packageOf(facts.name)
            return if (pkg.isEmpty()) file else pkg.replace('.', '/') + "/" + file
        }

        private fun merge(group: List<ClassFacts>): ClassFacts {
            if (group.size == 1) return group[0]
            return ClassFacts(
                name = group[0].name,
                access = group.fold(0) { acc, f -> acc or f.access },
                sourceFile = group.mapNotNull { it.sourceFile }.minOrNull(),
                supertypes = group.flatMap { it.supertypes }.distinct().sorted(),
                outer = group.mapNotNull { it.outer }.minOrNull(),
                references = group.flatMap { it.references }.distinct().sorted(),
                inlineSources = group.flatMap { it.inlineSources }.distinct().sorted(),
                constantScope = group.mapNotNull { it.constantScope }.maxOrNull(),
                constantFields = group.flatMap { it.constantFields }.distinct()
                    .sortedWith(compareBy({ it.name }, { it.descriptor }, { it.value }, { it.scope })),
                isKotlin = group.any { it.isKotlin },
                typeAliasScope = group.mapNotNull { it.typeAliasScope }.maxOrNull(),
            )
        }
    }
}
