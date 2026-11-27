package gg.aquatic.testimpact.gradle

import gg.aquatic.testimpact.select.SelectReason

/**
 * One selected test class and why it was selected.
 *
 * @property className binary name of the top-level test class.
 * @property reason why the class was selected.
 * @property recognised whether framework markers identified the class as a test.
 * @property origin the seed origin of the path, normally the changed file; `alwaysRun` for a pattern match.
 * @property start the class the path began at.
 * @property edges the path from [start] outward, each edge written as `from>to:KIND`; empty when the selected
 * class is the start itself.
 * @property seedKinds for an edge index in [edges] that is a hop through a name, service or resource link rather
 * than bytecode, written as `index:KIND`.
 */
internal data class SelectedEntry(
    val className: String,
    val reason: SelectReason,
    val recognised: Boolean,
    val origin: String,
    val start: String,
    val edges: List<String>,
    val seedKinds: List<String>,
)
