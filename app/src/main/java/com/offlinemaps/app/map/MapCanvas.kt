package com.offlinemaps.app.map

import android.os.Bundle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.offlinemaps.app.StartupTrace
import com.offlinemaps.core.common.MapTheme
import com.offlinemaps.core.map.MapStyleFactory
import com.offlinemaps.core.map.StyleOptions
import org.maplibre.android.maps.MapView

/**
 * Hosts the MapLibre [MapView] inside Compose.
 *
 * The MapView is created exactly once per composition and its lifecycle is forwarded manually,
 * which is what MapLibre requires - recreating it on every recomposition would leak native
 * renderer resources.
 *
 * Nothing here happens before the view is attached: MapLibre is initialised in the factory, and the
 * style document is generated from [styleOptions] only when a style is actually needed (first
 * attach, or when the options really changed). Recomposition passes a small [StyleOptions] value
 * instead of a freshly serialised style string, so the JSON is not built on the main thread for
 * state changes that cannot affect it.
 */
@Composable
fun MapCanvas(
    styleOptions: StyleOptions,
    theme: MapTheme,
    onMapReady: (MapController) -> Unit,
    onCameraIdle: () -> Unit,
    onMapClick: (Double, Double) -> Unit,
    modifier: Modifier = Modifier,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val bundle = remember { Bundle() }
    val mapViewRef = remember { arrayOfNulls<MapView>(1) }
    val controllerRef = remember { arrayOfNulls<MapController>(1) }
    val optionsRef = remember { arrayOfNulls<StyleOptions>(1) }
    val appliedRef = remember { arrayOfNulls<StyleOptions>(1) }
    optionsRef[0] = styleOptions

    AndroidView(
        modifier = modifier,
        factory = { context ->
            // MapLibre needs its process-wide initialisation before the first MapView exists, so
            // this is the earliest correct point - and the latest possible one. It used to run in
            // Application.onCreate, before the map was needed at all.
            MapLibreBootstrap.ensureInitialised(context)
            MapView(context).apply {
                onCreate(bundle)
                mapViewRef[0] = this
                getMapAsync { map ->
                    val controller = MapController(map)
                    controllerRef[0] = controller
                    map.uiSettings.isCompassEnabled = false
                    map.uiSettings.isRotateGesturesEnabled = true
                    map.uiSettings.isTiltGesturesEnabled = true
                    map.uiSettings.isZoomGesturesEnabled = true
                    map.uiSettings.isAttributionEnabled = true
                    map.addOnCameraIdleListener { onCameraIdle() }
                    map.addOnMapClickListener { latLng ->
                        onMapClick(latLng.latitude, latLng.longitude)
                        true
                    }
                    val options = optionsRef[0] ?: styleOptions
                    appliedRef[0] = options
                    controller.applyStyle(MapStyleFactory.buildJson(options), theme)
                    StartupTrace.process.firstStyleApplied()
                    onMapReady(controller)
                }
            }
        },
        update = { _ ->
            // The style is only rebuilt and re-applied when the options that produce it change.
            val controller = controllerRef[0] ?: return@AndroidView
            val pending = optionsRef[0] ?: return@AndroidView
            if (pending != appliedRef[0]) {
                appliedRef[0] = pending
                controller.applyStyle(MapStyleFactory.buildJson(pending), theme)
            }
        },
    )

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            val view = mapViewRef[0] ?: return@LifecycleEventObserver
            when (event) {
                Lifecycle.Event.ON_START -> view.onStart()
                Lifecycle.Event.ON_RESUME -> view.onResume()
                Lifecycle.Event.ON_PAUSE -> view.onPause()
                Lifecycle.Event.ON_STOP -> view.onStop()
                Lifecycle.Event.ON_DESTROY -> {
                    controllerRef[0]?.destroy()
                    view.onDestroy()
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
}
