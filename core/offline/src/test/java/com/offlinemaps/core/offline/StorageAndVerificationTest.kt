package com.offlinemaps.core.offline

import com.offlinemaps.core.common.DownloadStatus
import com.offlinemaps.core.common.GeoBounds
import com.offlinemaps.core.common.MapPackageType
import com.offlinemaps.core.common.TileMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.security.MessageDigest

class StorageAndVerificationTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `storage guard keeps a safety margin`() {
        val mb = 1024L * 1024
        // 300 MB free with a 10 MB download fits; 100 MB free does not, because 200 MB is reserved.
        assertTrue(StorageGuard.canFit(availableBytes = 300 * mb, requiredBytes = 10 * mb))
        assertFalse(StorageGuard.canFit(availableBytes = 100 * mb, requiredBytes = 10 * mb))
    }

    @Test
    fun `storage guard enforces the user limit`() {
        val limit = 1024L * 1024 * 1024
        assertFalse(
            StorageGuard.canFit(
                availableBytes = 10L * 1024 * 1024 * 1024,
                requiredBytes = 200L * 1024 * 1024,
                limitBytes = limit,
                usedByMapsBytes = limit - 100,
            ),
        )
    }

    @Test
    fun `storage guard explains the failure`() {
        val reason = StorageGuard.reasonFor(availableBytes = 1024, requiredBytes = 100 * 1024 * 1024)
        assertNotNull(reason)
        assertTrue(reason!!.contains("free space"))
    }

    @Test
    fun `file verifier accepts a matching sha256`() {
        val file = temp.newFile("ok.bin")
        val bytes = "payload".toByteArray()
        file.writeBytes(bytes)
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        assertNull(FileVerifier.verify(file, digest, null))
    }

    @Test
    fun `file verifier rejects a mismatched md5`() {
        val file = temp.newFile("bad.bin")
        file.writeBytes("payload".toByteArray())
        assertNotNull(FileVerifier.verify(file, null, "deadbeef"))
    }

    @Test
    fun `file verifier skips verification when no checksum is supplied`() {
        val file = temp.newFile("any.bin")
        file.writeBytes("payload".toByteArray())
        assertNull(FileVerifier.verify(file, null, null))
    }

    @Test
    fun `tile region estimate grows with zoom depth`() {
        val bounds = GeoBounds(42.42, 1.41, 42.66, 1.79)
        val shallow = TileRegionEstimator.estimateBytes(bounds, 6, 12, includeTerrain = false)
        val deep = TileRegionEstimator.estimateBytes(bounds, 6, 16, includeTerrain = false)
        assertTrue("deeper zoom must cost more", deep > shallow)
        assertTrue(shallow > 0)
    }

    @Test
    fun `zoom descriptions stay honest about detail`() {
        assertTrue(TileRegionEstimator.describeZoom(9).contains("outlines"))
        assertTrue(TileRegionEstimator.describeZoom(14).contains("Street network"))
        assertTrue(TileRegionEstimator.describeZoom(18).contains("Maximum detail"))
    }

    @Test
    fun `catalog parser reads packages and computes tile counts`() {
        val json = """
            {"version":1,"regions":[
              {"id":"andorra","name":"Andorra","country":"Andorra",
               "south":42.42,"west":1.41,"north":42.66,"east":1.79,
               "min_zoom":6,"max_zoom":14,
               "packages":[{"type":"ROUTING_OSM","url":"https://example.invalid/andorra.osm.pbf","size_bytes":0}]}
            ]}
        """.trimIndent()
        val parsed = RegionCatalogParser.toDomain(RegionCatalogParser.parse(json))
        assertEquals(1, parsed.size)
        val region = parsed.first()
        assertEquals("Andorra", region.name)
        assertEquals(MapPackageType.ROUTING_OSM, region.packages.first().type)
        assertEquals(TileMath.tileCount(region.bounds, 6, 14), region.estimatedTileCount)
    }

    @Test
    fun `in memory store tracks status transitions`() = kotlinx.coroutines.runBlocking {
        val store = InMemoryDownloadStore()
        store.save(
            DownloadEntry(
                packageKey = "k", regionId = "r", name = "n", type = MapPackageType.ROUTING_OSM,
                url = "u", status = DownloadStatus.QUEUED, bytesTotal = 10, bytesDownloaded = 0,
                localPath = null, sha256 = null, md5 = null, errorMessage = null,
            ),
        )
        store.update("k", DownloadStatus.RUNNING, 5, null)
        assertEquals(5L, store.find("k")?.bytesDownloaded)
        assertEquals(1, store.pending().size)
        store.delete("k")
        assertNull(store.find("k"))
    }
}
