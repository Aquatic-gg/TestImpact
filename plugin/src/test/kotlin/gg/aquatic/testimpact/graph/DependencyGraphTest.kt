package gg.aquatic.testimpact.graph

import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import org.junit.jupiter.api.function.ThrowingSupplier

class DependencyGraphTest {
    private fun facts(
        name: String,
        sourceFile: String? = null,
        supertypes: List<String> = emptyList(),
        outer: String? = null,
        references: List<String> = emptyList(),
        inlineSources: List<String> = emptyList(),
        constantScope: ReachScope? = null,
        isKotlin: Boolean = false,
        typeAliasScope: ReachScope? = null,
    ) = ClassFacts(
        name = name,
        access = 0x21,
        sourceFile = sourceFile,
        supertypes = supertypes,
        outer = outer,
        references = (references + supertypes).distinct().sorted(),
        inlineSources = inlineSources,
        constantScope = constantScope,
        constantFields = emptyList(),
        isKotlin = isKotlin,
        typeAliasScope = typeAliasScope,
    )

    private fun DependencyGraph.dependencyPairs(name: String) = dependencies(name).map { it.to to it.kind }

    private fun DependencyGraph.dependentPairs(name: String) = dependents(name).map { it.from to it.kind }

    @Test
    fun explicitEdgesAreSortedAndDeduplicatedByPriority() {
        val graph = DependencyGraph.of(
            listOf(
                facts("p.A", supertypes = listOf("p.Z"), references = listOf("p.M", "p.B"), inlineSources = listOf("p.B")),
                facts("p.B"),
            ),
        )

        assertEquals(
            listOf("p.B" to EdgeKind.REFERENCE, "p.M" to EdgeKind.REFERENCE, "p.Z" to EdgeKind.SUPERTYPE),
            graph.dependencyPairs("p.A"),
        )
        assertEquals(listOf("p.A" to EdgeKind.REFERENCE), graph.dependentPairs("p.B"))
    }

    @Test
    fun unscannedNamesAreKeptAndQueryable() {
        val graph = DependencyGraph.of(listOf(facts("p.A", references = listOf("lib.Gone"))))

        assertEquals(listOf("lib.Gone" to EdgeKind.REFERENCE), graph.dependencyPairs("p.A"))
        assertEquals(listOf("p.A" to EdgeKind.REFERENCE), graph.dependentPairs("lib.Gone"))
        assertEquals(emptyList(), graph.dependencies("lib.Gone"))
        assertNull(graph.facts("lib.Gone"))
        assertEquals("lib.Gone", graph.owner("lib.Gone"))
    }

    @Test
    fun selfReferencesProduceNoEdge() {
        val graph = DependencyGraph.of(
            listOf(facts("p.A", references = listOf("p.A"), constantScope = ReachScope.ALL, typeAliasScope = ReachScope.ALL, isKotlin = true)),
        )

        assertEquals(emptyList(), graph.dependencies("p.A"))
        assertEquals(emptyList(), graph.dependents("p.A"))
    }

    @Test
    fun publicConstantReachesEveryScannedClass() {
        val graph = DependencyGraph.of(
            listOf(
                facts("a.Config", constantScope = ReachScope.ALL),
                facts("a.User"),
                facts("b.Other"),
            ),
        )

        assertEquals(
            listOf("a.User" to EdgeKind.CONSTANT, "b.Other" to EdgeKind.CONSTANT),
            graph.dependentPairs("a.Config"),
        )
        assertEquals(listOf("a.Config" to EdgeKind.CONSTANT), graph.dependencyPairs("b.Other"))
    }

    @Test
    fun packageConstantStaysInItsPackage() {
        val graph = DependencyGraph.of(
            listOf(
                facts("a.Config", constantScope = ReachScope.PACKAGE),
                facts("a.User"),
                facts("a.sub.Child"),
                facts("b.Other"),
            ),
        )

        assertEquals(listOf("a.User" to EdgeKind.CONSTANT), graph.dependentPairs("a.Config"))
        assertEquals(emptyList(), graph.dependencies("b.Other"))
        assertEquals(emptyList(), graph.dependencies("a.sub.Child"))
    }

