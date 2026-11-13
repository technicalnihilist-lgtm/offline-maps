package com.offlinemaps.app.ui

import com.google.common.truth.Truth.assertThat
import com.offlinemaps.core.common.DownloadStatus
import com.offlinemaps.core.common.GeoBounds
import com.offlinemaps.core.common.MapPackageType
import com.offlinemaps.core.data.DownloadRecord
import com.offlinemaps.core.data.InstalledRegion
import com.offlinemaps.core.offline.DownloadProgress
import com.offlinemaps.core.offline.DownloadRuntime
import org.junit.Test

/**
 * Pins the pure half of the downloads screen (OPTIMIZATION.md J-01): the maps the ViewModel hands
 * to [DownloadsUiState], the key a tile region is filed under, the install decision for a finished
 * record and the package file extension.
 *
 * These are plain JVM tests on purpose - the ViewModel itself needs a Context, a Room database and
 * a MapLibre offline manager, so the parts that can be checked without an emulator are kept in
 * `DownloadsStateMapping.kt` and checked here.
 */
class DownloadsStateMappingTest {

    // --- mapNotNullValues -------------------------------------------------------------------

    @Test
    fun `mapNotNullValues drops the null entries and keeps the rest in order`() {
        val source = linkedMapOf("a" to 1, "b" to 2, "c" to 3)

        val mapped = source.mapNotNullValues { if (it.value == 2) null else it.value * 10 }

        assertThat(mapped).containsExactly("a", 10, "c", 30)
        assertThat(mapped.keys).containsExactly("a", "c").inOrder()
    }

    @Test
    fun `mapNotNullValues keeps a mapped value of zero, which is not null`() {
        val mapped = mapOf("a" to 0, "b" to 1).mapNotNullValues { if (it.value == 0) 0 else null }

        assertThat(mapped).containsExactly("a", 0)
    }

    @Test
    fun `mapNotNullValues on an empty map is empty`() {
        assertThat(emptyMap<String, Int>().mapNotNullValues { it.value }).isEmpty()
    }

    @Test
    fun `mapNotNullValues can key off the entry key`() {
        val mapped = mapOf("pkg" to "err").mapNotNullValues { "${it.key}!" to it.value }

        assertThat(mapped).containsExactly("pkg!", "err")
    }

    // --- the two state maps -----------------------------------------------------------------

    @Test
    fun `progressByPackage keeps only the packages that report progress`() {
        val states = mapOf(
            "andorra-tiles" to runtime("andorra-tiles", progress = progress("andorra-tiles", 10, 100)),
            "andorra-osm" to runtime("andorra-osm"),
            "france-tiles" to runtime("france-tiles", progress = progress("france-tiles", 0, 0)),
        )

        val progress = progressByPackage(states)

        assertThat(progress.keys).containsExactly("andorra-tiles", "france-tiles")
        assertThat(progress.getValue("andorra-tiles").bytesDownloaded).isEqualTo(10)
        assertThat(progress.getValue("andorra-tiles").bytesTotal).isEqualTo(100)
        assertThat(progress.getValue("france-tiles").fraction).isEqualTo(0f)
    }

    @Test
    fun `errorsByPackage keeps only the packages that failed`() {
        val states = mapOf(
            "andorra-tiles" to runtime("andorra-tiles", error = "Not enough space."),
            "andorra-osm" to runtime("andorra-osm", status = DownloadStatus.RUNNING),
        )

        val messages = errorsByPackage(states)

        assertThat(messages).containsExactly("andorra-tiles", "Not enough space.")
    }

    @Test
    fun `a package can carry both progress and an error, and both maps keep it`() {
        val states = mapOf(
            "andorra-tiles" to runtime(
                "andorra-tiles",
                status = DownloadStatus.FAILED,
                progress = progress("andorra-tiles", 40, 100),
                error = "Connection lost.",
            ),
        )

        assertThat(progressByPackage(states)).hasSize(1)
        assertThat(errorsByPackage(states)).containsExactly("andorra-tiles", "Connection lost.")
    }

    @Test
    fun `no running downloads map to two empty maps`() {
        val states = emptyMap<String, DownloadRuntime>()

        assertThat(progressByPackage(states)).isEmpty()
        assertThat(errorsByPackage(states)).isEmpty()
    }

    // --- package keys -----------------------------------------------------------------------

    @Test
    fun `a tile region is filed under its id and the tile type`() {
        assertThat(tileKeyFor("andorra")).isEqualTo("andorra-TILE_REGION")
    }

    @Test
    fun `region ids that already contain a dash keep their own key`() {
        assertThat(tileKeyFor("in-andorra")).isEqualTo("in-andorra-TILE_REGION")
        assertThat(tileKeyFor("in-andorra")).isNotEqualTo(tileKeyFor("andorra"))
    }

    // --- package extension ------------------------------------------------------------------

