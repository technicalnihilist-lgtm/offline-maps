package com.offlinemaps.core.offline

import com.offlinemaps.core.common.GeoBounds
import com.offlinemaps.core.common.MapPackageType
import com.offlinemaps.core.common.RegionCatalogEntry
import com.offlinemaps.core.common.RegionPackage
import com.offlinemaps.core.common.TileMath
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The downloadable-region catalogue.
 *
 * Bundled in assets so the app works on first launch with no network, and refreshable from a
 * user-configurable URL. Every entry names its licence and attribution because we redistribute
 * nothing we do not have the right to.
 */
@Serializable
data class RegionCatalogFile(
    val version: Int = 1,
    @SerialName("updated") val updated: String = "",
    val regions: List<RegionEntry> = emptyList(),
)

@Serializable
data class RegionEntry(
    // Everything except the packages is defaulted so that one truncated entry in a refreshed
    // catalogue cannot abort the decode of the whole file; [RegionCatalogParser.rejectReason]
    // then throws away just the entries that are actually unusable.
    val id: String = "",
    val name: String = "",
    val country: String = "",
    val south: Double = Double.NaN,
    val west: Double = Double.NaN,
    val north: Double = Double.NaN,
    val east: Double = Double.NaN,
    @SerialName("min_zoom") val minZoom: Int = 4,
    @SerialName("max_zoom") val maxZoom: Int = 14,
    val packages: List<PackageEntry> = emptyList(),
    val license: String = "ODbL-1.0",
    val attribution: String = "© OpenStreetMap contributors",
)

@Serializable
data class PackageEntry(
    val type: String = "",
    val url: String = "",
    @SerialName("size_bytes") val sizeBytes: Long = 0L,
    val sha256: String? = null,
    val md5: String? = null,
    val version: String = "1",
    val license: String = "ODbL-1.0",
)

object RegionCatalogParser {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Web-Mercator latitude limit: outside it there is no tile at any zoom level. */
    private const val MAX_MERCATOR_LAT = 85.05112878

    fun parse(raw: String): RegionCatalogFile = json.decodeFromString(RegionCatalogFile.serializer(), raw)

    /**
     * Why [entry] cannot become a usable region, or `null` when it can.
     *
     * The catalogue is refreshable from a user-configurable URL, so a single bad entry must cost
     * us that entry and nothing more. Inverted bounds used to throw from [GeoBounds.init], which
     * discarded every region in the file, and an inverted zoom range silently produced a tile
     * estimate of zero, which read as "this region is free".
     */
    fun rejectReason(entry: RegionEntry): String? {
        if (entry.id.isBlank()) return "missing id"
        if (entry.name.isBlank()) return "missing name"
        if (!entry.south.isFinite() || !entry.west.isFinite() ||
            !entry.north.isFinite() || !entry.east.isFinite()
        ) {
            return "missing or non-finite bounds"
        }
        if (entry.south > entry.north) return "inverted latitude bounds"
        if (entry.west > entry.east) return "inverted longitude bounds"
        if (entry.south < -MAX_MERCATOR_LAT || entry.north > MAX_MERCATOR_LAT) {
            return "latitude outside the mercator range"
        }
        if (entry.west < -180.0 || entry.east > 180.0) return "longitude outside -180..180"
        if (entry.minZoom < 0 || entry.maxZoom > TileMath.MAX_ZOOM) {
            return "zoom outside 0..${TileMath.MAX_ZOOM}"
        }
        if (entry.minZoom > entry.maxZoom) return "empty zoom range"
        return null
    }

    /** Domain regions for every entry [rejectReason] accepts, in catalogue order. */
    fun toDomain(file: RegionCatalogFile): List<RegionCatalogEntry> =
        file.regions.filter { rejectReason(it) == null }.map { it.toDomain() }

    /** Ids of the entries that were dropped, for logging and for the catalogue-refresh diff. */
    fun rejected(file: RegionCatalogFile): List<String> =
        file.regions.mapNotNull { entry -> rejectReason(entry)?.let { entry.id.ifBlank { "<no id>" } } }

    private fun RegionEntry.toDomain(): RegionCatalogEntry {
        val bounds = GeoBounds(south, west, north, east)
        // A package with no URL cannot be fetched; dropping it keeps the region itself usable.
        val packages = packages.filter { it.url.isNotBlank() }.map { p ->
            RegionPackage(
                type = MapPackageType.entries.firstOrNull { it.name.equals(p.type, true) } ?: MapPackageType.ROUTING_OSM,
                url = p.url,
                sizeBytes = p.sizeBytes,
                sha256 = p.sha256,
                md5 = p.md5,
                version = p.version,
                license = p.license,
            )
        }
        return RegionCatalogEntry(
            id = id,
            name = name,
            country = country,
            bounds = bounds,
            minZoom = minZoom,
            maxZoom = maxZoom,
            packages = packages,
            estimatedTileCount = TileMath.tileCount(bounds, minZoom, maxZoom),
            license = license,
            attribution = attribution,
        )
    }
}
