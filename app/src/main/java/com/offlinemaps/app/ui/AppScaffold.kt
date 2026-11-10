package com.offlinemaps.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.offlinemaps.app.di.ServiceLocator
import com.offlinemaps.app.map.MapController
import com.offlinemaps.app.nav.NavigationSessionUi
import com.offlinemaps.core.common.MapMode
import com.offlinemaps.core.routing.RouteResult
import com.offlinemaps.core.ui.MapCircleButton
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import com.offlinemaps.core.ui.glassSurface
import androidx.compose.foundation.layout.Row
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class Tab(val label: String, val icon: ImageVector) {
    EXPLORE("Explore", Icons.Filled.Explore),
    NAVIGATE("Navigate", Icons.Filled.Navigation),
    DOWNLOADS("Downloads", Icons.Filled.Download),
    SAVED("Saved", Icons.Filled.Bookmark),
    SETTINGS("Settings", Icons.Filled.Settings),
}

/**
 * The single screen of the app: the map fills the window and every other surface floats above it.
 *
 * Keeping the map mounted across tab changes matters - MapLibre re-initialises its native renderer
 * on every new MapView, which would cost a second or more each time the user switched tabs.
 */
@Composable
fun AppScaffold(
    state: MapUiState,
    navigation: NavigationSessionUi?,
    tab: Tab,
    onTabChange: (Tab) -> Unit,
    mapViewModel: MapViewModel,
    locator: ServiceLocator,
    onRequestLocationPermission: () -> Unit,
    onStartNavigationService: () -> Unit,
    onStopNavigationService: () -> Unit,
    mapContent: @Composable (Modifier) -> Unit,
    controller: MapController?,
) {
    val statusBarPadding = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val navBarPadding = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    var showPermissionDialog by remember { mutableStateOf(false) }

    // The explore stream is registered only while this screen is started (OPTIMIZATION.md G-04). A
    // map in a pocket cannot show a fix, so the GPS radio is released with the screen and comes
    // back with it. The first sync reads the owner's current state, so a screen composed while it
    // is stopped does not hold a registration for a screen nobody can see.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        mapViewModel.onScreenVisibilityChanged(
            lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED),
        )
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapViewModel.onScreenVisibilityChanged(true)
                Lifecycle.Event.ON_STOP -> mapViewModel.onScreenVisibilityChanged(false)
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Keep the native camera and overlay layers in sync with UI state.
    LaunchedEffect(state.mode, state.quality) {
        controller?.moveTo(
            target = state.cameraTarget,
            zoom = state.zoom,
            tilt = if (state.mode == MapMode.PERSPECTIVE_3D) state.quality.maxPitch else 0f,
            bearing = state.bearing,
        )
        controller?.setBuildingsVisible(state.buildingsEnabled && state.mode == MapMode.PERSPECTIVE_3D)
    }
    LaunchedEffect(state.userLocation) {
        controller?.setUserLocation(state.userLocation, state.userAccuracy)
    }
    LaunchedEffect(state.destination) {
        controller?.setDestination(state.destination?.point)
    }
    LaunchedEffect(state.destination, state.profile) {
        // Preview geometry comes from the routing engine, not from a straight line.
        val destination = state.destination
        if (destination == null) {
            controller?.setRoute(emptyList())
        } else {
            val origin = mapViewModel.currentLocation() ?: state.cameraTarget
            // calculateRoute suspends off the main thread: the routing graph may have to be read
            // back from disk first (OPTIMIZATION.md E-01). The controller call stays on main.
            val geometry = withContext(Dispatchers.Default) {
                when (val result = locator.navigationManager.calculateRoute(origin, destination.point, state.profile)) {
                    is RouteResult.Success -> result.plan.geometry
                    is RouteResult.Failure -> emptyList()
                }
            }
            controller?.setRoute(geometry)
        }
    }
    LaunchedEffect(navigation?.plan) {
        navigation?.let { controller?.setRoute(it.plan.geometry) }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        mapContent(Modifier.fillMaxSize())

        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .padding(top = statusBarPadding + 8.dp, start = 12.dp, end = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ExploreSearchBar(
                state = state,
                onSearchClick = { onTabChange(Tab.EXPLORE) },
                onQueryChange = mapViewModel::setSearchQuery,
                onResultClick = { place ->
                    mapViewModel.selectDestination(place)
                    controller?.moveTo(place.point, maxOf(state.zoom, 14.0), state.tilt, state.bearing)
                },
                onClear = mapViewModel::clearSearch,
            )
        }

        if (navigation == null) {
            MapControls(
                state = state,
                modifier = Modifier.align(Alignment.CenterEnd).padding(end = 12.dp),
                onToggleMode = mapViewModel::toggleMode,
                onZoomIn = { controller?.zoomBy(1.0) },
                onZoomOut = { controller?.zoomBy(-1.0) },
                onResetBearing = {
                    mapViewModel.setBearing(0f)
                    controller?.moveTo(state.cameraTarget, state.zoom, state.tilt, 0f)
                },
                onMyLocation = {
                    if (state.hasLocationPermission) {
                        mapViewModel.startLocationUpdates()
                        mapViewModel.currentLocation()?.let { point ->
                            controller?.moveTo(point, 15.5, state.tilt, state.bearing)
                        }
                    } else {
                        showPermissionDialog = true
                    }
                },
            )
        }

        Column(modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
            AnimatedVisibility(
                visible = navigation != null,
                enter = slideInVertically { it },
                exit = slideOutVertically { it },
            ) {
                navigation?.let { session ->
                    NavigationTurnCard(
                        session = session,
                        onEnd = {
                            mapViewModel.stopNavigation()
                            onStopNavigationService()
                        },
                        onMuteToggle = { locator.navigationManager.setMuted(!session.muted) },
                        onRepeat = { locator.navigationManager.repeatLastInstruction() },
                    )
                }
            }

            when (tab) {
                Tab.EXPLORE -> ExploreBottomPanel(
                    state = state,
                    onStartNavigation = {
                        mapViewModel.startNavigation()
                        onStartNavigationService()
                    },
                    onSavePlace = { label ->
                        state.destination?.let { place -> mapViewModel.savePlace(place.name, label, place.point) }
                    },
                    onInstallSample = mapViewModel::installBundledSample,
                    onDismissDestination = mapViewModel::clearDestination,
                    onProfileChange = mapViewModel::setProfile,
                )
                Tab.NAVIGATE -> NavigatePanel(
                    state = state,
                    navigation = navigation,
                    onProfileChange = mapViewModel::setProfile,
                    onStart = {
                        mapViewModel.startNavigation()
                        onStartNavigationService()
                    },
                    onEnd = {
                        mapViewModel.stopNavigation()
                        onStopNavigationService()
                    },
                )
                Tab.DOWNLOADS -> DownloadsPanel(locator = locator)
                Tab.SAVED -> SavedPanel(locator = locator, onGoTo = { place ->
                    controller?.moveTo(place, 15.0, state.tilt, state.bearing)
                    onTabChange(Tab.EXPLORE)
                })
                Tab.SETTINGS -> SettingsPanel(locator = locator)
            }

            GlassBottomBar(
                selected = tab,
                onSelect = onTabChange,
                bottomInset = navBarPadding,
            )
        }
    }

    if (showPermissionDialog) {
        LocationPermissionDialog(
            onConfirm = {
                showPermissionDialog = false
                onRequestLocationPermission()
            },
            onDismiss = { showPermissionDialog = false },
        )
    }
}

