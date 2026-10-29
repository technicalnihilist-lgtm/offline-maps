package com.offlinemaps.app.map

import com.offlinemaps.core.common.GeoPoint
import com.offlinemaps.core.common.MapTheme
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

    fun onStyleLoaded(style: Style) {
        this.style = style
        ensureOverlayLayers(style)
    }

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

    /** Shows the user's position with an accuracy ring. Pass null to hide the puck. */
    fun setUserLocation(point: GeoPoint?, accuracyMeters: Float? = null) {
        val currentStyle = style ?: return
        val source = currentStyle.getSourceAs<GeoJsonSource>(USER_SOURCE) ?: return
        if (point == null) {
            source.setGeoJson(EMPTY_FEATURES)
            return
        }
        val ringRadius = ((accuracyMeters ?: 0f) / 3f).coerceIn(6f, 90f)
        source.setGeoJson(
            """
            {"type":"FeatureCollection","features":[
              {"type":"Feature","properties":{"kind":"accuracy","radius":$ringRadius},
               "geometry":{"type":"Point","coordinates":[${coord(point)}]}},
              {"type":"Feature","properties":{"kind":"puck"},
               "geometry":{"type":"Point","coordinates":[${coord(point)}]}}
            ]}
            """.trimIndent(),
        )
    }

    /** Draws the active route. Replaces any previous geometry. */
    fun setRoute(points: List<GeoPoint>) {
        val currentStyle = style ?: return
        val source = currentStyle.getSourceAs<GeoJsonSource>(ROUTE_SOURCE) ?: return
        if (points.size < 2) {
            source.setGeoJson(EMPTY_FEATURES)
            return
        }
        val coordinates = points.joinToString(",") { "[${coord(it)}]" }
        source.setGeoJson(
            """{"type":"Feature","properties":{},"geometry":{"type":"LineString","coordinates":[$coordinates]}}""",
        )
    }

    fun setDestination(point: GeoPoint?) {
        val currentStyle = style ?: return
        val source = currentStyle.getSourceAs<GeoJsonSource>(DESTINATION_SOURCE) ?: return
        if (point == null) {
            source.setGeoJson(EMPTY_FEATURES)
            return
        }
        source.setGeoJson(
            """{"type":"Feature","properties":{},"geometry":{"type":"Point","coordinates":[${coord(point)}]}}""",
        )
    }

    /** Turns the extruded-building layer on or off without reloading the style. */
    fun setBuildingsVisible(visible: Boolean) {
        style?.getLayer(BUILDING_LAYER)?.setProperties(
            PropertyFactory.visibility(if (visible) Property.VISIBLE else Property.NONE),
        )
    }

    fun destroy() {
        style = null
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
        const val BUILDING_LAYER = "building-3d"
        const val EMPTY_FEATURES = """{"type":"FeatureCollection","features":[]}"""
    }
}
