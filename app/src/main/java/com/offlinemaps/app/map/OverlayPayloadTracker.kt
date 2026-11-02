package com.offlinemaps.app.map

/**
 * Remembers what was last pushed to each overlay GeoJSON source.
 *
 * The overlay sources (location puck, route line, destination marker) are updated from state that
 * often re-emits the same value - a camera move, a recomposition, a navigation tick that does not
 * change the plan. Serialising the GeoJSON again in those cases costs a `String.format` per
 * coordinate plus the document itself, all of it thrown away by the next equality check inside
 * MapLibre. [MapController] therefore builds the document only when the *key* describing the
 * payload actually changed.
 *
 * A key is any value with value semantics: a [com.offlinemaps.core.common.GeoPoint], a
 * `Pair(point, radius)` or the route's `List<GeoPoint>` (compared element by element, so a freshly
 * built but identical geometry list is still recognised as unchanged).
 *
 * Keys are kept per source id and are cleared with [reset] whenever the sources themselves are
 * recreated (a style reload), because a recreated source holds no geometry at all.
 */
internal class OverlayPayloadTracker {

    private val lastKeys = HashMap<String, Any?>()
    private val lastPayloads = HashMap<String, String>()

    /** Payloads actually built and handed to MapLibre. */
    var writes: Int = 0
        private set

    /** Updates skipped because the payload was already in place. */
    var skips: Int = 0
        private set

    /**
     * Returns the JSON to push for [sourceId], or `null` when [key] equals the key last pushed to
     * that source. [build] is invoked only when a payload is actually needed, so an unchanged
     * update allocates nothing beyond the key itself.
     */
    fun payloadFor(sourceId: String, key: Any?, build: () -> String): String? {
        if (lastKeys.containsKey(sourceId) && lastKeys[sourceId] == key) {
            skips++
            return null
        }
        val payload = build()
        lastKeys[sourceId] = key
        lastPayloads[sourceId] = payload
        writes++
        return payload
    }

    /** The payload last pushed to [sourceId], for diagnostics and tests. */
    fun lastPayload(sourceId: String): String? = lastPayloads[sourceId]

    /** Forgets every source. Call when the sources are recreated. */
    fun reset() {
        lastKeys.clear()
        lastPayloads.clear()
    }

    /** Number of sources currently tracked. */
    fun trackedSources(): Int = lastKeys.size
}
