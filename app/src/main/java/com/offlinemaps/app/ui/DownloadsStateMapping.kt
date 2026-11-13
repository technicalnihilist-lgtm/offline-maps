package com.offlinemaps.app.ui

import com.offlinemaps.core.common.MapPackageType
import com.offlinemaps.core.data.DownloadRecord
import com.offlinemaps.core.data.InstalledRegion
import com.offlinemaps.core.offline.DownloadProgress
import com.offlinemaps.core.offline.DownloadRuntime

/**
 * The pure half of [DownloadsViewModel]: turning the download engine's runtime map into the two
 * maps the screen renders, deciding whether a finished record still needs installing, and naming a
 * package key or a file. Nothing here touches a Context, a dispatcher or the engine, so all of it
 * is pinned by plain JVM tests (OPTIMIZATION.md J-01).
 *
 * These functions are the single definition of that mapping - the ViewModel must call them rather
 * than re-deriving the same expressions, or the tests stop describing what ships.
 */

/** The package key a tile region's download is filed under. */
internal fun tileKeyFor(regionId: String): String = "$regionId-${MapPackageType.TILE_REGION.name}"

/** The on-disk extension for a package type; the file name is `<packageKey>.<extension>`. */
internal fun packageExtension(type: MapPackageType): String = when (type) {
    MapPackageType.ROUTING_OSM -> "osm.pbf"
    MapPackageType.PLACES_PACK -> "jsonl.gz"
    MapPackageType.MBTILES -> "mbtiles"
    MapPackageType.TILE_REGION -> "tiles"
}

/**
 * True when a completed download needs no install step because the region already carries that
 * artefact. Tile regions are installed by MapLibre itself and never appear here as a record to
 * install, so they are not "already installed" by this rule.
 */
internal fun isAlreadyInstalled(record: DownloadRecord, installed: List<InstalledRegion>): Boolean =
    installed.any {
        it.regionId == record.regionId &&
            ((record.type == MapPackageType.ROUTING_OSM && it.routingReady) ||
                (record.type == MapPackageType.PLACES_PACK && it.searchReady))
    }

/** Progress for every package that has any; a package with no progress is simply absent. */
internal fun progressByPackage(states: Map<String, DownloadRuntime>): Map<String, DownloadProgress> =
    states.mapNotNullValues { it.value.progress }

/** The error line for every package that failed; a healthy package is simply absent. */
internal fun errorsByPackage(states: Map<String, DownloadRuntime>): Map<String, String> =
    states.mapNotNullValues { it.value.error }

/**
 * `mapValues` that drops the entries whose transform returned null, keeping the iteration order of
 * the source map so the screen does not reshuffle rows between emissions.
 */
internal inline fun <K, V, R> Map<K, V>.mapNotNullValues(transform: (Map.Entry<K, V>) -> R?): Map<K, R> {
    val result = LinkedHashMap<K, R>(size)
    for (entry in entries) {
        val mapped = transform(entry)
        if (mapped != null) result[entry.key] = mapped
    }
    return result
}