    @Test
    fun fileConstantReachesSameSourceAndOwnerGroupOnly() {
        val graph = DependencyGraph.of(
            listOf(
                facts("p.FooKt", sourceFile = "Foo.kt", constantScope = ReachScope.FILE, isKotlin = true),
                facts("p.Foo", sourceFile = "Foo.kt", isKotlin = true),
                facts("p.Foo\$Companion", sourceFile = "Foo.kt", outer = "p.Foo", isKotlin = true),
                facts("p.Bar", sourceFile = "Bar.kt", isKotlin = true),
                facts("q.Foo", sourceFile = "Foo.kt", isKotlin = true),
            ),
        )

        assertEquals(
            listOf("p.Foo" to EdgeKind.CONSTANT, "p.Foo\$Companion" to EdgeKind.CONSTANT),
            graph.dependentPairs("p.FooKt"),
        )
    }

    @Test
    fun fileConstantOfNestedClassReachesTheWholeOwnerGroup() {
        val graph = DependencyGraph.of(
            listOf(
                facts("p.Outer", sourceFile = "Outer.java"),
                facts("p.Outer\$Inner", sourceFile = "Outer.java", outer = "p.Outer", constantScope = ReachScope.FILE),
                facts("p.Outer\$Other", sourceFile = "Outer.java", outer = "p.Outer"),
                facts("p.Elsewhere", sourceFile = "Elsewhere.java"),
            ),
        )

        assertEquals(
            listOf("p.Outer" to EdgeKind.CONSTANT, "p.Outer\$Other" to EdgeKind.CONSTANT),
            graph.dependentPairs("p.Outer\$Inner"),
        )
        assertEquals(listOf("p.Outer\$Inner" to EdgeKind.CONSTANT), graph.dependencyPairs("p.Outer"))
    }

    @Test
    fun explicitDependentsAndScopeGroupsTogetherEqualDependents() {
        val graph = DependencyGraph.of(
            listOf(
                facts("p.A", sourceFile = "A.kt", constantScope = ReachScope.ALL, isKotlin = true),
                facts("p.FooKt", sourceFile = "Foo.kt", constantScope = ReachScope.FILE, typeAliasScope = ReachScope.FILE, isKotlin = true),
                facts("p.Foo", sourceFile = "Foo.kt", references = listOf("p.FooKt", "p.A"), isKotlin = true),
                facts("p.Foo\$In", sourceFile = "Foo.kt", outer = "p.Foo", constantScope = ReachScope.FILE, isKotlin = true),
                facts("p.Pkg", sourceFile = "Pkg.kt", constantScope = ReachScope.PACKAGE, typeAliasScope = ReachScope.ALL, isKotlin = true),
                facts("p.Java", sourceFile = "Java.java", supertypes = listOf("p.Pkg")),
                facts("q.Other", sourceFile = "Other.kt", references = listOf("p.Pkg"), isKotlin = true),
            ),
        )

        for (name in graph.classes + "p.Unscanned") {
            val merged = HashMap<String, EdgeKind>()
            fun put(from: String, kind: EdgeKind) {
                if (from != name) merged.merge(from, kind) { a, b -> if (a <= b) a else b }
            }
            graph.explicitDependentsOf(name).forEach { put(it.from, it.kind) }
            graph.scopeGroups(name).forEach { group -> group.members().forEach { put(it, group.kind) } }
            assertEquals(graph.dependentPairs(name), merged.map { it.key to it.value }.sortedBy { it.first }, name)
        }
    }

