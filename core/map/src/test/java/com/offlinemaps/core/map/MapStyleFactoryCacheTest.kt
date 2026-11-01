package com.offlinemaps.core.map

import com.offlinemaps.core.common.MapTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The style document is generated in code, and serialising it is the expensive part of opening a
 * map. Callers ask for it on the main thread (first attach, an options change, registering an
 * offline region), so repeated requests for the same options must be a map lookup, not a rebuild.
 *
 * These tests pin down three things: the cache actually returns the same instance for the same
 * options, the key covers every input that changes the document (so a stale document can never be
 * served), and the cache stays bounded.
 */
class MapStyleFactoryCacheTest {

    @Before
    @After
    fun resetCache() = MapStyleFactory.clearStyleCache()

    @Test
    fun `repeated requests for the same options reuse one document`() {
        val options = StyleOptions(theme = MapTheme.DARK, showTerrain = true, show3DBuildings = true)

        val first = MapStyleFactory.buildJson(options)
        val second = MapStyleFactory.buildJson(options.copy())

        assertSame("same options must not rebuild the document", first, second)
        assertEquals(1L, MapStyleFactory.styleCacheMisses())
        assertEquals(1L, MapStyleFactory.styleCacheHits())
        assertEquals(1, MapStyleFactory.cachedStyleCount())
    }

    @Test
    fun `the document returned from the cache is byte-identical to a fresh build`() {
        val options = StyleOptions(theme = MapTheme.LIGHT)
        val cached = MapStyleFactory.buildJson(options)

        // Evict, rebuild from scratch, and compare: caching must not change the document at all.
        MapStyleFactory.clearStyleCache()
        val rebuilt = MapStyleFactory.buildJson(options)

        assertFalse("the rebuild must be a different instance", cached === rebuilt)
        assertEquals(cached, rebuilt)
        assertEquals(cached.length, rebuilt.length)
    }

    @Test
    fun `the key covers every option that changes the document`() {
        val base = StyleOptions(theme = MapTheme.LIGHT, showTerrain = true, show3DBuildings = true)
        val variants = listOf(
            base,
            base.copy(theme = MapTheme.DARK),
            base.copy(showTerrain = false),
            base.copy(show3DBuildings = false),
            base.copy(buildingsMinZoom = 15),
            base.copy(maxTerrainZoom = 10),
            base.copy(config = MapConfig.DEFAULT.copy(vectorTileUrlTemplate = "https://tiles.example/{z}/{x}/{y}.pbf")),
            base.copy(config = MapConfig.DEFAULT.copy(maxZoom = 12)),
        )

        val documents = variants.map { MapStyleFactory.buildJson(it) }

        assertEquals("every variant must be a cache miss", variants.size.toLong(), MapStyleFactory.styleCacheMisses())
        assertEquals(variants.size, documents.toSet().size)
        assertEquals(variants.size, MapStyleFactory.cachedStyleCount())
    }

    @Test
    fun `each theme and toggle combination is generated exactly once`() {
        val options = buildList {
            for (theme in MapTheme.entries) {
                for (terrain in listOf(true, false)) {
                    for (buildings in listOf(true, false)) {
                        add(StyleOptions(theme = theme, showTerrain = terrain, show3DBuildings = buildings))
                    }
                }
            }
        }
        repeat(3) { round -> options.forEach { MapStyleFactory.buildJson(it) } }

        assertEquals(options.size.toLong(), MapStyleFactory.styleCacheMisses())
        assertEquals(options.size * 2L, MapStyleFactory.styleCacheHits())
        assertEquals(options.size, MapStyleFactory.cachedStyleCount())
    }

    @Test
    fun `the cache is bounded and evicts the least recently used document`() {
        val options = (0 until MapStyleFactory.MAX_CACHED_STYLES + 4).map { index ->
            StyleOptions(config = MapConfig.DEFAULT.copy(attribution = "source-$index"))
        }

        options.forEach { MapStyleFactory.buildJson(it) }

        assertEquals(MapStyleFactory.MAX_CACHED_STYLES, MapStyleFactory.cachedStyleCount())

        // An evicted document is rebuilt correctly rather than served stale.
        val rebuilt = MapStyleFactory.buildJson(options.first())
        assertTrue(rebuilt.contains("source-0"))
        assertEquals(MapStyleFactory.MAX_CACHED_STYLES, MapStyleFactory.cachedStyleCount())
    }

    @Test
    fun `concurrent callers all receive the correct document`() {
        val options = StyleOptions(theme = MapTheme.DARK, showTerrain = false)
        val expected = MapStyleFactory.buildJson(options)
        MapStyleFactory.clearStyleCache()

        val pool = Executors.newFixedThreadPool(8)
        try {
            val results = pool.invokeAll((0 until 64).map { Callable<String> { MapStyleFactory.buildJson(options) } })
                .map { it.get(30, TimeUnit.SECONDS) }
            assertEquals(64, results.size)
            results.forEach { assertEquals(expected, it) }
        } finally {
            pool.shutdownNow()
        }

        assertEquals(1, MapStyleFactory.cachedStyleCount())
        assertEquals(1L, MapStyleFactory.styleCacheMisses())
    }
}
