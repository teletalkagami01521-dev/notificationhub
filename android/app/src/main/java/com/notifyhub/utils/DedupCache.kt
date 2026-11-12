package com.notifyhub.utils

object DedupCache {

    private const val MAX_SIZE = 200
    private const val WINDOW_MS = 90_000L  // 90 seconds

    // LRU-ish map: evicts eldest when over capacity
    private val cache = object : LinkedHashMap<String, Long>(MAX_SIZE + 1, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>) = size > MAX_SIZE
    }

    @Synchronized
    fun isDuplicate(hash: String): Boolean {
        val now = System.currentTimeMillis()
        val ts = cache[hash]
        if (ts != null && (now - ts) < WINDOW_MS) return true
        cache[hash] = now
        return false
    }
}
