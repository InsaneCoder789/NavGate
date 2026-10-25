package com.rohanc.navgate.data.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SearchCacheTest {
    @Test fun `an answer is kept for its lifetime and then forgotten`() {
        var now = 0L; val cache = SearchCache<String>(4, 1_000) { now }
        cache.put("a", "one"); now = 900; assertEquals("one", cache.get("a")); now = 1_001; assertNull(cache.get("a"))
    }

    @Test fun `the oldest answer goes first when the cache is full`() {
        val cache = SearchCache<Int>(2, 10_000) { 0L }
        cache.put("a", 1); cache.put("b", 2); cache.get("a"); cache.put("c", 3)
        assertEquals(1, cache.get("a")); assertNull(cache.get("b")); assertEquals(3, cache.get("c"))
    }
}
