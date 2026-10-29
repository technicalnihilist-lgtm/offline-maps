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
    val id: String,
    val name: String,
    val country: String,
    val south: Double,
    val west: Double,
    val north: Double,
    val east: Double,
    @SerialName("min_zoom") val minZoom: Int = 4,
    @SerialName("max_zoom") val maxZoom: Int = 14,
    val packages: List<PackageEntry> = emptyList(),
    val license: String = "ODbL-1.0",
    val attribution: String = "© OpenStreetMap contributors",
)

@Serializable
data class PackageEntry(
    val type: String,
    val url: String,
    @SerialName("size_bytes") val sizeBytes: Long = 0L,
    val sha256: String? = null,
    val md5: String? = null,
    val version: String = "1",
    val license: String = "ODbL-1.0",
)

object RegionCatalogParser {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun parse(raw: String): RegionCatalogFile = json.decodeFromString(RegionCatalogFile.serializer(), raw)

    fun toDomain(file: RegionCatalogFile): List<RegionCatalogEntry> = file.regions.map { entry ->
        val bounds = GeoBounds(entry.south, entry.west, entry.north, entry.east)
        val packages = entry.packages.map { p ->
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
        RegionCatalogEntry(
            id = entry.id,
            name = entry.name,
            country = entry.country,
            bounds = bounds,
            minZoom = entry.minZoom,
            maxZoom = entry.maxZoom,
            packages = packages,
            estimatedTileCount = TileMath.tileCount(bounds, entry.minZoom, entry.maxZoom),
            license = entry.license,
            attribution = entry.attribution,
        )
    }
}
