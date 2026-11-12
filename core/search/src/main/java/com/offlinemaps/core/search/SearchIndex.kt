package com.offlinemaps.core.search

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.offlinemaps.core.common.GeoBounds
import com.offlinemaps.core.common.GeoMath
import com.offlinemaps.core.common.GeoPoint
import com.offlinemaps.core.common.PlaceResult
import com.offlinemaps.core.common.SearchSource
import java.io.File

/**
 * On-device search index over downloaded places.
 *
 * Backed by SQLite with an FTS4 virtual table, so prefix search over tens of thousands of places
 * stays instant without holding anything in memory. One database per device; rows are tagged with
 * the region that supplied them so deleting a region can remove its places.
 */
class SearchIndex(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(CREATE_PLACES)
        db.execSQL(CREATE_PLACES_FTS)
        createLookupIndexes(db)
    }

    /**
     * v2 only changes how `places_fts` is stored, so an upgrade rebuilds that table from `places`
     * and leaves the rows alone: the two hold the same text, and a user must never lose an
     * imported region - or have to re-download it - because the index layout changed.
     *
     * Every upgrade path that keeps the rows ends in [createLookupIndexes], so a database can never
     * come out of an upgrade without the two lookup indexes the map screen queries through
     * (OPTIMIZATION.md D-04). The statements are `IF NOT EXISTS`, so on an install that already has
     * them this is a no-op catalog lookup and nothing is rewritten or re-downloaded.
     */
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (!tableExists(db, "places")) {
            db.execSQL("DROP TABLE IF EXISTS places_fts")
            onCreate(db)
            return
        }
        if (oldVersion < 2) {
            db.execSQL("DROP TABLE IF EXISTS places_fts")
            db.execSQL(CREATE_PLACES_FTS)
            db.execSQL(
                "INSERT INTO places_fts(rowid, name, admin, street) " +
                    "SELECT rowid, name, IFNULL(admin, ''), IFNULL(street, '') FROM places",
            )
        }
        createLookupIndexes(db)
    }

    /**
     * The two indexes the interactive queries run on. `nearby` bounds its scan by `lat` and filters
     * `lon`, so it reads `idx_places_latlon` instead of walking the table; deleting or counting a
     * region reads `idx_places_region` (OPTIMIZATION.md D-04).
     */
    private fun createLookupIndexes(db: SQLiteDatabase) {
        db.execSQL(CREATE_INDEX_LATLON)
        db.execSQL(CREATE_INDEX_REGION)
    }

    private fun tableExists(db: SQLiteDatabase, table: String): Boolean =
        db.rawQuery("SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?", arrayOf(table))
            .use { it.moveToFirst() }

    /**
     * Bulk insert, replacing any previous rows for the same region. Runs in one transaction.
     *
     * Returns the number of rows written. A [Sequence] that fails part-way (a truncated pack, see
     * [PlacesPackReader]) rolls the transaction back and rethrows, so a half-read pack never leaves
     * a region with a partial set of rows; the file overload below is the one that salvages.
     */
    fun importPack(regionId: String, records: Sequence<PlaceRecord>, onProgress: (Int) -> Unit = {}): Int {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("places", "region_id = ?", arrayOf(regionId))
            db.execSQL("DELETE FROM places_fts WHERE rowid NOT IN (SELECT rowid FROM places)")
            var count = 0
            records.forEach { record ->
                val values = ContentValues().apply {
                    put("id", record.id)
                    put("name", record.name)
                    put("type", record.type)
                    put("lat", record.lat)
                    put("lon", record.lon)
                    put("admin", record.admin)
                    put("street", record.street)
                    put("region_id", regionId)
                    put("weight", weightForType(record.type))
                }
                val rowId = db.insertWithOnConflict("places", null, values, SQLiteDatabase.CONFLICT_REPLACE)
                if (rowId >= 0) {
                    db.execSQL(
                        "INSERT INTO places_fts(rowid, name, admin, street) VALUES (?, ?, ?, ?)",
                        arrayOf(rowId, record.name, record.admin ?: "", record.street ?: ""),
                    )
                    count++
                }
                if (count % 5000 == 0) onProgress(count)
            }
            db.setTransactionSuccessful()
            onProgress(count)
            return count
        } finally {
            db.endTransaction()
        }
    }

    /**
     * Imports a places pack from disk and reports what it contained (OPTIMIZATION.md I-04).
     *
     * The reader keeps every record that decodes, so a pack whose gzip body ends early still
     * indexes the readable prefix instead of leaving the region with no offline search at all; the
     * returned [PlacesPackStats] is how the caller learns that the pack was incomplete.
     */
    fun importPack(regionId: String, file: File, onProgress: (Int) -> Unit = {}): PlacesPackStats {
        val stats = PlacesPackStats()
        importPack(regionId, PlacesPackReader.read(file, stats), onProgress)
        return stats
    }

    fun count(): Int = readableDatabase.rawQuery("SELECT COUNT(*) FROM places", null).use {
        if (it.moveToFirst()) it.getInt(0) else 0
    }

    /**
     * Opens the database and reads the index once, so the first real query does not pay for it
     * (OPTIMIZATION.md A-06).
     *
     * Opening the file, loading the FTS segment list and preparing the join are the costs the first
     * search carries; running a representative prefix query here moves them to a background warm-up
     * after the first frame. Returns the number of indexed places, so a caller can tell an empty
     * index (nothing installed yet) from a warmed one. Read-only: it changes no row and no file
     * beyond SQLite's own page cache.
     */
    fun warmUp(): Int {
        val total = count()
        if (total > 0) {
            readableDatabase.rawQuery(
                "SELECT p.rowid FROM places_fts f JOIN places p ON p.rowid = f.rowid " +
                    "WHERE places_fts MATCH ? LIMIT 1",
                arrayOf("a*"),
            ).use { it.moveToFirst() }
        }
        return total
    }

    fun countForRegion(regionId: String): Int =
        readableDatabase.rawQuery("SELECT COUNT(*) FROM places WHERE region_id = ?", arrayOf(regionId)).use {
            if (it.moveToFirst()) it.getInt(0) else 0
        }

    /**
     * Prefix search. The user's text is turned into safe FTS terms - quotes and operators are
     * stripped so a stray character cannot produce a SQL or FTS syntax error.
     */
    fun search(query: String, limit: Int = 25): List<PlaceResult> {
        val terms = toFtsTerms(query)
        if (terms.isEmpty()) return emptyList()
        val sql = """
            SELECT p.id, p.name, p.type, p.lat, p.lon, p.admin, p.street, p.weight
            FROM places_fts f
            JOIN places p ON p.rowid = f.rowid
            WHERE places_fts MATCH ?
            ORDER BY p.weight ASC, LENGTH(p.name) ASC, p.name ASC
            LIMIT ?
        """.trimIndent()
        return readableDatabase.rawQuery(sql, arrayOf(terms, limit.toString())).use { cursor ->
            val out = ArrayList<PlaceResult>(cursor.count)
            while (cursor.moveToNext()) {
                out.add(
                    PlaceResult(
                        id = cursor.getString(0),
                        name = cursor.getString(1),
                        type = cursor.getString(2),
                        point = GeoPoint(cursor.getDouble(3), cursor.getDouble(4)),
                        address = listOfNotNull(
                            cursor.getString(6)?.takeIf { it.isNotBlank() },
                            cursor.getString(5)?.takeIf { it.isNotBlank() },
                        ).joinToString(", ").ifBlank { null },
                        source = SearchSource.OFFLINE_INDEX,
                        score = 1f - (cursor.getInt(7) / 100f),
                    ),
                )
            }
            out
        }
    }

    /**
     * Places inside a bounding box, ordered by distance from [center] - used for "nearby".
     *
     * The bounding box is filtered in SQL, so a small radius never reads the region's places that
     * lie outside it. The exact radius test cannot be expressed without trigonometry, so it runs in
     * Kotlin - but on a bounded set: the select returns at most [rowCapFor] rows, and it orders by
     * distance, so what the cap drops is the far end of the box rather than an arbitrary slice of
     * the table (OPTIMIZATION.md D-05). Rows the radius rejects are dropped as they are read, so no
     * [PlaceResult] is built for a place that will not be returned.
     */
    fun nearby(center: GeoPoint, radiusMeters: Double, limit: Int = 40): List<PlaceResult> {
        // A single point is its own bounds, so build them directly instead of going through
        // GeoBounds.of (which can return null for an empty list and forces a non-null assertion).
        val bounds = GeoBounds(
            south = center.latitude,
            west = center.longitude,
            north = center.latitude,
            east = center.longitude,
        ).expandedBy(radiusMeters)
        val rowCap = rowCapFor(limit)
        val found = ArrayList<Pair<Double, PlaceResult>>(minOf(rowCap, 64))
        readableDatabase.rawQuery(NEARBY_BBOX_SQL, nearbyArgs(center, bounds, rowCap)).use { cursor ->
            while (cursor.moveToNext()) {
                val point = GeoPoint(cursor.getDouble(3), cursor.getDouble(4))
                val distance = GeoMath.distanceMeters(center, point)
                if (distance > radiusMeters) continue
                found.add(
                    distance to PlaceResult(
                        id = cursor.getString(0),
                        name = cursor.getString(1),
                        type = cursor.getString(2),
                        point = point,
                        address = cursor.getString(6),
                        source = SearchSource.OFFLINE_INDEX,
                    ),
                )
            }
        }
        return found.sortedBy { it.first }.take(limit).map { it.second }
    }

    fun deleteRegion(regionId: String) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.execSQL(
                "DELETE FROM places_fts WHERE rowid IN (SELECT rowid FROM places WHERE region_id = ?)",
                arrayOf(regionId),
            )
            db.delete("places", "region_id = ?", arrayOf(regionId))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun clearAll() {
        writableDatabase.apply {
            execSQL("DELETE FROM places")
            execSQL("DELETE FROM places_fts")
        }
    }

    companion object {
        private const val DATABASE_NAME = "offline_search.db"
        private const val VERSION = 2

        /**
         * `nearby`'s bounding-box select; kept here so a test can EXPLAIN the real query (D-04).
         *
         * The `BETWEEN` clauses are the box filter and run in SQLite. The `ORDER BY` is an
         * approximate distance - degrees of latitude, plus degrees of longitude scaled by cos(lat)
         * so it is comparable away from the equator - which makes the `LIMIT` keep the near end of
         * the box instead of an arbitrary slice of it. The exact radius test still runs in Kotlin;
         * it needs trigonometry SQLite may not provide (OPTIMIZATION.md D-05).
         */
        internal val NEARBY_BBOX_SQL = """
            SELECT id, name, type, lat, lon, admin, street
            FROM places
            WHERE lat BETWEEN ? AND ? AND lon BETWEEN ? AND ?
            ORDER BY (lat - ?) * (lat - ?) + (lon - ?) * (lon - ?) * ?
            LIMIT ?
        """.trimIndent()

        /** Ceiling on the rows one `nearby` call may read, whatever limit the caller asks for. */
        internal const val NEARBY_MAX_ROWS = 400

        /** Rows read per requested result, so the radius test has spares to reject. */
        private const val NEARBY_OVERFETCH = 4

        /**
         * Rows [nearby] lets the bounding-box select return for a request of [limit] results. It is
         * derived from the limit rather than fixed, so a caller asking for 5 places does not pay for
         * 400, and it is bounded by [NEARBY_MAX_ROWS], so no caller can ask for an unbounded read.
         */
        internal fun rowCapFor(limit: Int): Int =
            (limit.coerceAtLeast(1) * NEARBY_OVERFETCH).coerceAtMost(NEARBY_MAX_ROWS)

        /** The bound parameters of [NEARBY_BBOX_SQL], in order, for [bounds] around [center]. */
        internal fun nearbyArgs(center: GeoPoint, bounds: GeoBounds, rowCap: Int): Array<String> {
            val cosLat = kotlin.math.cos(Math.toRadians(center.latitude))
            val lonScaleSq = (cosLat * cosLat).coerceAtLeast(1e-12)
            return arrayOf(
                bounds.south.toString(),
                bounds.north.toString(),
                bounds.west.toString(),
                bounds.east.toString(),
                center.latitude.toString(),
                center.latitude.toString(),
                center.longitude.toString(),
                center.longitude.toString(),
                lonScaleSq.toString(),
                rowCap.toString(),
            )
        }

        private const val CREATE_INDEX_LATLON =
            "CREATE INDEX IF NOT EXISTS idx_places_latlon ON places(lat, lon)"

        private const val CREATE_INDEX_REGION =
            "CREATE INDEX IF NOT EXISTS idx_places_region ON places(region_id)"

        private val CREATE_PLACES = """
            CREATE TABLE places (
                id TEXT PRIMARY KEY,
                name TEXT NOT NULL,
                type TEXT NOT NULL,
                lat REAL NOT NULL,
                lon REAL NOT NULL,
                admin TEXT,
                street TEXT,
                region_id TEXT NOT NULL,
                weight INTEGER NOT NULL DEFAULT 50
            )
        """.trimIndent()

        /**
         * `prefix="2,3"` keeps prefix indexes for 2- and 3-character prefixes (OPTIMIZATION.md
         * D-01). Search always queries `term*`, so without them every prefix query enumerates the
         * whole doclist of the term before the join and sort can start. Measured on a 60k-place
         * index, the prefix indexes cut that enumeration by roughly a third for one-character
         * queries and turn a no-match multi-term query into a single cheap lookup; the results,
         * their order and the row count are identical, and the index file grows by about 20%.
         */
        private const val CREATE_PLACES_FTS =
            "CREATE VIRTUAL TABLE places_fts USING fts4(" +
                "name, admin, street, tokenize=unicode61, prefix=\"2,3\")"

        fun toFtsTerms(query: String): String {
            // Strip everything that is not a letter, digit or space, then build one prefix term per
            // word. The result is always either empty or one to six space separated `<letters and
            // digits>*` terms, which is a valid FTS4 MATCH expression for any input (D-07):
            //
            // * every character that carries FTS meaning - `"` for a phrase, `*` for a prefix,
            //   `-` for NOT, `:` for a column filter, `(`/`)` for grouping, `^`, `\` - is
            //   replaced with a space, so none of them can reach the parser;
            // * the operator words (AND, OR, NOT, NEAR) are letters and survive, but Android's
            //   bundled SQLite has no enhanced query syntax, so they are searched as words rather
            //   than acting as operators - "a NEAR b" is three terms, not an error;
            // * no term is ever empty, and the count is capped, so nothing can be left dangling
            //   next to a space.
            val cleaned = query.replace(Regex("[^\\p{L}\\p{N}\\s]"), " ").trim()
            if (cleaned.isEmpty()) return ""
            // FTS4 prefix queries must be written as `term*` - a quoted `"term"*` only matches a
            // complete token and silently returns nothing for partial input.
            //
            // Terms are joined with a space, not " AND ". Android's bundled SQLite is built
            // without the FTS3/4 "enhanced query syntax" flag, so it treats a literal AND as a
            // search term (returning nothing) where desktop SQLite treats it as an operator.
            // Space-separated terms are an implicit AND on both.
            val terms = cleaned.split(Regex("\\s+")).filter { it.isNotBlank() }.take(6)
            return terms.joinToString(" ") { "$it*" }
        }

        fun weightForType(type: String): Int = when (type.lowercase()) {
            "country" -> 5
            "state", "region", "province" -> 10
            "city" -> 15
            "town" -> 20
            "village" -> 25
            "suburb", "neighbourhood", "hamlet", "locality" -> 35
            "street", "road", "highway" -> 45
            "address" -> 50
            "poi", "amenity", "shop", "tourism" -> 60
            else -> 70
        }
    }
}
