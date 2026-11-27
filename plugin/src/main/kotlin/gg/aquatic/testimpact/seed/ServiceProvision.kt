package gg.aquatic.testimpact.seed

/** One `provides service with providers` clause of a module descriptor; [providers] are sorted binary names. */
internal data class ServiceProvision(
    val service: String,
    val providers: List<String>,
)
