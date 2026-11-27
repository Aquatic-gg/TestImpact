package gg.aquatic.testimpact.cache.internal

import gg.aquatic.testimpact.cache.ContentKey
import gg.aquatic.testimpact.graph.ClassFacts

/** Outcome of reading a cache file: the stored facts, or the reason the file cannot be trusted. */
internal sealed interface Decoded {
    data class Entries(val entries: Map<ContentKey, ClassFacts>) : Decoded

    data class Rejected(val reason: String) : Decoded
}
