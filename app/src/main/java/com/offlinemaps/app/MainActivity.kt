package com.offlinemaps.app

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.offlinemaps.app.di.ServiceLocator
import com.offlinemaps.app.map.MapCanvas
import com.offlinemaps.app.map.MapController
import com.offlinemaps.app.service.NavigationService
import com.offlinemaps.app.ui.AppScaffold
import com.offlinemaps.app.ui.MapViewModel
import com.offlinemaps.app.ui.MapViewModelFactory
import com.offlinemaps.app.ui.Tab
import com.offlinemaps.core.ui.OfflineMapsTheme

class MainActivity : ComponentActivity() {

    private lateinit var locator: ServiceLocator

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        locator = (application as OfflineMapsApp).locator

        setContent {
            val factory = remember { MapViewModelFactory(locator) }
            val mapViewModel: MapViewModel = viewModel(factory = factory)
            val state by mapViewModel.state.collectAsState()

            OfflineMapsTheme(theme = state.theme) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AppRoot(locator = locator, mapViewModel = mapViewModel)
                }
            }
        }
    }
}

@Composable
private fun AppRoot(locator: ServiceLocator, mapViewModel: MapViewModel) {
    val state by mapViewModel.state.collectAsState()
    val navigation by mapViewModel.navigation.collectAsState()
    var tab by remember { mutableStateOf(Tab.EXPLORE) }
    var controller by remember { mutableStateOf<MapController?>(null) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        mapViewModel.refreshGpsState()
        if (result.values.any { it }) mapViewModel.startLocationUpdates()
    }

    AppScaffold(
        state = state,
        navigation = navigation,
        tab = tab,
        onTabChange = { tab = it },
        mapViewModel = mapViewModel,
        locator = locator,
        onRequestLocationPermission = {
            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                ),
            )
        },
        onStartNavigationService = { NavigationService.start(locator.appContext) },
        onStopNavigationService = { NavigationService.stop(locator.appContext) },
        mapContent = { modifier ->
            MapCanvas(
                styleJson = state.styleJson,
                theme = state.theme,
                modifier = modifier,
                onMapReady = { mapController ->
                    controller = mapController
                    mapController.moveTo(state.cameraTarget, state.zoom, state.tilt, state.bearing, animate = false)
                },
                onCameraIdle = { controller?.cameraState()?.let(mapViewModel::onCameraChanged) },
                onMapClick = { _, _ -> },
            )
        },
        controller = controller,
    )
}
