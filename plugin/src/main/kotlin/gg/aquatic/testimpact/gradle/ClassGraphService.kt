package gg.aquatic.testimpact.gradle

import gg.aquatic.testimpact.cache.ClassGraphCache
import gg.aquatic.testimpact.graph.ClassGraphResult
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.logging.Logging
import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask

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

    private val results = ConcurrentHashMap<List<String>, FutureTask<ClassGraphResult>>()

    private val cache: ClassGraphCache by lazy {
        val logger = Logging.getLogger(ClassGraphService::class.java)
        ClassGraphCache(parameters.cacheDirectory.get().asFile.toPath()) { message -> logger.info(message) }
    }

    internal val memoizedRootSets: Int get() = results.size

    fun graph(roots: List<Path>): ClassGraphResult {
        val normalized = roots.map { it.toAbsolutePath().normalize() }
        val key = normalized.map(Path::toString).distinct().sorted()
        val created = FutureTask { cache.scan(normalized) }
        val task = results.putIfAbsent(key, created) ?: created.also { it.run() }
        try {
            return task.get()
        } catch (e: ExecutionException) {
            results.remove(key, task)
            throw e.cause ?: e
        }
    }

    override fun close() {
        results.clear()
    }
}
