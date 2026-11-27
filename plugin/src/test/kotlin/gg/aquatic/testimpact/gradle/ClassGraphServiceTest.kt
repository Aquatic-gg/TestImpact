package gg.aquatic.testimpact.gradle

import gg.aquatic.testimpact.cache.CacheCorpus
import gg.aquatic.testimpact.graph.ClassGraphResult
import gg.aquatic.testimpact.graph.ClassGraphScanner
import org.gradle.api.provider.Provider
import org.gradle.api.services.BuildServiceRegistration
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ClassGraphServiceTest {
    @TempDir
    lateinit var temp: Path

    private fun service(): ClassGraphService {
        val project = ProjectBuilder.builder().withProjectDir(temp.resolve("project").also(Files::createDirectories).toFile()).build()
        project.plugins.apply(TestImpactPlugin::class.java)
        val registration = project.gradle.sharedServices.registrations.getByName("testImpactClassGraph")
        @Suppress("UNCHECKED_CAST")
        val provider = (registration as BuildServiceRegistration<ClassGraphService, ClassGraphService.Params>).service as Provider<ClassGraphService>
        return provider.get()
    }

    @Test
    fun `registers the service with the class cache directory`() {
        val project = ProjectBuilder.builder().withProjectDir(temp.resolve("p").also(Files::createDirectories).toFile()).build()
        project.plugins.apply(TestImpactPlugin::class.java)
        val registration = project.gradle.sharedServices.registrations.getByName("testImpactClassGraph")
        @Suppress("UNCHECKED_CAST")
        val params = (registration as BuildServiceRegistration<ClassGraphService, ClassGraphService.Params>).parameters
        val dir = params.cacheDirectory.get().asFile.toPath().toString().replace('\\', '/')
        assertTrue(dir.endsWith("build/testimpact/class-cache"), dir)
    }

    @Test
    fun `parallel callers with the same roots share one scan`() {
        val service = service()
        val corpus = CacheCorpus.create(temp)
        val threads = 8
        val pool = Executors.newFixedThreadPool(threads)
        val start = CountDownLatch(1)
        try {
            val futures = (0 until threads).map {
                pool.submit<ClassGraphResult> {
                    start.await()
                    service.graph(listOf(corpus))
                }
            }
            start.countDown()
            val results = futures.map { it.get(60, TimeUnit.SECONDS) }
            assertIs<ClassGraphResult.Known>(results.first())
            results.forEach { assertSame(results.first(), it) }
            assertEquals(1, service.memoizedRootSets)
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `root order does not matter and another root set has its own entry`() {
        val service = service()
        val corpus = CacheCorpus.create(temp)
        val second = temp.resolve("second").also { target ->
            corpus.toFile().copyRecursively(target.toFile(), overwrite = true)
        }
        val both = service.graph(listOf(corpus, second))
        assertSame(both, service.graph(listOf(second, corpus)))
        assertEquals(1, service.memoizedRootSets)
        val single = service.graph(listOf(corpus))
        assertNotSame(both, single)
        assertEquals(2, service.memoizedRootSets)
    }

    @Test
    fun `duplicate roots give the facts of the single root`() {
        val service = service()
        val corpus = CacheCorpus.create(temp)
        val expected = assertIs<ClassGraphResult.Known>(ClassGraphScanner.scan(listOf(corpus))).graph
        val duplicated = assertIs<ClassGraphResult.Known>(service.graph(listOf(corpus, corpus))).graph
        val single = assertIs<ClassGraphResult.Known>(service.graph(listOf(corpus))).graph
        assertEquals(1, service.memoizedRootSets)
        for (graph in listOf(duplicated, single)) {
            assertEquals(expected.classes, graph.classes)
            for (name in expected.classes) assertEquals(expected.facts(name), graph.facts(name))
        }
    }

    @Test
    fun `applying the plugin does not create the service`() {
        val before = ClassGraphService.created.get()
        val project = ProjectBuilder.builder().withProjectDir(temp.resolve("lazy").also(Files::createDirectories).toFile()).build()
        project.plugins.apply(TestImpactPlugin::class.java)
        assertEquals(before, ClassGraphService.created.get())
    }

    @Test
    fun `applied from a child project the cache lives under the settings directory`() {
        val rootDir = temp.resolve("root").also(Files::createDirectories)
        val root = ProjectBuilder.builder().withProjectDir(rootDir.toFile()).build()
        val child = ProjectBuilder.builder().withName("child").withParent(root)
            .withProjectDir(rootDir.resolve("child").also(Files::createDirectories).toFile()).build()
        child.plugins.apply(TestImpactPlugin::class.java)
        val registration = child.gradle.sharedServices.registrations.getByName("testImpactClassGraph")
        @Suppress("UNCHECKED_CAST")
        val params = (registration as BuildServiceRegistration<ClassGraphService, ClassGraphService.Params>).parameters
        assertEquals(
            rootDir.resolve("build/testimpact/class-cache").toAbsolutePath().normalize(),
            params.cacheDirectory.get().asFile.toPath().toAbsolutePath().normalize(),
        )
    }

    @Test
    fun `close clears the memo`() {
        val service = service()
        service.graph(listOf(CacheCorpus.create(temp)))
        assertEquals(1, service.memoizedRootSets)
        service.close()
        assertEquals(0, service.memoizedRootSets)
    }
}
