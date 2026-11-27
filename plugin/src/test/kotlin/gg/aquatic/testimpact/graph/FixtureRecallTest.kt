package gg.aquatic.testimpact.graph

import gg.aquatic.testimpact.graph.EdgeKind.CONSTANT
import gg.aquatic.testimpact.graph.EdgeKind.INLINE
import gg.aquatic.testimpact.graph.EdgeKind.REFERENCE
import gg.aquatic.testimpact.graph.EdgeKind.SUPERTYPE
import gg.aquatic.testimpact.graph.EdgeKind.TYPEALIAS
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FixtureRecallTest {
    private val graph = FixtureClasses.graph

    private fun edge(from: String, to: String, kind: EdgeKind) =
        Edge(FixtureClasses.name(from), FixtureClasses.name(to), kind)

    private val expected = listOf(
        // refs
        edge("refs.FieldUser", "refs.FieldTarget", REFERENCE),
        edge("refs.MethodUser", "refs.ReturnType", REFERENCE),
        edge("refs.MethodUser", "refs.ParamType", REFERENCE),
        edge("refs.CtorUser", "refs.CtorTarget", REFERENCE),
        edge("refs.LiteralUser", "refs.LiteralTarget", REFERENCE),
        edge("refs.ClassAnnotated", "refs.ClassMarker", REFERENCE),
        edge("refs.RuntimeAnnotated", "refs.RuntimeMarker", REFERENCE),
        edge("refs.RuntimeAnnotated", "refs.Mode", REFERENCE),
        edge("refs.RuntimeAnnotated", "refs.Payload", REFERENCE),
        edge("refs.TypeUseUser", "refs.TypeUseMarker", REFERENCE),
        edge("refs.TypeUseUser", "refs.CastTarget", REFERENCE),
        edge("refs.SignatureOnlyUser", "refs.SigTarget", REFERENCE),
        edge("refs.ThrowsUser", "refs.DeclaredException", REFERENCE),
        edge("refs.MethodRefUser", "refs.MethodRefTarget", REFERENCE),
        edge("refs.RecordUser", "refs.RecordPart", REFERENCE),
        edge("refs.Circle", "refs.Shape", SUPERTYPE),
        edge("refs.Square", "refs.Shape", SUPERTYPE),
        edge("refs.Shape", "refs.Circle", REFERENCE),
        edge("refs.Shape", "refs.Square", REFERENCE),
        edge("refs.NestHostUser", "refs.NestHostUser\$Inner", REFERENCE),
        edge("refs.NestHostUser\$Inner", "refs.NestHostUser", REFERENCE),
        // constants: folded values leave no reference in the bytecode
        edge("constants.KObjUser", "constants.KObj", CONSTANT),
        edge("constants.TopUser", "constants.TopConstKt", CONSTANT),
        edge("constants.CompanionUser", "constants.WithCompanion", CONSTANT),
        edge("constants.JavaUsers", "constants.JavaConst", CONSTANT),
        edge("constants.JavaUsers", "constants.PackageConst", CONSTANT),
        edge("constants.SameFileUser", "constants.PrivateConstKt", CONSTANT),
        edge("constants.other.InheritedConstUser", "constants.HiddenBase", CONSTANT),
        edge("constants.other.InheritedConstUser", "constants.HiddenConsts", CONSTANT),
        // inline
        edge("inline.InlineUser", "inline.InlineDefsKt", INLINE),
        edge("inline.InlineUser", "inline.InlineObject", INLINE),
        edge("inline.InlineUser", "inline.InlineHost\$Companion", INLINE),
        edge("inline.InlineUser\$regenerated\$\$inlined\$makeRunnable\$1", "inline.InlineDefsKt\$makeRunnable\$1", INLINE),
        // alias
        edge("alias.AliasSignatureUser", "alias.AliasDefsKt", TYPEALIAS),
        edge("alias.AliasBodyUser", "alias.AliasDefsKt", TYPEALIAS),
        edge("alias.PrivateAliasUser", "alias.PrivateAliasKt", TYPEALIAS),
        edge("alias.AliasDefsKt", "alias.AliasTarget", REFERENCE),
        // value class erased from the declarations
        edge("value.ValueUserKt", "value.Meters", REFERENCE),
        // owner
        edge("owner.JavaStaticCaller", "owner.WithJvmStatic", REFERENCE),
        edge("owner.WhenUserKt", "owner.Color", REFERENCE),
        edge("owner.WhenUserKt\$WhenMappings", "owner.Color", REFERENCE),
        edge("owner.Multi", "owner.Multi__MultiAKt", REFERENCE),
        edge("owner.Multi", "owner.Multi__MultiBKt", REFERENCE),
    )

    private val absent = listOf(
        "constants.PrivateConstOutsider" to "constants.PrivateConstKt",
        "constants.KObjUser" to "constants.PrivateConstKt",
        "constants.other.OtherPackageUser" to "constants.PackageConst",
        "alias.PrivateAliasOutsider" to "alias.PrivateAliasKt",
        "alias.AliasBodyUser" to "alias.PrivateAliasKt",
        "alias.JavaAliasBystander" to "alias.AliasDefsKt",
        "alias.JavaAliasBystander" to "alias.PrivateAliasKt",
    )

    /** An edge counts when the pair is linked at least as strongly as expected: a plain reference also covers an inline source. */
    private fun covered(edge: Edge, found: List<Edge>) = found.any {
        it.from == edge.from && it.to == edge.to && it.kind <= edge.kind
    }

    @Test
    fun `every expected edge of the fixture corpus is a dependency and a dependent`() {
        val missing = expected.filter { !covered(it, graph.dependencies(it.from)) }
        assertEquals(emptyList(), missing, "dependencies missing from the graph")
        val unmirrored = expected.filter { !covered(it, graph.dependents(it.to)) }
        assertEquals(emptyList(), unmirrored, "dependents missing from the graph")
    }

    @Test
    fun `users of folded constants carry no reference of their own`() {
        val users = expected.filter { it.kind == CONSTANT }
        assertTrue(users.count { graph.facts(it.from)?.isKotlin == true } >= 4)
        assertTrue(users.count { graph.facts(it.from)?.isKotlin == false } >= 4)
        for (edge in users) {
            val references = graph.facts(edge.from)?.references.orEmpty()
            assertTrue(edge.to !in references, "${edge.from} references ${edge.to}: the fixture no longer shows a folded constant")
        }
    }

    @Test
    fun `inlined classes are listed from the source map even when a plain reference exists`() {
        fun inlineSources(name: String) = graph.facts(FixtureClasses.name(name))?.inlineSources.orEmpty()

        assertEquals(
            listOf("inline.InlineDefsKt", "inline.InlineHost\$Companion", "inline.InlineObject").map(FixtureClasses::name),
            inlineSources("inline.InlineUser"),
        )
        assertTrue(FixtureClasses.name("inline.InlineDefsKt\$makeRunnable\$1") in inlineSources("inline.InlineUser\$regenerated\$\$inlined\$makeRunnable\$1"))
    }

    @Test
    fun `scoped constants and aliases do not reach beyond their visibility`() {
        for ((from, to) in absent) {
            val found = graph.dependencies(FixtureClasses.name(from)).filter { it.to == FixtureClasses.name(to) }
            assertEquals(emptyList(), found, "$from must not depend on $to")
        }
    }

    @Test
    fun `a public constant reaches users in every package`() {
        val reached = graph.dependents(FixtureClasses.name("constants.KObj")).map { it.from }
        assertTrue(FixtureClasses.name("constants.other.OtherPackageUser") in reached)
        assertTrue(FixtureClasses.name("alias.JavaAliasBystander") in reached)
    }

    @Test
    fun `classes generated from one file share their owner or their source`() {
        fun owner(name: String) = graph.owner(FixtureClasses.name(name))
        fun name(name: String) = FixtureClasses.name(name)

        assertEquals(name("refs.NestHostUser"), owner("refs.NestHostUser\$Inner"))
        assertEquals(name("owner.WithJvmStatic"), owner("owner.WithJvmStatic\$Companion"))
        assertEquals(name("owner.AnonHost"), owner("owner.AnonHost\$make\$1"))
        assertEquals(name("owner.SuspendHost"), owner("owner.SuspendHost\$go\$1"))
        assertEquals(name("owner.OwnersKt"), owner("owner.OwnersKt\$lambdaHost\$1"))
        assertEquals(name("owner.WhenUserKt"), owner("owner.WhenUserKt\$WhenMappings"))
        assertEquals(name("inline.InlineUser"), owner("inline.InlineUser\$regenerated\$\$inlined\$makeRunnable\$1"))
        assertEquals(name("owner.Multi"), owner("owner.Multi__MultiAKt"))
        assertEquals(name("owner.Multi"), owner("owner.Multi__MultiBKt"))
        assertEquals(
            listOf(name("owner.Multi"), name("owner.Multi__MultiAKt"), name("owner.Multi__MultiBKt")),
            graph.ownedBy(name("owner.Multi")),
        )

        val classSource = graph.sourceOf(name("owner.TopAndClass"))
        assertEquals(classSource, graph.sourceOf(name("owner.TopAndClassKt")))
        assertNotEquals(classSource, graph.sourceOf(name("owner.OwnersKt")))
        assertNull(graph.sourceOf(name("owner.Multi")))
    }
}
