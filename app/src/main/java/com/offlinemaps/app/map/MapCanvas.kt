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
import com.offlinemaps.core.common.MapTheme
import org.maplibre.android.maps.MapView

/**
 * Hosts the MapLibre [MapView] inside Compose.
 *
 * The MapView is created exactly once per composition and its lifecycle is forwarded manually,
 * which is what MapLibre requires - recreating it on every recomposition would leak native
 * renderer resources.
 */
@Composable
fun MapCanvas(
    styleJson: String,
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
    val styleRef = remember { arrayOfNulls<String>(1) }
    val appliedRef = remember { arrayOfNulls<String>(1) }
    styleRef[0] = styleJson

    AndroidView(
        modifier = modifier,
        factory = { context ->
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
                    appliedRef[0] = styleRef[0]
                    controller.applyStyle(styleRef[0] ?: "{}", theme)
                    onMapReady(controller)
                }
            }
        },
        update = { view ->
            // The style is only re-applied when the generated document actually changes.
            val controller = controllerRef[0]
            val pending = styleRef[0]
            if (controller != null && pending != null && pending != appliedRef[0]) {
                appliedRef[0] = pending
                controller.applyStyle(pending, theme)
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