    @Test
    fun `each package type has its own extension and none carries a leading dot`() {
        val extensions = MapPackageType.entries.map { packageExtension(it) }

        assertThat(extensions).containsExactly("tiles", "mbtiles", "osm.pbf", "jsonl.gz")
        assertThat(extensions.toSet()).hasSize(MapPackageType.entries.size)
        assertThat(extensions.any { it.startsWith(".") }).isFalse()
    }

    @Test
    fun `the extensions stay what the download engine expects`() {
        // These names are on disk and in the resume path; changing one orphans every package a
        // user already has.
        assertThat(packageExtension(MapPackageType.ROUTING_OSM)).isEqualTo("osm.pbf")
        assertThat(packageExtension(MapPackageType.PLACES_PACK)).isEqualTo("jsonl.gz")
        assertThat(packageExtension(MapPackageType.MBTILES)).isEqualTo("mbtiles")
        assertThat(packageExtension(MapPackageType.TILE_REGION)).isEqualTo("tiles")
    }

    // --- install decision -------------------------------------------------------------------

    @Test
    fun `a routing download is already installed when the region has routing`() {
        val record = record(MapPackageType.ROUTING_OSM)
        val installed = listOf(installed("andorra", routingReady = true, searchReady = false))

        assertThat(isAlreadyInstalled(record, installed)).isTrue()
    }

    @Test
    fun `a routing download still installs when the region has only search`() {
        val record = record(MapPackageType.ROUTING_OSM)
        val installed = listOf(installed("andorra", routingReady = false, searchReady = true))

        assertThat(isAlreadyInstalled(record, installed)).isFalse()
    }

    @Test
    fun `a places download is already installed when the region has search`() {
        val record = record(MapPackageType.PLACES_PACK)
        val installed = listOf(installed("andorra", routingReady = false, searchReady = true))

        assertThat(isAlreadyInstalled(record, installed)).isTrue()
    }

    @Test
    fun `a places download still installs when the region has only routing`() {
        val record = record(MapPackageType.PLACES_PACK)
        val installed = listOf(installed("andorra", routingReady = true, searchReady = false))

        assertThat(isAlreadyInstalled(record, installed)).isFalse()
    }

    @Test
    fun `readiness on a different region does not count as installed`() {
        val record = record(MapPackageType.ROUTING_OSM)
        val installed = listOf(installed("france", routingReady = true, searchReady = true))

        assertThat(isAlreadyInstalled(record, installed)).isFalse()
    }

    @Test
    fun `nothing installed means nothing to skip`() {
        assertThat(isAlreadyInstalled(record(MapPackageType.ROUTING_OSM), emptyList())).isFalse()
        assertThat(isAlreadyInstalled(record(MapPackageType.PLACES_PACK), emptyList())).isFalse()
    }

    @Test
    fun `an mbtiles download is never treated as already installed`() {
        val record = record(MapPackageType.MBTILES)
        val installed = listOf(installed("andorra", routingReady = true, searchReady = true))

        assertThat(isAlreadyInstalled(record, installed)).isFalse()
    }

    // --- size label -------------------------------------------------------------------------

    @Test
    fun `a known size is formatted and an unknown one says so`() {
        assertThat(DownloadsViewModel.sizeLabel(0L)).isEqualTo("size from server")
        assertThat(DownloadsViewModel.sizeLabel(-1L)).isEqualTo("size from server")
        assertThat(DownloadsViewModel.sizeLabel(2L * 1024 * 1024)).isEqualTo("2.0 MB")
    }

    // --- fixtures ---------------------------------------------------------------------------

    private fun progress(key: String, downloaded: Long, total: Long) = DownloadProgress(
        packageKey = key,
        bytesDownloaded = downloaded,
        bytesTotal = total,
    )

    private fun runtime(
        key: String,
        status: DownloadStatus = DownloadStatus.QUEUED,
        progress: DownloadProgress? = null,
        error: String? = null,
    ) = DownloadRuntime(packageKey = key, status = status, progress = progress, error = error)

    private fun record(type: MapPackageType) = DownloadRecord(
        packageKey = "andorra-${type.name}",
        regionId = "andorra",
        name = "Andorra",
        type = type,
        url = "https://example.invalid/andorra",
        status = DownloadStatus.COMPLETED,
        bytesTotal = 1024,
        bytesDownloaded = 1024,
        localPath = "/tmp/andorra",
        sha256 = null,
        md5 = null,
        errorMessage = null,
        updatedAt = 0L,
    )

    private fun installed(regionId: String, routingReady: Boolean, searchReady: Boolean) = InstalledRegion(
        regionId = regionId,
        name = regionId,
        type = MapPackageType.TILE_REGION,
        sizeBytes = 1024,
        version = "1",
        installedAt = 0L,
        bounds = GeoBounds(south = 42.4, west = 1.4, north = 42.7, east = 1.8),
        minZoom = 0,
        maxZoom = 14,
        routingReady = routingReady,
        searchReady = searchReady,
    )
}
