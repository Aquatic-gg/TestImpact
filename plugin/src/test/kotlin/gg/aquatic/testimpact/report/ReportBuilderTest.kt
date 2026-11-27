package gg.aquatic.testimpact.report

import gg.aquatic.testimpact.change.ChangeDetection
import gg.aquatic.testimpact.change.UnknownReason
import gg.aquatic.testimpact.fallback.FallbackRules
import gg.aquatic.testimpact.fallback.FullRunRule
import gg.aquatic.testimpact.gradle.internal.SelectionDecision
import gg.aquatic.testimpact.gradle.internal.SelectionOutcome
import gg.aquatic.testimpact.gradle.internal.SelectionSettings
import gg.aquatic.testimpact.gradle.internal.TestImpactMode
import gg.aquatic.testimpact.graph.Edge
import gg.aquatic.testimpact.graph.EdgeKind
import gg.aquatic.testimpact.seed.SeedKind
import gg.aquatic.testimpact.select.ReachPath
import gg.aquatic.testimpact.select.SeedSet
import gg.aquatic.testimpact.select.SelectedTest
import gg.aquatic.testimpact.select.Selection
import gg.aquatic.testimpact.select.SelectionUnknownReason
import gg.aquatic.testimpact.select.SelectReason
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReportBuilderTest {

    private val emptyTask = listOf(ReportFixtures.known(":c:test", emptyList()))

    private fun build(
        selections: List<Selection>,
        detection: ChangeDetection = ReportFixtures.changedCore(),
        seeds: SeedSet = SeedSet(emptyList()),
        scrubber: PathScrubber = ReportFixtures.scrubber,
        selectionMs: Map<String, Long> = emptyMap(),
    ) = ReportFixtures.report(selections, detection, seeds, 7, selectionMs, scrubber)

    @Test
    fun `reached test names changed file seed and reached class`() {
        val report = build(listOf(ReportFixtures.known(":c:test", listOf(ReportFixtures.reachedTest()))))

        val test = report.tasks.single().tests.single()
        assertEquals("a/src/main/java/a/Core.java", test.changedFile)
        assertEquals("a.Core", test.seedClass)
        assertEquals("c.ServiceTest\$Inner", test.reachedClass)
        assertEquals("REACHED", test.reason)
        assertEquals(listOf("c.Service", "c.ServiceTest\$Inner"), test.path.map { it.from })
        assertEquals(listOf("a.Core", "c.Service"), test.path.map { it.to })
    }

    @Test
    fun `global test keeps the class that registers the extension as reached class`() {
        val tests = listOf(ReportFixtures.reachedTest(), ReportFixtures.globalTest())
        val test = build(listOf(ReportFixtures.known(":c:test", tests)))
            .tasks.single().tests.single { it.className == "c.OtherTest" }

        assertEquals("GLOBAL", test.reason)
        assertEquals("c.Ext", test.reachedClass)
        assertEquals("c.OtherTest", test.className)
    }

    @Test
    fun `reached class is the seed class when the path has no edges`() {
        val selected = SelectedTest("a.CoreTest", ReachPath("a/Core.java", "a.CoreTest", emptyList()), SelectReason.REACHED, true)

        val test = build(listOf(ReportFixtures.known(":a:test", listOf(selected)))).tasks.single().tests.single()

        assertEquals("a.CoreTest", test.reachedClass)
        assertTrue(test.path.isEmpty())
    }

    @Test
    fun `every unknown reason gives a full task with a rule`() {
        val selections = SelectionUnknownReason.entries.map { Selection.Unknown(":t:${it.name}", it, "detail") }

        val tasks = build(selections).tasks

        assertEquals(SelectionUnknownReason.entries.size, tasks.size)
        for (task in tasks) {
            assertEquals(ImpactReport.Result.FULL, task.result)
            assertNull(task.counts)
            val rule = assertNotNull(task.rule)
            assertTrue(rule.text.isNotBlank())
            assertEquals("detail", rule.detail)
            val reason = SelectionUnknownReason.valueOf(task.path.substringAfterLast(':'))
            val decided = FullRunRule.forSelection(reason)
            assertEquals(decided.id.uppercase().replace('-', '_'), rule.reason)
            assertEquals(decided.text, rule.text)
        }
    }

    @Test
    fun `forced rule comes from the change rule and is scrubbed`() {
        val scrubber = PathScrubber(listOf("/r/proj" to PathScrubber.ROOT))
        val verdict = FallbackRules.Verdict(FullRunRule.RESOURCE_CHANGE, "resource change at /r/proj/a.properties", true)

        val forced = assertNotNull(ReportFixtures.report(emptyTask, changeRule = verdict, scrubber = scrubber).forced)

        assertEquals("RESOURCE_CHANGE", forced.reason)
        assertEquals(FullRunRule.RESOURCE_CHANGE.text, forced.text)
        assertEquals("resource change at <root>/a.properties", forced.detail)
    }

    @Test
    fun `unknown detection gives state unknown with its reason`() {
        val detection = ChangeDetection.Unknown(UnknownReason.NOT_A_REPOSITORY, "no repository")

        val changes = build(emptyTask, detection = detection).changes

        assertEquals(ImpactReport.State.UNKNOWN, changes.state)
        assertEquals("NOT_A_REPOSITORY", changes.reason)
        assertNull(changes.baseRef)
        assertTrue(changes.files.isEmpty())
    }

    @Test
    fun `known detection lists the changed files`() {
        val changes = build(emptyTask).changes

        assertEquals(ImpactReport.State.KNOWN, changes.state)
        val file = changes.files.single()
        assertEquals("a/src/main/java/a/Core.java", file.path)
        assertEquals("MODIFIED", file.status)
        assertEquals("SOURCE", file.kind)
        assertEquals(listOf("a.Core"), file.classes)
    }

    @Test
    fun `totals count tasks tests and candidates and tasks are sorted`() {
        val selections = listOf(
            Selection.Unknown(":f:test", SelectionUnknownReason.UNKNOWN_ENGINE, "f.FakeEngine"),
            ReportFixtures.known(":c:test", listOf(ReportFixtures.reachedTest(), ReportFixtures.globalTest()), candidates = 5),
            ReportFixtures.known(":a:test", emptyList(), candidates = 3),
        )

        val report = build(selections, selectionMs = mapOf(":c:test" to 4L, ":a:test" to 1L))

        assertEquals(listOf(":a:test", ":c:test", ":f:test"), report.tasks.map { it.path })
        assertEquals(ImpactReport.Totals(tasks = 3, fullRunTasks = 1, selectedTests = 2, candidates = 8), report.totals)
        assertEquals(ImpactReport.Durations(detectionMs = 7, selectionMs = 5, totalMs = 12), report.durations)
    }

    @Test
    fun `task detail is scrubbed`() {
        val scrubber = PathScrubber(listOf("/r/proj" to PathScrubber.ROOT))
        val unknown = Selection.Unknown(":c:test", SelectionUnknownReason.GRAPH_UNKNOWN, "/r/proj/c/build/x.class: truncated")

        val rule = assertNotNull(build(listOf(unknown), scrubber = scrubber).tasks.single().rule)

        assertEquals("<root>/c/build/x.class: truncated", rule.detail)
    }

    @Test
    fun `edge kinds are kept by name`() {
        val selected = SelectedTest(
            "c.T",
            ReachPath("o", "a.A", listOf(Edge("c.T", "a.A", EdgeKind.INLINE))),
            SelectReason.REACHED,
            true,
        )

        val step = build(listOf(ReportFixtures.known(":c:test", listOf(selected)))).tasks.single().tests.single().path.single()

        assertEquals("INLINE", step.kind)
        assertNull(step.seed)
    }

    @Test
    fun `a hop through a seed link keeps its seed kind and the other edges have none`() {
        val edges = listOf(Edge("b.Svc", "a.Impl", EdgeKind.REFERENCE), Edge("c.T", "b.Svc", EdgeKind.REFERENCE))
        val selected = SelectedTest("c.T", ReachPath("o", "a.Impl", edges, mapOf(0 to SeedKind.SERVICE)), SelectReason.REACHED, true)

        val steps = build(listOf(ReportFixtures.known(":c:test", listOf(selected)))).tasks.single().tests.single().path

        assertEquals(listOf("SERVICE", null), steps.map { it.seed })
        assertTrue("\"seed\":\"SERVICE\"" in ReportJson.render(build(listOf(ReportFixtures.known(":c:test", listOf(selected))))).replace(" ", ""))
    }

    @Test
    fun `a wide change is a full task with the decision's rule`() {
        val settings = SelectionSettings.of(null, 1, 0, emptySet(), emptySet())
        val selection = ReportFixtures.known(":c:test", listOf(ReportFixtures.reachedTest()), candidates = 2)

        val report = ReportFixtures.report(listOf(selection), settings = settings)

        val task = report.tasks.single()
        assertEquals(ImpactReport.Result.FULL, task.result)
        assertNull(task.counts)
        assertTrue(task.tests.isEmpty())
        val rule = assertNotNull(task.rule)
        assertEquals("WIDE_CHANGE", rule.reason)
        assertEquals("1 of 2 test classes are affected, above 1%", rule.detail)
        assertEquals(ImpactReport.Totals(tasks = 1, fullRunTasks = 1, selectedTests = 0, candidates = 0), report.totals)
    }

    @Test
    fun `a task decided by the mode reads no changes and shows not analysed`() {
        val decision = assertNotNull(SelectionDecision.shortCircuit(":c:test", TestImpactMode.FULL, false, 2))

        val report = ReportBuilder.build(":c", listOf(SelectionOutcome(decision)), ReportFixtures.scrubber)

        assertEquals("MODE_FULL", report.tasks.single().rule?.reason)
        assertEquals(ImpactReport.State.UNKNOWN, report.changes.state)
        assertEquals(ReportBuilder.NOT_ANALYSED, report.changes.reason)
        assertEquals("every task was decided before reading the changes", report.changes.detail)
    }

    @Test
    fun `a project without a Test task shows not analysed`() {
        val report = ReportBuilder.build(":c", emptyList(), ReportFixtures.scrubber)

        assertEquals(ReportBuilder.NOT_ANALYSED, report.changes.reason)
        assertEquals("the project has no Test task", report.changes.detail)
        assertTrue(report.tasks.isEmpty())
    }

    @Test
    fun `forced is shown only when every task that read the changes is forced alike`() {
        val verdict = FallbackRules.Verdict(FullRunRule.BUILD_LOGIC, "build.gradle.kts", true)
        val detection = ReportFixtures.changedCore()
        val settings = SelectionSettings.of(null, 60, 12, emptySet(), emptySet())
        fun forcedOutcome(path: String): SelectionOutcome =
            SelectionOutcome(SelectionDecision.fallback(path, TestImpactMode.AUTO, verdict, detection, 0), detection, changeRule = verdict.rule)
        fun selectedOutcome(path: String): SelectionOutcome {
            val selection = ReportFixtures.known(path, emptyList())
            val decision = SelectionDecision.decide(selection, detection, settings, TestImpactMode.AUTO, false, 0)
            return SelectionOutcome(decision, detection, SeedSet(emptyList()), selection)
        }

        val alike = ReportBuilder.build(":c", listOf(forcedOutcome(":c:a"), forcedOutcome(":c:b")), ReportFixtures.scrubber)
        val mixed = ReportBuilder.build(":c", listOf(forcedOutcome(":c:a"), selectedOutcome(":c:b")), ReportFixtures.scrubber)

        assertEquals("BUILD_LOGIC", alike.forced?.reason)
        assertNull(mixed.forced)
        assertEquals(ImpactReport.Result.SELECTED, mixed.tasks.single { it.path == ":c:b" }.result)
    }
}
