package gg.aquatic.testimpact.select

/**
 * Seeds derived from one change detection.
 *
 * @property seeds sorted by origin, without duplicates.
 * @property forced set when the change cannot be trusted; the seeds are then informational only.
 */
internal data class SeedSet(
    val seeds: List<Seed>,
    val forced: Forced? = null,
)
