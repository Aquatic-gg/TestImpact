package gg.aquatic.testimpact.gradle

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask

/**
 * Computes the value of each key once, however many threads ask at the same time. A failed computation is not kept:
 * its exception reaches every waiting caller and the next call for the key computes again.
 */
internal class OncePerKey<K : Any, V : Any>(private val compute: (K) -> V) {
    private val tasks = ConcurrentHashMap<K, FutureTask<V>>()

    val size: Int get() = tasks.size

    fun get(key: K): V {
        val created = FutureTask { compute(key) }
        val task = tasks.putIfAbsent(key, created) ?: created.also { it.run() }
        try {
            return task.get()
        } catch (e: ExecutionException) {
            tasks.remove(key, task)
            throw e.cause ?: e
        }
    }

    fun clear() {
        tasks.clear()
    }
}
