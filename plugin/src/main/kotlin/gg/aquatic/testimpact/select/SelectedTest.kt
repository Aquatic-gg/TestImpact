package gg.aquatic.testimpact.select

/**
 * @property className binary name of the top-level test class.
 * @property path how the change reached the class.
 * @property recognised whether framework markers identified the class as a test; a class that is not recognised is
 * still selected.
 */
internal data class SelectedTest(
    val className: String,
    val path: ReachPath,
    val reason: SelectReason,
    val recognised: Boolean,
)
