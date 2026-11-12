package com.offlinemaps.core.offline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A catalogue can be refreshed from a user-configurable URL, so a single malformed entry must be
 * dropped on its own instead of taking the whole list (or the whole app screen) down with it.
 */
class RegionCatalogValidationTest {

    private val andorra = """
        {"id":"andorra","name":"Andorra","country":"Andorra",
         "south":42.42,"west":1.41,"north":42.66,"east":1.79,"min_zoom":6,"max_zoom":14}
    """.trimIndent()

    private fun parseEntries(vararg entries: String) = RegionCatalogParser.toDomain(
        RegionCatalogParser.parse("""{"version":1,"regions":[${entries.joinToString(",")}]}"""),
    )

    @Test
    fun `a sound entry is kept`() {
        val regions = parseEntries(andorra)
        assertEquals(1, regions.size)
        assertEquals("andorra", regions.single().id)
    }

    @Test
    fun `entry with no bounds is dropped and the rest survive`() {
        val regions = parseEntries(
            andorra,
            """{"id":"broken","name":"Broken","country":"Nowhere","min_zoom":6,"max_zoom":14}""",
        )
        assertEquals(listOf("andorra"), regions.map { it.id })
    }

    @Test
    fun `inverted latitude bounds are dropped instead of throwing`() {
        val regions = parseEntries(
            """{"id":"flipped","name":"Flipped","country":"X","south":42.66,"west":1.41,"north":42.42,"east":1.79}""",
            andorra,
        )
        assertEquals(listOf("andorra"), regions.map { it.id })
    }

    @Test
    fun `inverted longitude bounds are dropped`() {
        val regions = parseEntries(
            """{"id":"flipped","name":"Flipped","country":"X","south":42.42,"west":1.79,"north":42.66,"east":1.41}""",
        )
        assertTrue(regions.isEmpty())
    }

    @Test
    fun `empty zoom range is dropped rather than estimating zero tiles`() {
        val regions = parseEntries(
            """{"id":"backwards","name":"Backwards","country":"X","south":42.42,"west":1.41,"north":42.66,"east":1.79,"min_zoom":14,"max_zoom":6}""",
        )
        assertTrue(regions.isEmpty())
    }

    @Test
    fun `a single zoom level is still a usable region`() {
        val regions = parseEntries(
            """{"id":"one","name":"One","country":"X","south":42.42,"west":1.41,"north":42.66,"east":1.79,"min_zoom":9,"max_zoom":9}""",
        )
        assertEquals(1, regions.size)
        assertTrue(regions.single().estimatedTileCount > 0)
    }

    @Test
    fun `zoom outside the supported range is dropped`() {
        assertTrue(
            parseEntries(
                """{"id":"deep","name":"Deep","country":"X","south":42.42,"west":1.41,"north":42.66,"east":1.79,"min_zoom":6,"max_zoom":30}""",
            ).isEmpty(),
        )
        assertTrue(
            parseEntries(
                """{"id":"negative","name":"Negative","country":"X","south":42.42,"west":1.41,"north":42.66,"east":1.79,"min_zoom":-1,"max_zoom":14}""",
            ).isEmpty(),
        )
    }

    @Test
    fun `bounds outside the world are dropped`() {
        assertTrue(
            parseEntries(
                """{"id":"polar","name":"Polar","country":"X","south":80.0,"west":1.41,"north":89.0,"east":1.79}""",
            ).isEmpty(),
        )
        assertTrue(
            parseEntries(
                """{"id":"wide","name":"Wide","country":"X","south":42.42,"west":-190.0,"north":42.66,"east":190.0}""",
            ).isEmpty(),
        )
    }

    @Test
    fun `entry with no id or name is dropped`() {
        assertTrue(
            parseEntries(
                """{"name":"Nameless","country":"X","south":42.42,"west":1.41,"north":42.66,"east":1.79}""",
            ).isEmpty(),
        )
        assertTrue(
            parseEntries(
                """{"id":"anon","country":"X","south":42.42,"west":1.41,"north":42.66,"east":1.79}""",
            ).isEmpty(),
        )
    }

    @Test
    fun `packages without a url are dropped but the region is kept`() {
        val regions = parseEntries(
            """
            {"id":"andorra","name":"Andorra","country":"Andorra",
             "south":42.42,"west":1.41,"north":42.66,"east":1.79,"min_zoom":6,"max_zoom":14,
             "packages":[
               {"type":"ROUTING_OSM"},
               {"type":"ROUTING_OSM","url":"https://example.invalid/a.osm.pbf","size_bytes":12}
             ]}
            """.trimIndent(),
        )
        assertEquals(1, regions.size)
        assertEquals(1, regions.single().packages.size)
        assertEquals("https://example.invalid/a.osm.pbf", regions.single().packages.single().url)
    }

    @Test
    fun `rejectReason names the problem and stays quiet on a sound entry`() {
        val good = RegionCatalogParser.parse("""{"regions":[$andorra]}""").regions.single()
        assertNull(RegionCatalogParser.rejectReason(good))

        val flipped = RegionCatalogParser.parse(
            """{"regions":[{"id":"f","name":"F","south":42.66,"west":1.41,"north":42.42,"east":1.79}]}""",
        ).regions.single()
        assertNotNull(RegionCatalogParser.rejectReason(flipped))
        assertTrue(RegionCatalogParser.rejectReason(flipped)!!.contains("latitude"))
    }

    @Test
    fun `rejected lists the ids that were skipped`() {
        val file = RegionCatalogParser.parse(
            """{"regions":[$andorra,{"id":"backwards","name":"B","south":42.42,"west":1.41,"north":42.66,"east":1.79,"min_zoom":14,"max_zoom":6}]}""",
        )
        assertEquals(listOf("backwards"), RegionCatalogParser.rejected(file))
        assertEquals(1, RegionCatalogParser.toDomain(file).size)
    }
}
