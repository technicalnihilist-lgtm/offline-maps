package com.offlinemaps.core.search

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.zip.GZIPInputStream

/**
 * A places pack is the offline search dataset: one JSON object per line, gzip-compressed.
 *
 * It is generated from an OpenStreetMap extract by `tools/build_places_pack.py`, so the app never
 * has to parse a PBF on the phone. Everything in the pack came from OSM (ODbL) and carries its
 * place type, which is what lets the UI be honest about what it found.
 */
@Serializable
data class PlaceRecord(
    val id: String,
    val name: String,
    val type: String,
    val lat: Double,
    val lon: Double,
    val admin: String? = null,
    val street: String? = null,
) {
    /**
     * True when [lat]/[lon] are a real WGS84 position (OPTIMIZATION.md I-08).
     *
     * A pack line is text from a download, so it can carry a latitude of 200 or a longitude of
     * 1e9 - or a value that is not a number at all. `GeoPoint` refuses those in its `init`, so a
     * record that reached the index would throw when a search built a point from its row, and one
     * bad line would take down every query over that region. The reader therefore treats such a
     * line as damage and skips it, the same way it skips a line that is not JSON.
     */
    fun hasValidPoint(): Boolean =
        lat.isFinite() && lon.isFinite() && lat in -90.0..90.0 && lon in -180.0..180.0
}

/**
 * What a pack read actually produced, so a caller can tell a complete pack from a damaged one
 * (OPTIMIZATION.md I-04).
 *
 * A download that stopped half-way leaves a gzip whose body ends early, and a pack assembled by
 * hand can carry a line that is not JSON. Neither should cost the user every place in the region,
 * so [PlacesPackReader] keeps whatever decoded and records the damage here.
 */
class PlacesPackStats {
    /** Records that decoded into a usable [PlaceRecord] and were handed to the caller. */
    var records: Int = 0
        internal set

    /**
     * Non-blank lines that could not be decoded, decoded without a name, or carried a coordinate
     * outside the world (|lat| > 90, |lon| > 180, or not finite).
     */
    var skippedLines: Int = 0
        internal set

    /** True when the stream itself failed part-way, which means the pack is incomplete. */
    var truncated: Boolean = false
        internal set

    /** True when the pack was not clean: part of it could not be read. */
    val damaged: Boolean get() = truncated || skippedLines > 0
}

object PlacesPackReader {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Reads a pack of one JSON record per line, gzipped or plain.
     *
     * A damaged pack no longer takes the whole read down. A line that is not JSON - including the
     * half-written last line of a truncated file - is skipped and counted, as is a line whose
     * coordinate is outside the world (|lat| > 90, |lon| > 180, or not finite), which would
     * otherwise throw from `GeoPoint` the first time a search read that row back
     * (OPTIMIZATION.md I-08). A stream that fails part-way (a gzip body that ends before its
     * trailer) ends the sequence after the records that did decode, with
     * [PlacesPackStats.truncated] set. Only a stream whose *header* cannot be read throws, because
     * nothing at all is recoverable from it.
     */
    fun read(input: InputStream, stats: PlacesPackStats = PlacesPackStats()): Sequence<PlaceRecord> = sequence {
        val stream = input.buffered()
        stream.mark(2)
        val b1 = stream.read()
        val b2 = stream.read()
        stream.reset()
        val source = if (b1 == 0x1f && b2 == 0x8b) GZIPInputStream(stream) else stream
        val reader = source.bufferedReader()
        try {
            while (true) {
                val line = try {
                    reader.readLine()
                } catch (e: IOException) {
                    // A truncated gzip body surfaces here as EOFException (or a CRC ZipException at
                    // the end of a damaged stream). Keep the records that already decoded.
                    stats.truncated = true
                    break
                } ?: break
                if (line.isBlank()) continue
                val record = runCatching { json.decodeFromString(PlaceRecord.serializer(), line) }.getOrNull()
                if (record == null || record.name.isBlank() || !record.hasValidPoint()) {
                    stats.skippedLines++
                    continue
                }
                stats.records++
                yield(record)
            }
        } finally {
            // A truncated stream can throw while closing; the read is already over by then.
            runCatching { reader.close() }
        }
    }

    fun read(file: File, stats: PlacesPackStats = PlacesPackStats()): Sequence<PlaceRecord> =
        file.inputStream().use { stream ->
            // Materialise inside the stream scope so the file handle is released immediately.
            read(stream, stats).toList().asSequence()
        }
}
