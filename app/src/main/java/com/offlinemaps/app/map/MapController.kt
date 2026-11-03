package com.offlinemaps.app.map

import com.offlinemaps.core.common.GeoPoint
import com.offlinemaps.core.common.MapTheme
import com.offlinemaps.core.map.MapStyleFactory
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import java.util.Locale

/** Camera state the UI mirrors back (compass, zoom level, 2D/3D toggle). */
data class MapCameraState(
    val target: GeoPoint,
    val zoom: Double,
    val tilt: Float,
    val bearing: Float,
)

/**
 * Thin wrapper around [MapLibreMap].
 *
 * Everything the app draws on top of the base map (location puck, route line, destination marker)
 * is a GeoJSON source plus a layer, created once and updated in place. That keeps re-renders cheap
 * on low-end devices - no layer churn while navigating.
 */
class MapController(private val map: MapLibreMap) {

    private var style: Style? = null

    /** What was last pushed to each overlay source, so unchanged geometry is not re-serialised. */
    private val payloads = OverlayPayloadTracker()

    /** Drops location fixes the user could not see the difference of (GNSS jitter). */
    private val puckGate = PuckUpdateGate()

    /** Last visibility written to the extruded-building layer, so a repeat call is not pushed. */
    private var buildingsVisible: Boolean? = null

    fun onStyleLoaded(style: Style) {
        this.style = style
        // The overlay sources are (re)created empty below, so nothing is cached for them yet.
        payloads.reset()
        puckGate.reset()
        // The new style carries whatever visibility its document was generated with, which this
        // object cannot know, so the next call must write rather than be skipped as a repeat.
        buildingsVisible = null
        ensureOverlayLayers(style)
    }

    /** True once a style document has loaded, so layer-property writes will actually take effect. */
    fun hasStyleLoaded(): Boolean = style != null

    /** Applies a generated style document. Overlay layers are re-created on the new style. */
    fun applyStyle(styleJson: String, theme: MapTheme, onLoaded: () -> Unit = {}) {
        map.setStyle(Style.Builder().fromJson(styleJson)) { newStyle ->
            onStyleLoaded(newStyle)
            onLoaded()
        }
    }

    fun moveTo(
        target: GeoPoint,
        zoom: Double,
        tilt: Float,
        bearing: Float,
        animate: Boolean = true,
        durationMs: Int = 650,
    ) {
        val position = CameraPosition.Builder()
            .target(LatLng(target.latitude, target.longitude))
            .zoom(zoom)
            .tilt(tilt.toDouble())
            .bearing(bearing.toDouble())
            .build()
        val update = CameraUpdateFactory.newCameraPosition(position)
        if (animate) map.animateCamera(update, durationMs) else map.moveCamera(update)
    }

    fun zoomBy(delta: Double, animate: Boolean = true) {
        val update = CameraUpdateFactory.zoomBy(delta)
        if (animate) map.animateCamera(update, 250) else map.moveCamera(update)
    }

    fun cameraState(): MapCameraState {
        val position = map.cameraPosition
        val target = position.target ?: LatLng(0.0, 0.0)
        return MapCameraState(
            target = GeoPoint(target.latitude, target.longitude),
            zoom = position.zoom,
            tilt = position.tilt.toFloat(),
            bearing = position.bearing.toFloat(),
        )
    }

    /**
     * Shows the user's position with an accuracy ring. Pass null to hide the puck.
     *
     * Two filters keep this cheap on a stream of fixes. [PuckUpdateGate] drops a fix that moved less
     * than a few metres and did not change the accuracy materially, so a stationary device with a
     * wandering GNSS position no longer redraws the puck; the overlay tracker then skips a document
     * that is identical to the one already on screen (a repeated `lastKnown` emission, a
     * recomposition) after one key compare.
     */
    fun setUserLocation(point: GeoPoint?, accuracyMeters: Float? = null) {
        val currentStyle = style ?: return
        val source = currentStyle.getSourceAs<GeoJsonSource>(USER_SOURCE) ?: return
        if (point == null) {
            // Nothing on screen to compare a future fix against.
            puckGate.reset()
            push(source, USER_SOURCE, HIDDEN_PUCK) { EMPTY_FEATURES }
            return
        }
        if (!puckGate.shouldPush(point, accuracyMeters)) return
        val ringRadius = ((accuracyMeters ?: 0f) / 3f).coerceIn(6f, 90f)
        push(source, USER_SOURCE, UserPuckKey(point, ringRadius)) {
            val coordinates = coord(point)
            """
            {"type":"FeatureCollection","features":[
              {"type":"Feature","properties":{"kind":"accuracy","radius":$ringRadius},
               "geometry":{"type":"Point","coordinates":[$coordinates]}},
              {"type":"Feature","properties":{"kind":"puck"},
               "geometry":{"type":"Point","coordinates":[$coordinates]}}
            ]}
            """.trimIndent()
        }
    }

    /**
     * Draws the active route. Replaces any previous geometry.
     *
     * The geometry list is compared by value, so the same plan (or a rebuilt but identical copy of
     * it) is not serialised again - a route of a few thousand points would otherwise format every
     * coordinate on every call. The last geometry list is held as the comparison key, so treat it
     * as immutable: the app always passes the plan's own list.
     */
    fun setRoute(points: List<GeoPoint>) {
        val currentStyle = style ?: return
        val source = currentStyle.getSourceAs<GeoJsonSource>(ROUTE_SOURCE) ?: return
        if (points.size < 2) {
            push(source, ROUTE_SOURCE, EMPTY_ROUTE_KEY) { EMPTY_FEATURES }
            return
        }
        push(source, ROUTE_SOURCE, points) {
            val coordinates = points.joinToString(",") { "[${coord(it)}]" }
            """{"type":"Feature","properties":{},"geometry":{"type":"LineString","coordinates":[$coordinates]}}"""
        }
    }

