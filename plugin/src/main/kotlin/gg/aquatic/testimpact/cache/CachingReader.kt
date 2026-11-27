package gg.aquatic.testimpact.cache

import gg.aquatic.testimpact.graph.ClassFacts
import gg.aquatic.testimpact.graph.ClassReading
import java.nio.file.Path
import java.security.MessageDigest

/**
 * Serves class facts from the snapshot of a root when the digest of the class bytes is known and parses otherwise.
 * One instance belongs to one scan and is not thread safe; it records what the scan saw so the cache can be
 * rewritten with exactly that.
 */
internal class CachingReader(
    private val snapshots: (Path) -> RootEntries,
    private val parse: (ByteArray) -> ClassFacts,
) : ClassReading {
    /** What a scan found under one root: the entries to persist and whether they differ from the snapshot. */
    class RootResult(val entries: RootEntries, val changed: Boolean)

    private class Seen(val snapshot: RootEntries) {
        val facts = HashMap<ContentKey, ClassFacts>()
        var added = 0
    }

    private val digest = MessageDigest.getInstance("SHA-256")
    private val seen = HashMap<String, Seen>()

    override fun read(root: Path, bytes: ByteArray): ClassFacts {
        val state = state(root)
        val key = ContentKey.of(digest.digest(bytes))
        state.facts[key]?.let { return it }
        val cached = state.snapshot.entries[key]
        if (cached != null) {
            state.facts[key] = cached
            return cached
        }
        val parsed = parse(bytes)
        state.facts[key] = parsed
        state.added++
        return parsed
    }

    /** The outcome for [root]; a root that was never read has no entries. */
    fun result(root: Path): RootResult {
        val state = state(root)
        val changed = state.added > 0 || state.facts.size != state.snapshot.entries.size
        return RootResult(RootEntries(state.snapshot.rootKey, state.facts), changed)
    }

    private fun state(root: Path): Seen =
        seen.getOrPut(rootKey(root)) { Seen(snapshots(root)) }

    companion object {
        fun rootKey(root: Path): String = root.toAbsolutePath().normalize().toString()
    }
}