@Composable
private fun MapControls(
    state: MapUiState,
    modifier: Modifier = Modifier,
    onToggleMode: () -> Unit,
    onZoomIn: () -> Unit,
    onZoomOut: () -> Unit,
    onResetBearing: () -> Unit,
    onMyLocation: () -> Unit,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(10.dp),
        horizontalAlignment = Alignment.End,
    ) {
        MapCircleButton(
            icon = Icons.Filled.Navigation,
            contentDescription = if (state.mode == MapMode.PERSPECTIVE_3D) "Switch to 2D" else "Switch to 3D",
            active = state.mode == MapMode.PERSPECTIVE_3D,
            onClick = onToggleMode,
            badge = if (state.mode == MapMode.PERSPECTIVE_3D) "3D" else "2D",
        )
        MapCircleButton(Icons.Filled.Explore, "Reset north", onResetBearing)
        MapCircleButton(Icons.Filled.Add, "Zoom in", onZoomIn)
        MapCircleButton(Icons.Filled.Remove, "Zoom out", onZoomOut)
        MapCircleButton(
            icon = Icons.Filled.MyLocation,
            contentDescription = "Go to my location",
            onClick = onMyLocation,
            active = state.hasLocationPermission,
        )
    }
}

/**
 * Glass bottom navigation with a pill that springs between tabs.
 *
 * The pill is a single animated box rather than a per-item background, which is what makes the
 * movement read as one object sliding; the width overshoots slightly while it travels so the motion
 * has weight, then settles. Springs (not tweens) so an interrupted tap retargets smoothly.
 */
@Composable
private fun GlassBottomBar(
    selected: Tab,
    onSelect: (Tab) -> Unit,
    bottomInset: androidx.compose.ui.unit.Dp,
) {
    val entries = Tab.entries
    val density = androidx.compose.ui.platform.LocalDensity.current
    val index by androidx.compose.animation.core.animateFloatAsState(
        targetValue = entries.indexOf(selected).toFloat(),
        animationSpec = androidx.compose.animation.core.spring(
            dampingRatio = androidx.compose.animation.core.Spring.DampingRatioMediumBouncy,
            stiffness = androidx.compose.animation.core.Spring.StiffnessMediumLow,
        ),
        label = "tabIndex",
    )
    // Stretch while in transit: 1.0 when settled, wider mid-flight.
    val settled = kotlin.math.abs(index - index.toInt().toFloat()) < 0.02f
    val stretch by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (settled) 1f else 1.22f,
        animationSpec = androidx.compose.animation.core.spring(
            dampingRatio = androidx.compose.animation.core.Spring.DampingRatioMediumBouncy,
            stiffness = androidx.compose.animation.core.Spring.StiffnessHigh,
        ),
        label = "tabStretch",
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.Transparent)
            .padding(horizontal = 10.dp, vertical = 8.dp)
            .glassSurface(shape = RoundedCornerShape(24.dp), elevation = 14.dp),
    ) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val itemWidth = maxWidth / entries.size
            Box(
                modifier = Modifier
                    .offset(x = itemWidth * index)
                    .width(itemWidth * stretch)
                    .height(52.dp)
                    .align(Alignment.CenterStart)
                    .glassSurface(
                        shape = RoundedCornerShape(18.dp),
                        elevation = 0.dp,
                        tint = MaterialTheme.colorScheme.primary,
                    ),
            )
            Row(Modifier.fillMaxWidth()) {
                entries.forEach { entry ->
                    val active = entry == selected
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .height(52.dp)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                            ) { onSelect(entry) },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Icon(
                            entry.icon,
                            contentDescription = entry.label,
                            modifier = Modifier.size(22.dp),
                            tint = if (active) MaterialTheme.colorScheme.onPrimary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            entry.label,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (active) MaterialTheme.colorScheme.onPrimary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
    Spacer(Modifier.height(bottomInset))
}