    fun setDestination(point: GeoPoint?) {
        val currentStyle = style ?: return
        val source = currentStyle.getSourceAs<GeoJsonSource>(DESTINATION_SOURCE) ?: return
        if (point == null) {
            push(source, DESTINATION_SOURCE, HIDDEN_DESTINATION) { EMPTY_FEATURES }
            return
        }
        push(source, DESTINATION_SOURCE, point) {
            """{"type":"Feature","properties":{},"geometry":{"type":"Point","coordinates":[${coord(point)}]}}"""
        }
    }

    /**
     * Turns the extruded-building layer on or off without reloading the style.
     *
     * The layer is always part of the generated document (hidden when the setting is off), so this
     * is one layout-property write on the live style: no style rebuild, no re-creation of the
     * overlay sources, no tile re-request, and the camera stays exactly where it was. A hidden layer
     * draws nothing, so the frame is what a reloaded style would have produced. Repeated calls with
     * the same value are dropped, because the native write is not free.
     */
    fun setBuildingsVisible(visible: Boolean) {
        val currentStyle = style ?: return
        if (buildingsVisible == visible) return
        val layer = currentStyle.getLayer(BUILDING_LAYER) ?: return
        layer.setProperties(PropertyFactory.visibility(if (visible) Property.VISIBLE else Property.NONE))
        buildingsVisible = visible
    }

    fun destroy() {
        style = null
        payloads.reset()
        puckGate.reset()
    }

    /** Pushes [build]'s document to [source] unless the payload for [key] is already in place. */
    private fun push(source: GeoJsonSource, sourceId: String, key: Any?, build: () -> String) {
        val payload = payloads.payloadFor(sourceId, key, build) ?: return
        source.setGeoJson(payload)
    }

    private fun ensureOverlayLayers(currentStyle: Style) {
        if (currentStyle.getSource(USER_SOURCE) != null) return

        currentStyle.addSource(GeoJsonSource(USER_SOURCE, EMPTY_FEATURES))
        currentStyle.addSource(GeoJsonSource(ROUTE_SOURCE, EMPTY_FEATURES))
        currentStyle.addSource(GeoJsonSource(DESTINATION_SOURCE, EMPTY_FEATURES))

        currentStyle.addLayer(
            CircleLayer(ACCURACY_LAYER, USER_SOURCE).apply {
                setFilter(Expression.eq(Expression.get("kind"), Expression.literal("accuracy")))
                withProperties(
                    PropertyFactory.circleRadius(Expression.get("radius")),
                    PropertyFactory.circleColor("#2D7FF9"),
                    PropertyFactory.circleOpacity(0.14f),
                )
            },
        )
        currentStyle.addLayer(
            CircleLayer(USER_LAYER, USER_SOURCE).apply {
                setFilter(Expression.eq(Expression.get("kind"), Expression.literal("puck")))
                withProperties(
                    PropertyFactory.circleRadius(7f),
                    PropertyFactory.circleColor("#2D7FF9"),
                    PropertyFactory.circleStrokeWidth(3f),
                    PropertyFactory.circleStrokeColor("#FFFFFF"),
                )
            },
        )
        currentStyle.addLayer(
            LineLayer(ROUTE_CASING, ROUTE_SOURCE).withProperties(
                PropertyFactory.lineColor("#0B3D2E"),
                PropertyFactory.lineWidth(9f),
                PropertyFactory.lineOpacity(0.55f),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
            ),
        )
        currentStyle.addLayer(
            LineLayer(ROUTE_LAYER, ROUTE_SOURCE).withProperties(
                PropertyFactory.lineColor("#2ECC71"),
                PropertyFactory.lineWidth(5.5f),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
            ),
        )
        currentStyle.addLayer(
            CircleLayer(DESTINATION_LAYER, DESTINATION_SOURCE).withProperties(
                PropertyFactory.circleRadius(8f),
                PropertyFactory.circleColor("#E03E3E"),
                PropertyFactory.circleStrokeWidth(3f),
                PropertyFactory.circleStrokeColor("#FFFFFF"),
            ),
        )
    }

    /** Value key for the location puck: the position plus the ring radius derived from accuracy. */
    private data class UserPuckKey(val point: GeoPoint, val radius: Float)

    private fun coord(point: GeoPoint): String =
        String.format(Locale.US, "%.6f,%.6f", point.longitude, point.latitude)

    private companion object {
        const val USER_SOURCE = "app-user-source"
        const val ROUTE_SOURCE = "app-route-source"
        const val DESTINATION_SOURCE = "app-destination-source"
        const val ACCURACY_LAYER = "app-accuracy-layer"
        const val USER_LAYER = "app-user-layer"
        const val ROUTE_CASING = "app-route-casing"
        const val ROUTE_LAYER = "app-route-layer"
        const val DESTINATION_LAYER = "app-destination-layer"
        /** Emitted by the style generator in every document; only its visibility ever changes. */
        val BUILDING_LAYER = MapStyleFactory.BUILDING_3D_LAYER_ID
        const val EMPTY_FEATURES = """{"type":"FeatureCollection","features":[]}"""

        /** Canonical keys for the "nothing to draw" payload, so hiding a source is also deduped. */
        val HIDDEN_PUCK = Any()
        val HIDDEN_DESTINATION = Any()
        val EMPTY_ROUTE_KEY: List<GeoPoint> = emptyList()
    }
}
