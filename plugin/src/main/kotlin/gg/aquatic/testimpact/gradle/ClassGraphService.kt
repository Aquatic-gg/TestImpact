package gg.aquatic.testimpact.gradle

import gg.aquatic.testimpact.cache.ClassGraphCache
import gg.aquatic.testimpact.graph.ClassGraphResult
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.logging.Logging
import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters
import java.nio.file.Path

/**
 * Scans class roots once per build. Parallel Test tasks asking for the same set of roots share one result, and
 * class files unchanged since an earlier build are not parsed again.
 *
 * A Test task consumes it through a `@ServiceReference` property or `usesService`, then calls [graph].
 */
internal abstract class ClassGraphService : BuildService<ClassGraphService.Params>, AutoCloseable {
    internal interface Params : BuildServiceParameters {
        val cacheDirectory: DirectoryProperty
    }

    private val cache: ClassGraphCache by lazy {
        val logger = Logging.getLogger(ClassGraphService::class.java)
        ClassGraphCache(parameters.cacheDirectory.get().asFile.toPath(), log = { message -> logger.info(message) })
    }

    private val results = OncePerKey<List<String>, ClassGraphResult> { key -> cache.scan(key.map(Path::of)) }

    internal val memoizedRootSets: Int get() = results.size

    fun graph(roots: List<Path>): ClassGraphResult {
        val key = roots.map { it.toAbsolutePath().normalize().toString() }.distinct().sorted()
        return results.get(key)
    }

    override fun close() {
        results.clear()
    }
}
