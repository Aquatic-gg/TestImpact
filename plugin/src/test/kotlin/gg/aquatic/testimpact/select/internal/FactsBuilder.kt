package gg.aquatic.testimpact.select.internal

import gg.aquatic.testimpact.graph.ClassFacts
import gg.aquatic.testimpact.graph.DependencyGraph

/** Builds synthetic [ClassFacts] without compiling anything. */
internal object FactsBuilder {
    const val PUBLIC = 0x0001
    const val INTERFACE = 0x0200
    const val ABSTRACT = 0x0400
    const val ANNOTATION = 0x2000
    const val ENUM = 0x4000

    /** References always include the supertypes, like facts read from a class file. */
    fun facts(
        name: String,
        access: Int = PUBLIC,
        supertypes: List<String> = emptyList(),
        references: List<String> = emptyList(),
        outer: String? = null,
        sourceFile: String? = null,
    ): ClassFacts = ClassFacts(
        name = name,
        access = access,
        sourceFile = sourceFile,
        supertypes = supertypes.sorted(),
        outer = outer,
        references = (references + supertypes).distinct().sorted(),
        inlineSources = emptyList(),
        constantScope = null,
        constantFields = emptyList(),
        isKotlin = false,
        typeAliasScope = null,
    )

    fun graph(vararg facts: ClassFacts): DependencyGraph = DependencyGraph.of(facts.toList())
}
