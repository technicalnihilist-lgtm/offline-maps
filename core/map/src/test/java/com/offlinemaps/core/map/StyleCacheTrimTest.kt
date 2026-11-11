package com.offlinemaps.core.map

import android.content.ComponentCallbacks2
import com.offlinemaps.core.common.MapTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The style cache and the memory trim (OPTIMIZATION.md H-02).
 *
 * Two halves are pinned here. The policy decides which `onTrimMemory` levels are worth a rebuild on
 * the main thread, and the release itself must empty the cache without touching the counters - a
 * trim is not a cache reset, and the hit/miss figures are how the cache is diagnosed.
 */
@RunWith(RobolectricTestRunner::class)
class StyleCacheTrimTest {

    @Before
    @After
    fun resetCache() = MapStyleFactory.clearStyleCache()

    @Test
    fun `levels that mean real pressure release the cached documents`() {
        val releasing = listOf(
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW,
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL,
            ComponentCallbacks2.TRIM_MEMORY_BACKGROUND,
            ComponentCallbacks2.TRIM_MEMORY_MODERATE,
            ComponentCallbacks2.TRIM_MEMORY_COMPLETE,
        )
        releasing.forEach { level ->
            assertTrue("level $level should release the style cache", StyleCacheMemoryPolicy.shouldRelease(level))
        }
    }

    @Test
    fun `a visible or merely hidden app keeps the cached documents`() {
        // UI_HIDDEN is deliberately absent: the app is only in the background, and coming back
        // would pay a main-thread rebuild for a few tens of kilobytes.
        val keeping = listOf(
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE,
            ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN,
            0,
            -1,
            Int.MAX_VALUE,
        )
        keeping.forEach { level ->
            assertFalse("level $level should keep the style cache", StyleCacheMemoryPolicy.shouldRelease(level))
        }
    }

    @Test
    fun `releasing empties the cache and keeps the counters`() {
        val light = StyleOptions(theme = MapTheme.LIGHT)
        val dark = StyleOptions(theme = MapTheme.DARK)
        val first = MapStyleFactory.buildJson(light)
        MapStyleFactory.buildJson(dark)
        MapStyleFactory.buildJson(light) // a hit
        assertEquals(2, MapStyleFactory.cachedStyleCount())
        assertEquals(1L, MapStyleFactory.styleCacheHits())
        assertEquals(2L, MapStyleFactory.styleCacheMisses())

        MapStyleFactory.releaseCacheForMemoryTrim()

        assertEquals("a trim must give every document back", 0, MapStyleFactory.cachedStyleCount())
        assertEquals("a trim is not a cache reset", 1L, MapStyleFactory.styleCacheHits())
        assertEquals("a trim is not a cache reset", 2L, MapStyleFactory.styleCacheMisses())

        // The next request rebuilds the same document: an equal string, not the evicted instance,
        // so nothing stale can be served after a trim.
        val rebuilt = MapStyleFactory.buildJson(light)
        assertEquals(first, rebuilt)
        assertNotSame(first, rebuilt)
        assertEquals(1, MapStyleFactory.cachedStyleCount())
        assertEquals(3L, MapStyleFactory.styleCacheMisses())
    }

    @Test
    fun `releasing an empty cache is a no-op`() {
        assertEquals(0, MapStyleFactory.cachedStyleCount())
        MapStyleFactory.releaseCacheForMemoryTrim()
        assertEquals(0, MapStyleFactory.cachedStyleCount())
        assertEquals(0L, MapStyleFactory.styleCacheHits())
        assertEquals(0L, MapStyleFactory.styleCacheMisses())
    }
}
