package com.offlinemaps.core.search

import com.offlinemaps.core.common.GeoPoint
import com.offlinemaps.core.common.PlaceResult
import com.offlinemaps.core.common.SearchSource
import java.io.File
import java.util.Locale
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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
 *
 * [prewarmIndex] moves that first open off the user's first search: it runs once per process, in the
 * background, and records how long it took and how long the first search takes afterwards
 * (OPTIMIZATION.md A-06). The timing goes to [logger] and nowhere else.
 */
class SearchRepository(
    private val index: SearchIndex,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000L },
    logger: (String) -> Unit = {},
) {

    private val prewarm = SearchPrewarm(warm = { index.warmUp() }, clock = clock, logger = logger)

    @Volatile
    var lastCoverageNote: String? = null
        private set

    /** Places seen by the one-shot warm-up; null until [prewarmIndex] has run. */
    val warmupPlaces: Int? get() = prewarm.places

    /** Cost of the one-shot warm-up in milliseconds; null until [prewarmIndex] has run. */
    val warmupMillis: Long? get() = prewarm.warmupMillis

    /** Cost of the first search after the warm-up in milliseconds; null until one has run. */
    val firstSearchMillis: Long? get() = prewarm.firstSearchMillis

    /**
     * Opens and touches the index once per process, on [io]. Safe to call more than once: after the
     * first call this returns the recorded cost without touching a dispatcher or the disk, so a
     * recreated activity cannot warm the index twice.
     */
    suspend fun prewarmIndex(): Long? =
        if (prewarm.hasRun) prewarm.warmupMillis else withContext(io) { prewarm.run() }

    suspend fun indexSize(): Int = withContext(io) { sizeBlocking() }

    private fun sizeBlocking(): Int = runCatching { index.count() }.getOrDefault(0)

    suspend fun search(query: String, limit: Int = 25): List<PlaceResult> = withContext(io) {
        val trimmed = query.trim()
        if (trimmed.length < 2) return@withContext emptyList()

        // A query made only of punctuation - "!!!", "...", "@#$" - has no letters or digits, so
        // it produces no FTS terms and cannot match a single row. The answer is therefore known
        // before any work is done, and it is given here instead of opening the index: on a cold
        // process the coverage count below would open the SQLite file for a query that can never
        // return anything (OPTIMIZATION.md D-06). The note is the one a no-match query already
        // publishes, so the screen says exactly what it said before.
        if (SearchIndex.toFtsTerms(trimmed).isEmpty()) {
            currentCoroutineContext().ensureActive()
            lastCoverageNote = NOTHING_MATCHED
            return@withContext emptyList()
        }

        val startedAt = clock()
        val coordinate = parseCoordinate(trimmed)
        val results = runCatching { index.search(trimmed, limit) }.getOrDefault(emptyList())
        // The query itself is a blocking SQLite call, so a search superseded while it ran cannot be
        // interrupted inside it - it stops at the edge of that call instead, before it publishes
        // anything. Both the coverage note and the first-search timing are process-wide, so a
        // cancelled search that wrote them would describe the newest query with an older answer
        // (OPTIMIZATION.md D-03).
        currentCoroutineContext().ensureActive()
        val note = when {
            sizeBlocking() == 0 -> NO_DATA_NOTE
            results.isEmpty() -> NOTHING_MATCHED
            else -> null
        }
        currentCoroutineContext().ensureActive()
        lastCoverageNote = note
        // Timed after the coverage count too, so the number is what the user actually waited for
        // (OPTIMIZATION.md A-06). Only the first search of the process is kept.
        prewarm.recordSearch(clock() - startedAt)
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
        internal const val NO_DATA_NOTE =
            "No offline search data is installed yet. Download a region's places pack to search without internet."

        internal const val NOTHING_MATCHED =
            "Nothing matched in your downloaded map data. Try another spelling, or download a nearby region."

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
