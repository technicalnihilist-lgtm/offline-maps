package com.offlinemaps.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.offlinemaps.app.di.ServiceLocator
import com.offlinemaps.app.install.PackageInstaller
import com.offlinemaps.app.map.MapCameraState
import com.offlinemaps.app.nav.NavigationSessionUi
import com.offlinemaps.core.common.ConnectivityState
import com.offlinemaps.core.common.Formatters
import com.offlinemaps.core.common.GeoBounds
import com.offlinemaps.core.common.GeoPoint
import com.offlinemaps.core.common.GraphicsQuality
import com.offlinemaps.core.common.LocationFix
import com.offlinemaps.core.common.MapMode
import com.offlinemaps.core.common.MapPackageType
import com.offlinemaps.core.common.MapTheme
import com.offlinemaps.core.common.PlaceResult
import com.offlinemaps.core.common.SavedPlaceLabel
import com.offlinemaps.core.common.TravelProfile
import com.offlinemaps.core.data.InstalledRegion
import com.offlinemaps.core.data.RecentSearch
import com.offlinemaps.core.data.SavedPlace
import com.offlinemaps.core.map.MapStyleFactory
import com.offlinemaps.core.map.StyleOptions
import com.offlinemaps.core.routing.RouteResult
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class MapUiState(
    val theme: MapTheme = MapTheme.LIGHT,
    val quality: GraphicsQuality = GraphicsQuality.MEDIUM,
    val mode: MapMode = MapMode.PERSPECTIVE_3D,
    val terrainEnabled: Boolean = true,
    val buildingsEnabled: Boolean = true,
    val zoom: Double = 2.4,
    val tilt: Float = 0f,
    val bearing: Float = 0f,
    val cameraTarget: GeoPoint = GeoPoint(20.0, 10.0),
    val userLocation: GeoPoint? = null,
    val userAccuracy: Float? = null,
    val hasLocationPermission: Boolean = false,
    val gpsEnabled: Boolean = false,
    val connectivity: ConnectivityState = ConnectivityState.UNKNOWN,
    val installedRegions: List<InstalledRegion> = emptyList(),
    val hasRoutingData: Boolean = false,
    val searchQuery: String = "",
    val searchResults: List<PlaceResult> = emptyList(),
    val searchNote: String? = null,
    val searching: Boolean = false,
    val recentSearches: List<RecentSearch> = emptyList(),
    val savedPlaces: List<SavedPlace> = emptyList(),
    val destination: PlaceResult? = null,
    val routeSummary: String? = null,
    val routeMessage: String? = null,
    val profile: TravelProfile = TravelProfile.CAR,
    val installingSample: Boolean = false,
    val sampleMessage: String? = null,
) {
    val styleJson: String
        get() = MapStyleFactory.buildJson(
            StyleOptions(
                theme = theme,
                show3DBuildings = buildingsEnabled,
                showTerrain = terrainEnabled,
                buildingsMinZoom = quality.buildingsMinZoom,
                maxTerrainZoom = 12,
            ),
        )

    /** Honest one-line coverage statement shown under the search bar. */
    val coverageChip: String
        get() = when {
            installedRegions.isEmpty() -> "No offline maps yet"
            hasRoutingData -> "${installedRegions.size} region(s) offline · routing ready"
            else -> "${installedRegions.size} region(s) offline · no routing data"
        }
}

/**
 * One view model for the map surface and the things that act on it (search, route preview,
 * installing the bundled sample). Downloads, saved places and settings have their own view models.
 */
class MapViewModel(private val locator: ServiceLocator) : ViewModel() {

    private val _state = MutableStateFlow(MapUiState())
    val state: StateFlow<MapUiState> = _state.asStateFlow()

    val navigation: StateFlow<NavigationSessionUi?> = locator.navigationManager.session

    private var lastFix: LocationFix? = null
    private var locationJob: Job? = null

    init {
        viewModelScope.launch {
            locator.settings.settings.collect { settings ->
                _state.update {
                    it.copy(
                        theme = settings.theme,
                        quality = settings.quality,
                        terrainEnabled = settings.terrainEnabled,
                        buildingsEnabled = settings.buildings3DEnabled,
                        profile = settings.travelProfile,
                    )
                }
            }
        }
        viewModelScope.launch {
            locator.installedRegions.observe().collect { regions ->
                _state.update { it.copy(installedRegions = regions) }
            }
        }
        viewModelScope.launch {
            locator.network.status.collect { status ->
                _state.update {
                    it.copy(connectivity = if (status.online) ConnectivityState.ONLINE else ConnectivityState.OFFLINE)
                }
            }
        }
        viewModelScope.launch {
            locator.recentSearches.observe(12).collect { recent -> _state.update { it.copy(recentSearches = recent) } }
        }
        viewModelScope.launch {
            locator.savedPlaces.observe().collect { saved -> _state.update { it.copy(savedPlaces = saved) } }
        }
        refreshGpsState()
        refreshRoutingAvailability()
    }

    fun refreshGpsState() {
        _state.update {
            it.copy(
                hasLocationPermission = locator.locationSource.hasPermission(),
                gpsEnabled = locator.locationSource.isGpsEnabled(),
            )
        }
    }

