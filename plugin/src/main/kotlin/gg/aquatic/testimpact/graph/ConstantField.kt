package gg.aquatic.testimpact.graph

/**
 * A field with a ConstantValue attribute, whose value compilers copy into every user.
 * [value] is the text of the constant (a string as is, numbers as `toString`), [scope] who may use it: the
 * visibility of the field, narrowed by the visibility of its class only when the class is final and not an
 * interface. Any other class can be subclassed, and a user reading the constant through a wider subclass leaves
 * no reference to the declaring class.
 */
internal data class ConstantField(
    val name: String,
    val descriptor: String,
    val value: String,
    val scope: ReachScope,
)
