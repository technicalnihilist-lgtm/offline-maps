package com.offlinemaps.core.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.offlinemaps.core.common.GeoPoint
import com.offlinemaps.core.common.SavedPlaceLabel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * H-03: the recent-search cache is bounded by the list the panel can show, and saved places - the
 * user's own data, rendered through a windowed `LazyColumn` - are never truncated.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class RecentSearchCapTest {

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
    fun `stored rows are capped at the visible limit`() = runTest {
        val repository = RecentSearchRepository(db.recentSearchDao())
        val extra = 5
        repeat(RecentSearchLimits.VISIBLE + extra) { i ->
            repository.record("query $i", "Place $i", GeoPoint(42.5, 1.5))
        }
        // Read past every limit so the assertion is on what is actually stored, not on the query.
        val stored = db.recentSearchDao().observeRecent(Int.MAX_VALUE).first()
        assertEquals(RecentSearchLimits.VISIBLE, stored.size)
        assertTrue(stored.any { it.query == "query ${RecentSearchLimits.VISIBLE + extra - 1}" })
        assertFalse(stored.any { it.query == "query 0" })
    }

    @Test
    fun `what the panel observes is unchanged by the cap`() = runTest {
        val repository = RecentSearchRepository(db.recentSearchDao())
        repeat(RecentSearchLimits.VISIBLE) { i ->
            repository.record("query $i", "Place $i", GeoPoint(42.5, 1.5))
        }
        val shown = repository.observe().first()
        assertEquals(RecentSearchLimits.VISIBLE, shown.size)
        // Timestamps can tie inside one millisecond, so assert on membership rather than order.
        assertTrue(shown.any { it.query == "query 0" })
        assertTrue(shown.any { it.query == "query ${RecentSearchLimits.VISIBLE - 1}" })
    }

    @Test
    fun `pruning breaks ties on id so the newest row survives`() = runTest {
        val dao = db.recentSearchDao()
        val total = RecentSearchLimits.VISIBLE + 3
        // Identical timestamps: the order must not depend on insertion order alone.
        repeat(total) { i ->
            dao.insert(
                RecentSearchEntity(
                    query = "tie $i",
                    name = "Place $i",
                    latitude = 1.0,
                    longitude = 2.0,
                    usedAt = 1_000L,
                ),
            )
        }
        dao.pruneTo(RecentSearchLimits.VISIBLE)
        val stored = dao.observeRecent(Int.MAX_VALUE).first()
        assertEquals(RecentSearchLimits.VISIBLE, stored.size)
        assertTrue(stored.any { it.query == "tie ${total - 1}" })
        assertFalse(stored.any { it.query == "tie 0" })
    }

    @Test
    fun `saved places are not truncated`() = runTest {
        val repository = SavedPlacesRepository(db.savedPlaceDao())
        val total = RecentSearchLimits.VISIBLE + 10
        repeat(total) { i ->
            repository.save(
                SavedPlace(name = "place $i", label = SavedPlaceLabel.CUSTOM, point = GeoPoint(1.0, 1.0)),
            )
        }
        assertEquals(total, repository.observe().first().size)
    }
}
