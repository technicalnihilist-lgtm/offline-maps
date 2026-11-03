package com.offlinemaps.app.map

import com.offlinemaps.core.common.GeoPoint
import com.offlinemaps.core.common.MapTheme
import com.offlinemaps.core.map.CameraAnimationTracker
import com.offlinemaps.core.map.CameraMovePolicy
import com.offlinemaps.core.map.CameraTarget
import com.offlinemaps.core.map.MapStyleFactory
import com.offlinemaps.core.map.OverlayLayerPlan
import com.offlinemaps.core.map.RouteLineSimplifier
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdate
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.Layer
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

    /** Where the last [moveTo] asked the camera to go, so an unfinished animation is not skipped. */
    private var lastIssuedTarget: CameraTarget? = null

    /** The animated move this object started and has not seen the end of (see [moveTo]). */
    private val animations = CameraAnimationTracker()

    /** Animated moves that replaced one that had not landed yet; diagnostics only. */
    val interruptedAnimations: Int get() = animations.interrupted

    /** Camera moves dropped because they would not have moved the picture; diagnostics only. */
    var skippedMoves: Int = 0
        private set

    /** Points the last drawn route line did not need; diagnostics only. */
    var droppedRoutePoints: Int = 0
        private set

    /** Overlay sources this controller has added across the styles it has seen; diagnostics only. */
    var overlaySourcesAdded: Int = 0
        private set

    /** Overlay layers this controller has added across the styles it has seen; diagnostics only. */
    var overlayLayersAdded: Int = 0
        private set

    fun onStyleLoaded(style: Style) {
        this.style = style
        // The overlay sources are (re)created empty below, so nothing is cached for them yet.
        payloads.reset()
        puckGate.reset()
        // The new style carries whatever visibility its document was generated with, which this
        // object cannot know, so the next call must write rather than be skipped as a repeat.
        buildingsVisible = null
        // The camera may have moved while the style was loading, so nothing is known about it.
        lastIssuedTarget = null
        // A flight started on the previous style has nothing left to animate onto.
        cancelInFlightAnimation()
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

    /**
     * Moves the camera. [animate] moves run an animation on the renderer; one that would land
     * within a few pixels of where the camera already is and change nothing else is skipped
     * outright, because the frames it would spend are the frames of an invisible transition (see
     * [CameraMovePolicy]). A snap ([animate] = false) is always applied - it is used to place the
     * camera the first time a style loads, where "already close enough" cannot be assumed.
     */
    fun moveTo(
        target: GeoPoint,
        zoom: Double,
        tilt: Float,
        bearing: Float,
        animate: Boolean = true,
        durationMs: Int = 650,
    ) {
        if (animate && isRedundantMove(target, zoom, tilt, bearing)) {
            skippedMoves++
            return
        }
        val position = CameraPosition.Builder()
            .target(LatLng(target.latitude, target.longitude))
            .zoom(zoom)
            .tilt(tilt.toDouble())
            .bearing(bearing.toDouble())
            .build()
        val update = CameraUpdateFactory.newCameraPosition(position)
        lastIssuedTarget = CameraTarget(target.latitude, target.longitude, zoom, tilt.toDouble(), bearing.toDouble())
        // A move issued while another is still flying replaces it rather than queueing behind it:
        // the old flight is stopped first, so the new one starts from where the camera actually is
        // instead of from a frame the user never asked for. A snap does this itself.
        if (animate) {
            cancelInFlightAnimation()
            animateCamera(update, durationMs)
        } else {
            map.moveCamera(update)
            animations.reset()
        }
    }

    /**
     * True when an animated move to the requested camera cannot change the picture.
     *
     * [CameraMovePolicy] answers that from the camera's current position, but the current position
     * of a map mid-animation is the interpolated frame, not a resting place: a request that happens
     * to sit within a few pixels of it would otherwise be dropped while the previous animation went
     * on to finish somewhere else. So a move is only dropped when the last move this object issued
     * has already landed (the camera is at that target), which is also true when no move was ever
     * issued. A camera the user panned away is not at the issued target, so the gate stays open and
     * the move is made as before.
     */
    private fun isRedundantMove(target: GeoPoint, zoom: Double, tilt: Float, bearing: Float): Boolean {
        val requested = CameraTarget(target.latitude, target.longitude, zoom, tilt.toDouble(), bearing.toDouble())
        val current = currentCameraTarget() ?: return false
        val issued = lastIssuedTarget
        if (issued != null && !CameraMovePolicy.isRedundant(current, issued)) return false
        return CameraMovePolicy.isRedundant(current, requested)
    }

    /** The camera as it is drawn right now, or null when MapLibre has no target yet. */
    private fun currentCameraTarget(): CameraTarget? {
        val position = map.cameraPosition
        val target = position.target ?: return null
        return CameraTarget(
            latitude = target.latitude,
            longitude = target.longitude,
            zoom = position.zoom,
            tilt = position.tilt,
            bearing = position.bearing,
        )
    }

    fun zoomBy(delta: Double, animate: Boolean = true) {
        val update = CameraUpdateFactory.zoomBy(delta)
        if (animate) {
            // Rapid taps on the zoom buttons: each replaces the previous 250 ms flight.
            cancelInFlightAnimation()
            animateCamera(update, ZOOM_DURATION_MS)
        } else {
            map.moveCamera(update)
            animations.reset()
        }
    }

    /**
     * Runs [update] as a tracked animation, so the next move can tell whether one is still flying.
     *
     * The token is what makes this safe: MapLibre reports the end of a cancelled flight on the main
     * looper, i.e. after the move that replaced it has already started, and only the callback whose
     * token is still current is allowed to end the animation.
     */
    private fun animateCamera(update: CameraUpdate, durationMs: Int) {
        val token = animations.start()
        map.animateCamera(update, durationMs, object : MapLibreMap.CancelableCallback {
            override fun onFinish() = animations.finish(token)
            override fun onCancel() = animations.cancel(token)
        })
    }

    /**
     * Stops the animation in flight, if any, and retires its token so the cancel callback MapLibre
     * posts afterwards cannot end the animation that replaces it.
     */
    private fun cancelInFlightAnimation() {
        if (!animations.isInFlight) return
        map.cancelTransitions()
        animations.reset()
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
     *
     * What is serialised is the line as it is drawn: [RouteLineSimplifier] thins a long plan to the
     * points that carry the shape, within a metre of the original, so a motorway run of a few
     * thousand coordinates becomes a few dozen `String.format` calls instead of a few thousand.
     * Only this payload is thinned - the plan itself is untouched, so the cumulative distances, the
     * manoeuvre indices and the navigation tracker keep indexing the full-resolution geometry. The
     * key stays the plan's own list, so an unchanged plan is still recognised as unchanged.
     */
    fun setRoute(points: List<GeoPoint>) {
        val currentStyle = style ?: return
        val source = currentStyle.getSourceAs<GeoJsonSource>(ROUTE_SOURCE) ?: return
        if (points.size < 2) {
            push(source, ROUTE_SOURCE, EMPTY_ROUTE_KEY) { EMPTY_FEATURES }
            return
        }
        push(source, ROUTE_SOURCE, points) {
            val drawn = RouteLineSimplifier.simplify(points)
            droppedRoutePoints = points.size - drawn.size
            val coordinates = drawn.joinToString(",") { "[${coord(it)}]" }
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
        // Nothing should keep flying onto a map that is being torn down.
        cancelInFlightAnimation()
        style = null
        payloads.reset()
        puckGate.reset()
        lastIssuedTarget = null
    }

    /** Pushes [build]'s document to [source] unless the payload for [key] is already in place. */
    private fun push(source: GeoJsonSource, sourceId: String, key: Any?, build: () -> String) {
        val payload = payloads.payloadFor(sourceId, key, build) ?: return
        source.setGeoJson(payload)
    }

    /**
     * Gives [currentStyle] the overlay sources and layers it is missing.
     *
     * [OverlayLayerPlan.missing] decides what to add by asking the live style about each declared
     * id, so this is idempotent: a style that already carries the overlays costs five lookups and
     * no native calls. That is what keeps a route from leaking sources - routing and clearing a
     * route never add anything, they only write a payload into the route source that is already
     * there (see [setRoute]). The old code returned as soon as the puck source existed, which left
     * a style that kept one overlay without the others permanently unable to draw a route.
     */
    private fun ensureOverlayLayers(currentStyle: Style) {
        val install = OverlayLayerPlan.missing(
            sourceExists = { currentStyle.getSource(it) != null },
            layerExists = { currentStyle.getLayer(it) != null },
        )
        if (install.isEmpty) return
        install.sources.forEach { currentStyle.addSource(GeoJsonSource(it, EMPTY_FEATURES)) }
        overlaySourcesAdded += install.sources.size
        install.layers.forEach { currentStyle.addLayer(buildOverlayLayer(it)) }
        overlayLayersAdded += install.layers.size
    }

    /** The overlay layer [spec] describes, with the properties it has always had. */
    private fun buildOverlayLayer(spec: OverlayLayerPlan.Layer): Layer = when (spec.id) {
        OverlayLayerPlan.ACCURACY_LAYER ->
            CircleLayer(spec.id, spec.sourceId).apply {
                setFilter(Expression.eq(Expression.get("kind"), Expression.literal("accuracy")))
                withProperties(
                    PropertyFactory.circleRadius(Expression.get("radius")),
                    PropertyFactory.circleColor("#2D7FF9"),
                    PropertyFactory.circleOpacity(0.14f),
                )
            }

        OverlayLayerPlan.USER_LAYER ->
            CircleLayer(spec.id, spec.sourceId).apply {
                setFilter(Expression.eq(Expression.get("kind"), Expression.literal("puck")))
                withProperties(
                    PropertyFactory.circleRadius(7f),
                    PropertyFactory.circleColor("#2D7FF9"),
                    PropertyFactory.circleStrokeWidth(3f),
                    PropertyFactory.circleStrokeColor("#FFFFFF"),
                )
            }

        OverlayLayerPlan.ROUTE_CASING ->
            LineLayer(spec.id, spec.sourceId).withProperties(
                PropertyFactory.lineColor("#0B3D2E"),
                PropertyFactory.lineWidth(9f),
                PropertyFactory.lineOpacity(0.55f),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
            )

        OverlayLayerPlan.ROUTE_LAYER ->
            LineLayer(spec.id, spec.sourceId).withProperties(
                PropertyFactory.lineColor("#2ECC71"),
                PropertyFactory.lineWidth(5.5f),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
            )

        OverlayLayerPlan.DESTINATION_LAYER ->
            CircleLayer(spec.id, spec.sourceId).withProperties(
                PropertyFactory.circleRadius(8f),
                PropertyFactory.circleColor("#E03E3E"),
                PropertyFactory.circleStrokeWidth(3f),
                PropertyFactory.circleStrokeColor("#FFFFFF"),
            )

        else -> throw IllegalArgumentException("unknown overlay layer ${spec.id}")
    }

    /** Value key for the location puck: the position plus the ring radius derived from accuracy. */
    private data class UserPuckKey(val point: GeoPoint, val radius: Float)

    private fun coord(point: GeoPoint): String =
        String.format(Locale.US, "%.6f,%.6f", point.longitude, point.latitude)

    private companion object {
        /** One list of overlay ids, shared with the install decision (see [OverlayLayerPlan]). */
        const val USER_SOURCE = OverlayLayerPlan.USER_SOURCE
        const val ROUTE_SOURCE = OverlayLayerPlan.ROUTE_SOURCE
        const val DESTINATION_SOURCE = OverlayLayerPlan.DESTINATION_SOURCE
        /** Emitted by the style generator in every document; only its visibility ever changes. */
        val BUILDING_LAYER = MapStyleFactory.BUILDING_3D_LAYER_ID
        const val EMPTY_FEATURES = """{"type":"FeatureCollection","features":[]}"""

        /** Duration of the zoom-button animation, as before. */
        const val ZOOM_DURATION_MS = 250

        /** Canonical keys for the "nothing to draw" payload, so hiding a source is also deduped. */
        val HIDDEN_PUCK = Any()
        val HIDDEN_DESTINATION = Any()
        val EMPTY_ROUTE_KEY: List<GeoPoint> = emptyList()
    }
}
