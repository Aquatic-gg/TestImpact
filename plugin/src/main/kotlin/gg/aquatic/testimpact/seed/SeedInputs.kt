package gg.aquatic.testimpact.seed

/** Everything scanned from the roots: class facts sorted by name and resource facts sorted by path. */
internal data class SeedInputs(
    val classes: List<SeedFacts>,
    val resources: List<ResourceFacts>,
)
