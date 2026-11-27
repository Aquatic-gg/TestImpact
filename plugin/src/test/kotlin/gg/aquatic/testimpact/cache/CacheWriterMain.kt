package gg.aquatic.testimpact.cache

import gg.aquatic.testimpact.graph.ClassGraphResult
import gg.aquatic.testimpact.graph.ClassGraphScanner
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.system.exitProcess

/**
 * Entry point of the child JVMs of [TwoProcessCacheTest]: scans one corpus repeatedly through a cache shared with
 * another process, alternating the plugin version so that every round rewrites the file.
 * Arguments: cache directory, corpus directory, go file, rounds.
 */
internal object CacheWriterMain {
    private const val GO_TIMEOUT_MILLIS = 120_000L

    @JvmStatic
    fun main(args: Array<String>) {
        val cacheDir = Paths.get(args[0])
        val corpus = Paths.get(args[1])
        val go = Paths.get(args[2])
        val rounds = args[3].toInt()
        val expected = (ClassGraphScanner.scan(listOf(corpus)) as ClassGraphResult.Known).graph.classes.size
        println("ready")
        System.out.flush()
        awaitGo(go)
        repeat(rounds) { round ->
            val stamp = CacheStamp(CacheStamp.FORMAT_VERSION, if (round % 2 == 0) "a" else "b")
            val result = ClassGraphCache(cacheDir, stamp).scan(listOf(corpus))
            val count = (result as? ClassGraphResult.Known)?.graph?.classes?.size
            if (count != expected) {
                System.err.println("round $round: expected $expected classes, got $count ($result)")
                exitProcess(1)
            }
        }
    }

    private fun awaitGo(go: Path) {
        val deadline = System.currentTimeMillis() + GO_TIMEOUT_MILLIS
        while (!Files.exists(go)) {
            if (System.currentTimeMillis() > deadline) {
                System.err.println("no go signal")
                exitProcess(2)
            }
            Thread.sleep(10)
        }
    }
}
