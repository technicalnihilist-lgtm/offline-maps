package com.offlinemaps.core.map

/**
 * The overlay sources and layers the app puts on top of whatever style document is loaded.
 *
 * Everything the app draws over the base map (location puck and its accuracy ring, route casing and
 * line, destination marker) is a GeoJSON source plus a layer that is created once and then updated
 * in place. Nothing is ever removed while a style lives: clearing a route writes an empty
 * `FeatureCollection` into the existing route source and clearing the destination does the same to
 * its source, so a session that routes twenty times still holds exactly three sources and five
 * layers. The style document itself is the only thing that resets them - a reload drops the whole
 * old [org.maplibre.android.maps.Style] and the map controller installs the overlays again on the
 * new one.
 *
 * This object is the single list of those ids, plus the pure decision of what a style still needs.
 * [missing] is idempotent: run against a style that already holds the overlays it returns an empty
 * [Install], which is what makes a repeated install (a second controller on the same style, a
 * style loaded twice) free, and it is per id rather than all-or-nothing, so a style that somehow
 * kept one overlay source still gets the rest. Nothing here touches a map, so it is testable on the
 * JVM.
 */
object OverlayLayerPlan {

    const val USER_SOURCE = "app-user-source"
    const val ROUTE_SOURCE = "app-route-source"
    const val DESTINATION_SOURCE = "app-destination-source"

    const val ACCURACY_LAYER = "app-accuracy-layer"
    const val USER_LAYER = "app-user-layer"
    const val ROUTE_CASING = "app-route-casing"
    const val ROUTE_LAYER = "app-route-layer"
    const val DESTINATION_LAYER = "app-destination-layer"

    /** One overlay layer and the overlay source it reads. */
    data class Layer(val id: String, val sourceId: String)

    /** Every overlay source, in the order they must be added - the layers read them. */
    val sources: List<String> = listOf(USER_SOURCE, ROUTE_SOURCE, DESTINATION_SOURCE)

    /** Every overlay layer, in draw order: accuracy ring, puck, route casing, route, destination. */
    val layers: List<Layer> = listOf(
        Layer(ACCURACY_LAYER, USER_SOURCE),
        Layer(USER_LAYER, USER_SOURCE),
        Layer(ROUTE_CASING, ROUTE_SOURCE),
        Layer(ROUTE_LAYER, ROUTE_SOURCE),
        Layer(DESTINATION_LAYER, DESTINATION_SOURCE),
    )

    /** What a style still has to be given: sources first, then the layers that read them. */
    data class Install(val sources: List<String>, val layers: List<Layer>) {
        val isEmpty: Boolean get() = sources.isEmpty() && layers.isEmpty()

        /** Number of native adds this install performs. */
        val size: Int get() = sources.size + layers.size
    }

    /**
     * What [sourceExists]/[layerExists] report as absent.
     *
     * Both predicates are asked once per declared id, so the caller can back them with the live
     * style's own lookup; a source that is already there is never added again.
     */
    fun missing(
        sourceExists: (String) -> Boolean,
        layerExists: (String) -> Boolean,
    ): Install {
        val missingSources = sources.filterNot(sourceExists)
        val missingLayers = layers.filterNot { layerExists(it.id) }
        return Install(missingSources, missingLayers)
    }
}
