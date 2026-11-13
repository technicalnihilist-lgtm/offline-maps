package com.offlinemaps.core.offline

import com.offlinemaps.core.common.GeoBounds
import com.offlinemaps.core.common.MapPackageType
import com.offlinemaps.core.common.TileMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Edge cases of the catalogue parser that [RegionCatalogValidationTest] does not reach: decoding
 * defaults, the exact boundaries of every range check, the non-finite values that used to reach
 * `GeoBounds.init` and throw, and the package mapping.
 *
 * The catalogue is refreshed from a user-configurable URL, so these are the inputs a hostile or
 * simply broken remote file can contain.
 */
class RegionCatalogParserEdgeCaseTest {

    private val good = """{"id":"andorra","name":"Andorra","country":"Andorra",
        "south":42.42,"west":1.41,"north":42.66,"east":1.79,"min_zoom":6,"max_zoom":14}"""

    private fun parse(vararg entries: String) = RegionCatalogParser.parse(
        """{"version":1,"regions":[${entries.joinToString(",")}]}""",
    )

    private fun ids(vararg entries: String) = RegionCatalogParser.toDomain(parse(*entries)).map { it.id }

    // --- decoding -------------------------------------------------------------------------

    @Test
    fun `unknown keys in the file and in an entry are ignored`() {
        val file = RegionCatalogParser.parse(
            """{"version":1,"future_field":{"nested":true},"regions":[
                 {"id":"andorra","name":"Andorra","south":42.42,"west":1.41,"north":42.66,
                  "east":1.79,"colour":"blue","packages":[]}]}""",
        )
        assertEquals(listOf("andorra"), RegionCatalogParser.toDomain(file).map { it.id })
    }

    @Test
    fun `an empty file decodes to the documented defaults instead of failing`() {
        val file = RegionCatalogParser.parse("{}")
        assertEquals(1, file.version)
        assertEquals("", file.updated)
        assertTrue(file.regions.isEmpty())
        assertTrue(RegionCatalogParser.toDomain(file).isEmpty())
        assertTrue(RegionCatalogParser.rejected(file).isEmpty())
    }

    @Test
    fun `an entry that carries only a name gets the entry defaults`() {
        // Every field except id/name/bounds is defaulted so that one truncated entry cannot abort
        // the decode of the whole file.
        val entry = RegionCatalogParser.parse("""{"regions":[{"name":"Nameless"}]}""").regions.single()
        assertEquals("", entry.id)
        assertEquals(4, entry.minZoom)
        assertEquals(14, entry.maxZoom)
        assertEquals("ODbL-1.0", entry.license)
        assertEquals("© OpenStreetMap contributors", entry.attribution)
        assertTrue(entry.packages.isEmpty())
        assertTrue(entry.south.isNaN())
    }

    @Test
    fun `a truncated entry costs only itself`() {
        val file = parse(good, """{"name":"Nameless"}""")
        assertEquals(listOf("andorra"), RegionCatalogParser.toDomain(file).map { it.id })
        // An entry with no id is reported under a placeholder so the refresh diff can count it.
        assertEquals(listOf("<no id>"), RegionCatalogParser.rejected(file))
    }

    // --- non-finite bounds ----------------------------------------------------------------

    @Test
    fun `non-finite bounds are rejected before GeoBounds can see them`() {
        val nan = RegionEntry(id = "nan", name = "NaN", south = Double.NaN, west = 1.41, north = 42.66, east = 1.79)
        val infinite = RegionEntry(id = "inf", name = "Inf", south = 42.42, west = 1.41, north = Double.POSITIVE_INFINITY, east = 1.79)
        val negativeInf = RegionEntry(id = "-inf", name = "-Inf", south = Double.NEGATIVE_INFINITY, west = 1.41, north = 42.66, east = 1.79)

        for (entry in listOf(nan, infinite, negativeInf)) {
            assertNotNull(entry.id, RegionCatalogParser.rejectReason(entry))
        }
        // Dropping them is what keeps toDomain from throwing out of GeoBounds.init.
        assertTrue(RegionCatalogParser.toDomain(RegionCatalogFile(regions = listOf(nan, infinite, negativeInf))).isEmpty())
        assertEquals(listOf("nan", "inf", "-inf"), RegionCatalogParser.rejected(RegionCatalogFile(regions = listOf(nan, infinite, negativeInf))))
    }

    // --- boundaries of the range checks ---------------------------------------------------

    @Test
    fun `bounds exactly on the mercator limit and the antimeridian are accepted`() {
        val regions = RegionCatalogParser.toDomain(
            parse(
                """{"id":"world","name":"World","south":-85.05112878,"west":-180.0,
                     "north":85.05112878,"east":180.0,"min_zoom":0,"max_zoom":3}""",
            ),
        )
        val region = regions.single()
        assertEquals(-180.0, region.bounds.west, 0.0)
        assertEquals(180.0, region.bounds.east, 0.0)
        assertEquals(TileMath.tileCount(GeoBounds(-85.05112878, -180.0, 85.05112878, 180.0), 0, 3), region.estimatedTileCount)
        assertTrue(region.estimatedTileCount > 0)
    }