    @Test
    fun fileScopeIsSplitIntoSourceAndOwnerGroups() {
        val graph = DependencyGraph.of(
            listOf(
                facts("p.AKt", sourceFile = "F.kt", constantScope = ReachScope.FILE, isKotlin = true),
                facts("p.BKt", sourceFile = "F.kt", constantScope = ReachScope.FILE, isKotlin = true),
            ),
        )

        val a = graph.scopeGroups("p.AKt")
        val b = graph.scopeGroups("p.BKt")
        assertEquals(2, a.size)
        assertEquals(listOf("S:p/F.kt"), a.map { it.key }.filter { it.startsWith("S:") })
        assertEquals(a.first().key, b.first().key)
        assertTrue(a.last().key != b.last().key)
    }

    @Test
    fun typeAliasReachesKotlinClassesOnly() {
        val graph = DependencyGraph.of(
            listOf(
                facts("p.AliasesKt", typeAliasScope = ReachScope.ALL, isKotlin = true),
                facts("p.KotlinUser", isKotlin = true),
                facts("p.JavaUser"),
            ),
        )

        assertEquals(listOf("p.KotlinUser" to EdgeKind.TYPEALIAS), graph.dependentPairs("p.AliasesKt"))
        assertEquals(emptyList(), graph.dependencies("p.JavaUser"))
        assertEquals(listOf("p.AliasesKt" to EdgeKind.TYPEALIAS), graph.dependencyPairs("p.KotlinUser"))
    }

    @Test
    fun privateTypeAliasStaysInItsFile() {
        val graph = DependencyGraph.of(
            listOf(
                facts("p.FooKt", sourceFile = "Foo.kt", typeAliasScope = ReachScope.FILE, isKotlin = true),
                facts("p.Foo", sourceFile = "Foo.kt", isKotlin = true),
                facts("p.Bar", sourceFile = "Bar.kt", isKotlin = true),
            ),
        )

        assertEquals(listOf("p.Foo" to EdgeKind.TYPEALIAS), graph.dependentPairs("p.FooKt"))
    }

    @Test
    fun explicitKindWinsOverScopedKind() {
        val graph = DependencyGraph.of(
            listOf(
                facts("a.Config", constantScope = ReachScope.ALL),
                facts("a.User", references = listOf("a.Config")),
                facts("a.Child", supertypes = listOf("a.Config")),
            ),
        )

        assertEquals(
            listOf("a.Child" to EdgeKind.SUPERTYPE, "a.User" to EdgeKind.REFERENCE),
            graph.dependentPairs("a.Config"),
        )
    }

    @Test
    fun constantAndAliasOnOneClassKeepTheLowerKind() {
        val graph = DependencyGraph.of(
            listOf(
                facts("p.Both", constantScope = ReachScope.ALL, typeAliasScope = ReachScope.ALL, isKotlin = true),
                facts("p.User", isKotlin = true),
            ),
        )

        assertEquals(listOf("p.User" to EdgeKind.CONSTANT), graph.dependentPairs("p.Both"))
    }

    @Test
    fun duplicateNamesAreUnitedWithTheWidestScope() {
        val narrow = facts("p.A", references = listOf("p.X"), constantScope = ReachScope.FILE, sourceFile = "A.java")
        val wide = facts("p.A", references = listOf("p.Y"), constantScope = ReachScope.ALL, sourceFile = "A.java")
        val user = facts("q.U")

        val forward = DependencyGraph.of(listOf(narrow, wide, user))
        val backward = DependencyGraph.of(listOf(user, wide, narrow))

        assertEquals(listOf("p.X", "p.Y"), forward.facts("p.A")?.references)
        assertEquals(ReachScope.ALL, forward.facts("p.A")?.constantScope)
        assertEquals(listOf("q.U" to EdgeKind.CONSTANT), forward.dependentPairs("p.A"))
        assertEquals(forward.facts("p.A"), backward.facts("p.A"))
    }

