package com.offlinemaps.core.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.offlinemaps.core.common.DownloadStatus
import com.offlinemaps.core.common.GeoBounds
import com.offlinemaps.core.common.GeoPoint
import com.offlinemaps.core.common.MapPackageType
import com.offlinemaps.core.common.SavedPlaceLabel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class RepositoryTest {

    private lateinit var db: AppDatabase
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = AppDatabase.inMemory(context)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `saved places round trip`() = runTest {
        val repository = SavedPlacesRepository(db.savedPlaceDao())
        repository.save(SavedPlace(name = "Andorra la Vella", label = SavedPlaceLabel.HOME, point = GeoPoint(42.5069, 1.5212)))
        val places = repository.observe().first()
        assertEquals(1, places.size)
        assertEquals("Andorra la Vella", places.first().name)
        assertEquals(SavedPlaceLabel.HOME, places.first().label)
        assertEquals(42.5069, places.first().point.latitude, 1e-6)
    }

    @Test
    fun `home work and school are singletons`() = runTest {
        val repository = SavedPlacesRepository(db.savedPlaceDao())
        repository.save(SavedPlace(name = "Old home", label = SavedPlaceLabel.HOME, point = GeoPoint(1.0, 1.0)))
        repository.save(SavedPlace(name = "New home", label = SavedPlaceLabel.HOME, point = GeoPoint(2.0, 2.0)))
        repository.save(SavedPlace(name = "Work", label = SavedPlaceLabel.WORK, point = GeoPoint(3.0, 3.0)))
        assertEquals(2, repository.observe().first().size)
        assertEquals("New home", repository.findLabel(SavedPlaceLabel.HOME)?.name)
    }

    @Test
    fun `custom places are not deduplicated`() = runTest {
        val repository = SavedPlacesRepository(db.savedPlaceDao())
        repository.save(SavedPlace(name = "Cafe", label = SavedPlaceLabel.CUSTOM, point = GeoPoint(1.0, 1.0)))
        repository.save(SavedPlace(name = "Cafe", label = SavedPlaceLabel.CUSTOM, point = GeoPoint(1.0, 1.0)))
        assertEquals(2, repository.observe().first().size)
    }

    @Test
    fun `deleting a saved place removes it`() = runTest {
        val repository = SavedPlacesRepository(db.savedPlaceDao())
        repository.save(SavedPlace(name = "Cafe", label = SavedPlaceLabel.CUSTOM, point = GeoPoint(1.0, 1.0)))
        repository.delete(repository.observe().first().first())
        assertTrue(repository.observe().first().isEmpty())
    }

    @Test
    fun `recent searches are deduplicated by query and ordered by recency`() = runTest {
        val repository = RecentSearchRepository(db.recentSearchDao())
        repository.record("ordino", "Ordino", GeoPoint(42.55, 1.53))
        repository.record("andorra", "Andorra la Vella", GeoPoint(42.50, 1.52))
        repository.record("ordino", "Ordino", GeoPoint(42.55, 1.53))
        val recent = repository.observe().first()
        assertEquals(2, recent.size)
        assertEquals("ordino", recent.first().query)
    }

    @Test
    fun `blank searches are not recorded`() = runTest {
        val repository = RecentSearchRepository(db.recentSearchDao())
        repository.record("  ", "x", GeoPoint(1.0, 1.0))
        assertTrue(repository.observe().first().isEmpty())
    }

    @Test
    fun `download records persist progress and status`() = runTest {
        val repository = DownloadRepository(db.downloadDao())
        repository.upsert(
            DownloadRecord(
                packageKey = "andorra-ROUTING_OSM", regionId = "andorra", name = "Andorra routing",
                type = MapPackageType.ROUTING_OSM, url = "https://example.invalid/a.pbf",
                status = DownloadStatus.QUEUED, bytesTotal = 1000, bytesDownloaded = 0,
                localPath = null, sha256 = null, md5 = null, errorMessage = null, updatedAt = 0,
            ),
        )
        repository.updateProgress("andorra-ROUTING_OSM", DownloadStatus.RUNNING, 400)
        val record = repository.find("andorra-ROUTING_OSM")
        assertNotNull(record)
        assertEquals(DownloadStatus.RUNNING, record!!.status)
        assertEquals(400L, record.bytesDownloaded)
        assertEquals(0.4f, record.fraction, 0.01f)
        assertEquals(1, repository.pending().size)
    }

    @Test
    fun `installed regions remember routing and search readiness`() = runTest {
        val repository = InstalledRegionRepository(db.installedRegionDao())
        repository.save(
            InstalledRegion(
                regionId = "andorra", name = "Andorra", type = MapPackageType.ROUTING_OSM,
                sizeBytes = 500_000, version = "1", installedAt = 1,
                bounds = GeoBounds(42.42, 1.41, 42.66, 1.79), minZoom = 6, maxZoom = 16,
            ),
        )
        repository.setRoutingReady("andorra", true)
        val region = repository.find("andorra")
        assertNotNull(region)
        assertTrue(region!!.routingReady)
        assertTrue(region.covers(GeoPoint(42.5, 1.5)))
        assertTrue(!region.covers(GeoPoint(48.85, 2.35)))
    }

    @Test
    fun `settings store keeps values across instances`() {
        val store = SettingsStore(context)
        store.update { it.copy(wifiOnlyDownloads = false, metricUnits = false, tileCacheMb = 512) }
        val reloaded = SettingsStore(context)
        assertEquals(false, reloaded.current().wifiOnlyDownloads)
        assertEquals(false, reloaded.current().metricUnits)
        assertEquals(512L, reloaded.current().tileCacheMb)
    }

    @Test
    fun `clearing settings restores defaults`() {
        val store = SettingsStore(context)
        store.update { it.copy(wifiOnlyDownloads = false) }
        store.clearAll()
        assertEquals(true, store.current().wifiOnlyDownloads)
    }
}
