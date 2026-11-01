package com.offlinemaps.core.search

import com.offlinemaps.core.common.GeoPoint
import com.offlinemaps.core.common.PlaceResult
import com.offlinemaps.core.common.SearchSource
import java.io.File
import java.util.Locale
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Search facade used by the UI.
 *
 * Order of resolution: a typed coordinate pair wins (the user was explicit), then the offline
 * index. When the index is empty we return an empty list plus [lastCoverageNote] so the UI can say
 * "no downloaded places here" instead of pretending nothing exists.
 *
 * Every call is a suspending function and runs on [io]: opening the SQLite file, reading it and
 * importing into it are all disk work, so none of it may happen on the caller's thread (which for
 * search is the main thread). That also means the index database is opened for the first time on
 * [io], never while a frame is being drawn — see OPTIMIZATION.md A-02.
 */
class SearchRepository(
    private val index: SearchIndex,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {

    @Volatile
    var lastCoverageNote: String? = null
        private set

    suspend fun indexSize(): Int = withContext(io) { sizeBlocking() }

    private fun sizeBlocking(): Int = runCatching { index.count() }.getOrDefault(0)

    suspend fun search(query: String, limit: Int = 25): List<PlaceResult> = withContext(io) {
        val trimmed = query.trim()
        if (trimmed.length < 2) return@withContext emptyList()

        val coordinate = parseCoordinate(trimmed)
        val results = runCatching { index.search(trimmed, limit) }.getOrDefault(emptyList())
        lastCoverageNote = when {
            sizeBlocking() == 0 ->
                "No offline search data is installed yet. Download a region's places pack to search without internet."
            results.isEmpty() ->
                "Nothing matched in your downloaded map data. Try another spelling, or download a nearby region."
            else -> null
        }
        if (coordinate != null) {
            listOf(coordinate) + results.filterNot { it.id == coordinate.id }
        } else {
            results
        }
    }

    suspend fun nearby(center: GeoPoint, radiusMeters: Double = 1500.0, limit: Int = 40): List<PlaceResult> =
        withContext(io) {
            runCatching { index.nearby(center, radiusMeters, limit) }.getOrDefault(emptyList())
        }

    suspend fun importPack(regionId: String, file: File, onProgress: (Int) -> Unit = {}) =
        withContext(io) { index.importPack(regionId, file, onProgress) }

    suspend fun removeRegion(regionId: String) = withContext(io) { index.deleteRegion(regionId) }

    suspend fun clear() = withContext(io) { index.clearAll() }

    companion object {
        private val COORDINATE = Regex(
            "^\\s*(-?\\d{1,3}(?:\\.\\d+)?)\\s*[,; ]\\s*(-?\\d{1,3}(?:\\.\\d+)?)\\s*$",
        )

        /** Accepts "18.5204, 73.8567" and "18.5204 73.8567". */
        fun parseCoordinate(text: String): PlaceResult? {
            val match = COORDINATE.matchEntire(text) ?: return null
            val lat = match.groupValues[1].toDoubleOrNull() ?: return null
            val lon = match.groupValues[2].toDoubleOrNull() ?: return null
            if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
            val point = GeoPoint(lat, lon)
            return PlaceResult(
                id = "coord:${String.format(Locale.US, "%.6f,%.6f", lat, lon)}",
                name = point.format(6),
                type = "coordinate",
                point = point,
                address = "Dropped pin",
                source = SearchSource.COORDINATE,
                score = 1f,
            )
        }
    }
}
