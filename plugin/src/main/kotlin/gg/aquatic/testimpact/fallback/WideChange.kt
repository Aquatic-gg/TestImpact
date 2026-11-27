package gg.aquatic.testimpact.fallback

/** The wide-change threshold: a change that reaches most of the test classes is cheaper to run in full. */
internal object WideChange {

    /**
     * @param selected test classes the change reaches
     * @param candidates test classes the task has
     * @param percent the share of [candidates] that [selected] must exceed
     * @param minClasses fewer candidates than this never count as wide
     */
    fun exceeds(selected: Int, candidates: Int, percent: Int, minClasses: Int): Boolean =
        candidates >= minClasses && selected.toLong() * 100 > percent.toLong() * candidates
}