    @Test
    fun `a hair outside the mercator limit is rejected`() {
        assertTrue(
            ids("""{"id":"over","name":"Over","south":42.42,"west":1.41,"north":85.05112879,"east":1.79}""").isEmpty(),
        )
        assertTrue(
            ids("""{"id":"under","name":"Under","south":-85.05112879,"west":1.41,"north":42.66,"east":1.79}""").isEmpty(),
        )
    }

    @Test
    fun `a hair outside the longitude range is rejected on either side`() {
        assertTrue(
            ids("""{"id":"w","name":"W","south":42.42,"west":-180.0001,"north":42.66,"east":1.79}""").isEmpty(),
        )
        assertTrue(
            ids("""{"id":"e","name":"E","south":42.42,"west":1.41,"north":42.66,"east":180.0001}""").isEmpty(),
        )
    }

    @Test
    fun `antimeridian-crossing bounds are dropped as inverted rather than throwing`() {
        val crossing = RegionEntry(id = "fiji", name = "Fiji", south = -19.0, west = 176.0, north = -16.0, east = -178.0)
        val reason = RegionCatalogParser.rejectReason(crossing)
        assertNotNull(reason)
        assertTrue(reason!!.contains("longitude"))
        assertTrue(RegionCatalogParser.toDomain(RegionCatalogFile(regions = listOf(crossing))).isEmpty())
    }

    @Test
    fun `the zoom limits are inclusive at 0 and MAX_ZOOM`() {
        assertNull(
            RegionCatalogParser.rejectReason(
                RegionEntry(
                    id = "z", name = "Z", south = 42.42, west = 1.41, north = 42.66, east = 1.79,
                    minZoom = 0, maxZoom = TileMath.MAX_ZOOM,
                ),
            ),
        )
        assertNotNull(
            RegionCatalogParser.rejectReason(
                RegionEntry(
                    id = "z", name = "Z", south = 42.42, west = 1.41, north = 42.66, east = 1.79,
                    minZoom = 0, maxZoom = TileMath.MAX_ZOOM + 1,
                ),
            ),
        )
        assertNotNull(
            RegionCatalogParser.rejectReason(
                RegionEntry(
                    id = "z", name = "Z", south = 42.42, west = 1.41, north = 42.66, east = 1.79,
                    minZoom = -1, maxZoom = 14,
                ),
            ),
        )
    }

    @Test
    fun `a whitespace-only id or name counts as missing`() {
        assertTrue(ids("""{"id":"   ","name":"Blank","south":42.42,"west":1.41,"north":42.66,"east":1.79}""").isEmpty())
        assertTrue(ids("""{"id":"blank","name":"\t","south":42.42,"west":1.41,"north":42.66,"east":1.79}""").isEmpty())
    }

    // --- packages and ordering ------------------------------------------------------------

    @Test
    fun `package type matching ignores case and an unknown type falls back`() {
        val region = RegionCatalogParser.toDomain(
            parse(
                """{"id":"andorra","name":"Andorra","south":42.42,"west":1.41,"north":42.66,"east":1.79,
                     "packages":[
                       {"type":"routing_osm","url":"https://example.invalid/a.pbf"},
                       {"type":"Places_Pack","url":"https://example.invalid/p.jsonl.gz"},
                       {"type":"not-a-type","url":"https://example.invalid/c.bin"}]}""",
            ),
        ).single()

        assertEquals(
            listOf(MapPackageType.ROUTING_OSM, MapPackageType.PLACES_PACK, MapPackageType.ROUTING_OSM),
            region.packages.map { it.type },
        )
        // Package defaults survive the mapping unchanged.
        assertTrue(region.packages.all { it.sizeBytes == 0L && it.version == "1" && it.license == "ODbL-1.0" })
        assertTrue(region.packages.all { it.sha256 == null && it.md5 == null })
    }

    @Test
    fun `package hashes, size and version are carried through as written`() {
        val region = RegionCatalogParser.toDomain(
            parse(
                """{"id":"andorra","name":"Andorra","south":42.42,"west":1.41,"north":42.66,"east":1.79,
                     "packages":[{"type":"MBTILES","url":"https://example.invalid/a.mbtiles",
                       "size_bytes":4096,"sha256":"ab","md5":"cd","version":"7"}]}""",
            ),
        ).single()

        val pkg = region.packages.single()
        assertEquals(MapPackageType.MBTILES, pkg.type)
        assertEquals(4096L, pkg.sizeBytes)
        assertEquals("ab", pkg.sha256)
        assertEquals("cd", pkg.md5)
        assertEquals("7", pkg.version)
    }

    @Test
    fun `catalogue order is preserved and rejected stays in step with toDomain`() {
        val file = parse(
            """{"id":"third","name":"Third","south":42.42,"west":1.41,"north":42.66,"east":1.79}""",
            """{"id":"bad","name":"Bad","south":42.66,"west":1.41,"north":42.42,"east":1.79}""",
            good,
            """{"id":"first","name":"First","south":1.0,"west":1.41,"north":2.0,"east":1.79}""",
        )
        assertEquals(listOf("third", "andorra", "first"), RegionCatalogParser.toDomain(file).map { it.id })
        assertEquals(listOf("bad"), RegionCatalogParser.rejected(file))
    }

    @Test
    fun `a repeated id is kept as written`() {
        // The parser does not de-duplicate: it documents the file. Anything that keys by id must
        // cope with the repeat itself.
        assertEquals(listOf("andorra", "andorra"), ids(good, good))
    }
}
