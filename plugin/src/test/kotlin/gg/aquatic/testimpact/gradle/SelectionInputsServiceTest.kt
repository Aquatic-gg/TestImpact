package gg.aquatic.testimpact.gradle

import gg.aquatic.testimpact.change.ChangeDetection
import gg.aquatic.testimpact.change.DetectionInput
import gg.aquatic.testimpact.change.UnknownReason
import gg.aquatic.testimpact.change.internal.GitRead
import gg.aquatic.testimpact.gradle.internal.ServiceNames
import gg.aquatic.testimpact.seed.SeedResult
import org.gradle.api.provider.Provider
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame

class SelectionInputsServiceTest {
    @TempDir
    lateinit var temp: Path

    private fun service(): SelectionInputsService {
        val project = ProjectBuilder.builder().withProjectDir(temp.resolve("p").also(Files::createDirectories).toFile()).build()
        project.plugins.apply(TestImpactPlugin::class.java)
        val registration = project.gradle.sharedServices.registrations.getByName(ServiceNames.selectionInputs)
        @Suppress("UNCHECKED_CAST")
        return (registration.service as Provider<SelectionInputsService>).get()
    }

    private fun input(dir: Path) = DetectionInput(dir, null, emptyList(), emptyList(), emptyList(), emptyList())

    @Test
    fun `parallel detections of the same input run once`() {
        val service = service()
        val dir = Files.createDirectories(temp.resolve("not-a-repo"))
        val pool = Executors.newFixedThreadPool(6)
        val start = CountDownLatch(1)
        try {
            val futures = (0 until 6).map {
                pool.submit<ChangeDetection> {
                    start.await()
                    service.changes(input(dir.resolve(".").resolve("..").resolve("not-a-repo")))
                }
            }
            start.countDown()
            val results = futures.map { it.get(60, TimeUnit.SECONDS) }
            assertIs<ChangeDetection.Unknown>(results.first())
            results.forEach { assertSame(results.first(), it) }
            assertEquals(1, service.memoizedDetections)
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `projects of one repository share a single git read`() {
        val service = service()
        val top = Files.createDirectories(temp.resolve("repo"))
        val reads = AtomicInteger()
        service.topLevelOf = { top }
        service.gitReadOf = { _, _ ->
            reads.incrementAndGet()
            GitRead.Failed(ChangeDetection.Unknown(UnknownReason.GIT_FAILED, "fake"))
        }
        val a = input(top.resolve("a"))
        val b = input(top.resolve("b"))

        service.changes(a)
        service.changes(b)
        service.changes(a.copy(sourceRoots = listOf(top.resolve("a/src"))))

        assertEquals(1, reads.get())
        assertEquals(1, service.memoizedGitReads)
        assertEquals(3, service.memoizedDetections)
        service.changes(a.copy(baseRef = "main"))
        assertEquals(2, reads.get())
    }

    @Test
    fun `a shared root is scanned once for different root sets`() {
        val service = service()
        val a = Files.createDirectories(temp.resolve("a"))
        val b = Files.createDirectories(temp.resolve("b"))
        val c = Files.createDirectories(temp.resolve("c"))
        service.seeds(listOf(a, b), emptyList())
        service.seeds(listOf(a, c), emptyList())
        assertEquals(3, service.memoizedRootScans)
        assertEquals(2, service.memoizedScans)
    }

    @Test
    fun `seed scans are shared per root set and order does not matter`() {
        val service = service()
        val a = Files.createDirectories(temp.resolve("a"))
        val b = Files.createDirectories(temp.resolve("b"))
        val first = service.seeds(listOf(a, b), listOf(a))
        assertSame(first, service.seeds(listOf(b, a, a), listOf(a)))
        assertIs<SeedResult.Known>(first)
        assertEquals(1, service.memoizedScans)
        service.seeds(listOf(a, b), emptyList())
        assertEquals(2, service.memoizedScans)
    }

    @Test
    fun `close clears the memos`() {
        val service = service()
        service.seeds(listOf(Files.createDirectories(temp.resolve("a"))), emptyList())
        service.close()
        assertEquals(0, service.memoizedScans)
        assertEquals(0, service.memoizedDetections)
    }
}
