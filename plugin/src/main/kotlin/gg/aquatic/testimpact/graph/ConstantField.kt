package gg.aquatic.testimpact.graph

/**
 * A field with a ConstantValue attribute, whose value compilers copy into every user.
 * [value] is the text of the constant (a string as is, numbers as `toString`), [scope] who may use it.
 */
internal data class ConstantField(
    val name: String,
    val descriptor: String,
    val value: String,
    val scope: ReachScope,
)
