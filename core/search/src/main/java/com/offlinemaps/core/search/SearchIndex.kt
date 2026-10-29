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
        db.execSQL(
            """
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
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE VIRTUAL TABLE places_fts USING fts4(name, admin, street, tokenize=unicode61)",
        )
        db.execSQL("CREATE INDEX idx_places_latlon ON places(lat, lon)")
        db.execSQL("CREATE INDEX idx_places_region ON places(region_id)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS places")
        db.execSQL("DROP TABLE IF EXISTS places_fts")
        onCreate(db)
    }

    /** Bulk insert, replacing any previous rows for the same region. Runs in one transaction. */
    fun importPack(regionId: String, records: Sequence<PlaceRecord>, onProgress: (Int) -> Unit = {}) {
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
        } finally {
            db.endTransaction()
        }
    }

    fun importPack(regionId: String, file: File, onProgress: (Int) -> Unit = {}) {
        importPack(regionId, PlacesPackReader.read(file), onProgress)
    }

    fun count(): Int = readableDatabase.rawQuery("SELECT COUNT(*) FROM places", null).use {
        if (it.moveToFirst()) it.getInt(0) else 0
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

    /** Places inside a bounding box, ordered by distance from [center] - used for "nearby". */
    fun nearby(center: GeoPoint, radiusMeters: Double, limit: Int = 40): List<PlaceResult> {
        val bounds = GeoBounds.of(listOf(center))!!.expandedBy(radiusMeters)
        val sql = """
            SELECT id, name, type, lat, lon, admin, street
            FROM places
            WHERE lat BETWEEN ? AND ? AND lon BETWEEN ? AND ?
            LIMIT 400
        """.trimIndent()
        val rows = readableDatabase.rawQuery(
            sql,
            arrayOf(bounds.south.toString(), bounds.north.toString(), bounds.west.toString(), bounds.east.toString()),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        PlaceResult(
                            id = cursor.getString(0),
                            name = cursor.getString(1),
                            type = cursor.getString(2),
                            point = GeoPoint(cursor.getDouble(3), cursor.getDouble(4)),
                            address = cursor.getString(6),
                            source = SearchSource.OFFLINE_INDEX,
                        ),
                    )
                }
            }
        }
        return rows.map { it to GeoMath.distanceMeters(center, it.point) }
            .filter { it.second <= radiusMeters }
            .sortedBy { it.second }
            .take(limit)
            .map { it.first }
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
        private const val VERSION = 1

        fun toFtsTerms(query: String): String {
            // Strip everything that is not a letter, digit or space. That removes FTS operators
            // (AND/OR/NOT/NEAR), quotes and punctuation, so a stray character can never turn the
            // user's text into an FTS syntax error or an injection.
            val cleaned = query.replace(Regex("[^\\p{L}\\p{N}\\s]"), " ").trim()
            if (cleaned.isEmpty()) return ""
            // FTS4 prefix queries must be written as `term*` - a quoted `"term"*` only matches a
            // complete token and silently returns nothing for partial input.
            val terms = cleaned.split(Regex("\\s+")).filter { it.isNotBlank() }.take(6)
            return terms.joinToString(" AND ") { "$it*" }
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
