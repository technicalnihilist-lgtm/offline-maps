package com.offlinemaps.core.search

import com.offlinemaps.core.common.GeoPoint
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
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
    fun toPoint() = GeoPoint(lat, lon)
}

object PlacesPackReader {
    private val json = Json { ignoreUnknownKeys = true }

    fun read(input: InputStream): Sequence<PlaceRecord> = sequence {
        val stream = input.buffered()
        stream.mark(2)
        val b1 = stream.read()
        val b2 = stream.read()
        stream.reset()
        val source = if (b1 == 0x1f && b2 == 0x8b) GZIPInputStream(stream) else stream
        source.bufferedReader().useLines { lines ->
            for (line in lines) {
                if (line.isBlank()) continue
                val record = runCatching { json.decodeFromString(PlaceRecord.serializer(), line) }.getOrNull()
                if (record != null && record.name.isNotBlank()) yield(record)
            }
        }
    }

    fun read(file: File): Sequence<PlaceRecord> = file.inputStream().use { stream ->
        // Materialise inside the stream scope so the file handle is released immediately.
        read(stream).toList().asSequence()
    }
}
