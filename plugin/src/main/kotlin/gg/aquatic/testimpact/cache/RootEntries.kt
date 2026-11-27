package gg.aquatic.testimpact.cache

import gg.aquatic.testimpact.graph.ClassFacts

/** The facts known for one class root, by the content of the class file they were read from. Never mutated. */
internal class RootEntries(val rootKey: String, val entries: Map<ContentKey, ClassFacts>) {
    companion object {
        fun empty(rootKey: String): RootEntries = RootEntries(rootKey, emptyMap())
    }
}
