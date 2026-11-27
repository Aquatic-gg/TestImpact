package gg.aquatic.testimpact.cache.internal

/** A cache file whose content contradicts its own structure; only used inside the codec. */
internal class CorruptCacheException(message: String) : Exception(message)