    /** Starts streaming position updates. Safe to call repeatedly. */
    fun startLocationUpdates() {
        if (locationJob != null) return
        if (!locator.locationSource.hasPermission()) {
            refreshGpsState()
            return
        }
        locator.locationSource.lastKnown()?.let { fix ->
            lastFix = fix
            _state.update { it.copy(userLocation = fix.point, userAccuracy = fix.accuracyMeters, gpsEnabled = true) }
        }
        locationJob = viewModelScope.launch {
            locator.locationSource.updates(minTimeMs = 4_000L, minDistanceMeters = 8f).collect { fix ->
                lastFix = fix
                _state.update {
                    it.copy(
                        userLocation = fix.point,
                        userAccuracy = fix.accuracyMeters,
                        hasLocationPermission = true,
                        gpsEnabled = true,
                    )
                }
            }
        }
    }

    fun stopLocationUpdates() {
        locationJob?.cancel()
        locationJob = null
    }

    fun currentLocation(): GeoPoint? = lastFix?.point ?: _state.value.userLocation

    fun onCameraChanged(camera: MapCameraState) {
        _state.update {
            it.copy(zoom = camera.zoom, tilt = camera.tilt, bearing = camera.bearing, cameraTarget = camera.target)
        }
    }

    fun toggleMode() {
        _state.update { current ->
            val next = if (current.mode == MapMode.PERSPECTIVE_3D) MapMode.FLAT_2D else MapMode.PERSPECTIVE_3D
            current.copy(mode = next, tilt = if (next == MapMode.PERSPECTIVE_3D) current.quality.maxPitch else 0f)
        }
    }

    fun setBearing(bearing: Float) = _state.update { it.copy(bearing = bearing) }

    fun toggleTerrain() {
        val enabled = !_state.value.terrainEnabled
        locator.settings.update { it.copy(terrainEnabled = enabled) }
    }

    fun setSearchQuery(query: String) {
        _state.update { it.copy(searchQuery = query) }
        if (query.trim().length < 2) {
            _state.update { it.copy(searchResults = emptyList(), searchNote = null) }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(searching = true) }
            val results = locator.search.search(query)
            _state.update {
                it.copy(searchResults = results, searchNote = locator.search.lastCoverageNote, searching = false)
            }
        }
    }

    fun clearSearch() = _state.update {
        it.copy(searchQuery = "", searchResults = emptyList(), searchNote = null)
    }

    fun selectDestination(place: PlaceResult) {
        _state.update { it.copy(destination = place, routeSummary = null, routeMessage = null) }
        viewModelScope.launch { locator.recentSearches.record(place.name, place.name, place.point) }
        previewRoute()
    }

    fun clearDestination() = _state.update { it.copy(destination = null, routeSummary = null, routeMessage = null) }

    fun setProfile(profile: TravelProfile) {
        locator.settings.update { it.copy(travelProfile = profile) }
        _state.update { it.copy(profile = profile) }
        if (_state.value.destination != null) previewRoute()
    }

    /** Calculates a route from the current position (or the map centre) to the destination. */
    fun previewRoute() {
        val destination = _state.value.destination ?: return
        val origin = currentLocation() ?: _state.value.cameraTarget
        viewModelScope.launch {
            when (val result = locator.navigationManager.calculateRoute(origin, destination.point, _state.value.profile)) {
                is RouteResult.Success -> _state.update {
                    it.copy(
                        routeSummary = "${Formatters.distance(result.plan.distanceMeters)} · " +
                            "${Formatters.duration(result.plan.durationSeconds)} · " +
                            "${result.plan.maneuvers.size} steps",
                        routeMessage = null,
                    )
                }
                is RouteResult.Failure -> _state.update { it.copy(routeSummary = null, routeMessage = result.message) }
            }
        }
    }

    fun startNavigation() {
        val destination = _state.value.destination ?: return
        val voice = locator.settings.current().voiceGuidanceEnabled
        val result = locator.navigationManager.start(destination, _state.value.profile, voiceEnabled = voice)
        _state.update { it.copy(routeMessage = result.exceptionOrNull()?.message) }
    }

    fun stopNavigation() = locator.navigationManager.stop()

    fun savePlace(name: String, label: SavedPlaceLabel, point: GeoPoint) {
        viewModelScope.launch { locator.savedPlaces.save(SavedPlace(name = name, label = label, point = point)) }
    }

    fun installBundledSample() {
        if (_state.value.installingSample) return
        viewModelScope.launch {
            _state.update { it.copy(installingSample = true, sampleMessage = null) }
            val installer = PackageInstaller(locator.appContext, locator)
            val result = installer.installBundledSample { phase ->
                _state.update { it.copy(sampleMessage = phase) }
            }
            when (result) {
                is PackageInstaller.InstallResult.Success -> {
                    locator.installedRegions.save(
                        InstalledRegion(
                            regionId = PackageInstaller.BUNDLED_REGION_ID,
                            name = "Andorra (bundled sample)",
                            type = MapPackageType.ROUTING_OSM,
                            sizeBytes = 553_000L,
                            version = "bundled",
                            installedAt = System.currentTimeMillis(),
                            bounds = GeoBounds(42.42, 1.41, 42.66, 1.79),
                            minZoom = 6,
                            maxZoom = 16,
                            routingReady = true,
                            searchReady = true,
                        ),
                    )
                    _state.update {
                        it.copy(installingSample = false, sampleMessage = result.message, hasRoutingData = true)
                    }
                }
                is PackageInstaller.InstallResult.Failure -> _state.update {
                    it.copy(installingSample = false, sampleMessage = result.message)
                }
            }
        }
    }

    fun refreshRoutingAvailability() {
        _state.update { it.copy(hasRoutingData = locator.routingEngine.hasGraph()) }
    }

    override fun onCleared() {
        stopLocationUpdates()
        super.onCleared()
    }
}
