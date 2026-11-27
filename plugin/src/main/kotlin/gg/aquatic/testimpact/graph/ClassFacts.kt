package gg.aquatic.testimpact.graph

/**
 * Everything one class file says about its dependencies; the unit that can be cached per class.
 * Names are binary names (`p.Outer$Inner`), every collection is sorted and free of the class itself.
 *
 * - [supertypes]: superclass and interfaces.
 * - [references]: every other class the file mentions, including the supertypes.
 * - [inlineSources]: classes whose inline function bodies were copied into this class (Kotlin source map).
 * - [outer]: the class this one is declared in, if any.
 * - [constantScope]: widest scope of [constantFields], null without constants.
 * - [typeAliasScope]: scope of the typealiases a Kotlin class declares, null without any.
 */
internal data class ClassFacts(
    val name: String,
    val access: Int,
    val sourceFile: String?,
    val supertypes: List<String>,
    val outer: String?,
    val references: List<String>,
    val inlineSources: List<String>,
    val constantScope: ReachScope?,
    val constantFields: List<ConstantField>,
    val isKotlin: Boolean,
    val typeAliasScope: ReachScope?,
)