    @Test
    fun ownerFollowsOuterChainToTheTopLevelClass() {
        val graph = DependencyGraph.of(
            listOf(
                facts("p.Foo"),
                facts("p.Foo\$Companion", outer = "p.Foo"),
                facts("p.Foo\$Companion\$x\$1", outer = "p.Foo\$Companion"),
                facts("p.Lone"),
            ),
        )

        assertEquals("p.Foo", graph.owner("p.Foo\$Companion\$x\$1"))
        assertEquals("p.Foo", graph.owner("p.Foo"))
        assertEquals(listOf("p.Foo", "p.Foo\$Companion", "p.Foo\$Companion\$x\$1"), graph.ownedBy("p.Foo"))
        assertEquals(listOf("p.Lone"), graph.ownedBy("p.Lone"))
        assertEquals(emptyList(), graph.ownedBy("p.Nobody"))
    }

    @Test
    fun ownerOfMultifilePartIsTheFacade() {
        val graph = DependencyGraph.of(listOf(facts("p.Util"), facts("p.Util__PartKt", outer = "p.Util")))

        assertEquals("p.Util", graph.owner("p.Util__PartKt"))
    }

    @Test
    fun ownerCycleResolvesToTheSmallestNameDeterministically() {
        val a = facts("p.A", outer = "p.B")
        val b = facts("p.B", outer = "p.A")
        val tail = facts("p.C", outer = "p.B")

        val graph = DependencyGraph.of(listOf(b, tail, a))
        val reversed = DependencyGraph.of(listOf(a, tail, b))

        for (name in listOf("p.A", "p.B", "p.C")) {
            assertEquals("p.A", graph.owner(name))
            assertEquals("p.A", reversed.owner(name))
        }
        assertEquals(listOf("p.A", "p.B", "p.C"), graph.ownedBy("p.A"))
    }

    @Test
    fun longOuterChainResolvesInLinearTime() {
        val depth = 100_000
        val chain = (0..depth).map { facts("p.C$it", outer = if (it == 0) null else "p.C${it - 1}") }

        val graph = assertTimeoutPreemptively(Duration.ofSeconds(20), ThrowingSupplier { DependencyGraph.of(chain.reversed()) })

        assertEquals("p.C0", graph.owner("p.C$depth"))
        assertEquals("p.C0", graph.owner("p.C${depth / 2}"))
        assertEquals(depth + 1, graph.ownedBy("p.C0").size)
    }

    @Test
    fun sourceOfJoinsPackagePathAndSourceFile() {
        val graph = DependencyGraph.of(
            listOf(facts("a.b.Foo", sourceFile = "Foo.kt"), facts("Top", sourceFile = "Top.java"), facts("a.b.NoSource")),
        )

        assertEquals("a/b/Foo.kt", graph.sourceOf("a.b.Foo"))
        assertEquals("Top.java", graph.sourceOf("Top"))
        assertNull(graph.sourceOf("a.b.NoSource"))
        assertNull(graph.sourceOf("a.b.Missing"))
    }

    @Test
    fun classesAreSortedAndQueriesAreStableAcrossInputOrder() {
        val input = listOf(
            facts("p.B", references = listOf("p.A")),
            facts("p.A", constantScope = ReachScope.ALL),
            facts("p.C"),
        )

        val graph = DependencyGraph.of(input)
        val reversed = DependencyGraph.of(input.reversed())

        assertEquals(listOf("p.A", "p.B", "p.C"), graph.classes)
        for (name in graph.classes) {
            assertEquals(graph.dependencies(name), reversed.dependencies(name))
            assertEquals(graph.dependents(name), reversed.dependents(name))
        }
        assertTrue(graph.dependents("p.A").zipWithNext().all { (x, y) -> x.from < y.from })
    }

    @Test
    fun unknownNameHasNoEdges() {
        val graph = DependencyGraph.of(listOf(facts("a.Config", constantScope = ReachScope.ALL)))

        assertEquals(emptyList(), graph.dependencies("z.Nothing"))
    }
}
