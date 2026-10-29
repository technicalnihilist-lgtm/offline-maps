package com.offlinemaps.core.common

/** Rendering quality presets. Controls 3D detail, terrain and cache aggressiveness. */
enum class GraphicsQuality(val label: String, val maxPitch: Float, val buildingsMinZoom: Int) {
    LOW("Low", 30f, 15),
    MEDIUM("Medium", 50f, 14),
    HIGH("High", 65f, 13),
}

enum class MapTheme { LIGHT, DARK }

enum class MapMode { FLAT_2D, PERSPECTIVE_3D }

/** Travel profiles actually supported by the bundled offline routing engine. */
enum class TravelProfile(val id: String, val label: String) {
    CAR("car", "Driving"),
    BIKE("bike", "Cycling"),
    FOOT("foot", "Walking"),
}

enum class SavedPlaceLabel(val id: String, val label: String) {
    HOME("home", "Home"),
    WORK("work", "Work"),
    SCHOOL("school", "School"),
    CUSTOM("custom", "Saved place"),
}

/** What kind of artefact an offline package installs. */
enum class MapPackageType {
    /** MapLibre offline tile region (vector tiles + terrain cached in MapLibre's store). */
    TILE_REGION,

    /** A single MBTiles database merged into MapLibre's offline store. */
    MBTILES,

    /** An OSM extract imported by GraphHopper into an on-device routing graph. */
    ROUTING_OSM,

    /** A pre-built places index (JSONL, gzip) used for offline search. */
    PLACES_PACK,
}

enum class DownloadStatus { QUEUED, RUNNING, PAUSED, COMPLETED, FAILED, CANCELLED, VERIFYING }

/** Where a search result came from - the UI must never imply more coverage than we have. */
enum class SearchSource { OFFLINE_INDEX, SAVED_PLACE, COORDINATE, TILE_REGION_NAME }

data class PlaceResult(
    val id: String,
    val name: String,
    val type: String,
    val point: GeoPoint,
    val address: String? = null,
    val source: SearchSource = SearchSource.OFFLINE_INDEX,
    val score: Float = 0f,
)

/** State of the optional network connection, surfaced in the UI. */
enum class ConnectivityState { ONLINE, OFFLINE, UNKNOWN }

/** A single map region the user can download. */
data class RegionCatalogEntry(
    val id: String,
    val name: String,
    val country: String,
    val bounds: GeoBounds,
    val minZoom: Int,
    val maxZoom: Int,
    val packages: List<RegionPackage>,
    val estimatedTileCount: Long,
    val license: String,
    val attribution: String,
)

/** One downloadable artefact belonging to a region. */
data class RegionPackage(
    val type: MapPackageType,
    val url: String,
    val sizeBytes: Long,
    val sha256: String? = null,
    val md5: String? = null,
    val version: String = "1",
    val license: String = "ODbL-1.0",
)
