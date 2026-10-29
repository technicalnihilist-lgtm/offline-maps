package com.offlinemaps.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.offlinemaps.app.di.ServiceLocator
import com.offlinemaps.core.common.GeoPoint
import com.offlinemaps.core.common.SavedPlaceLabel
import com.offlinemaps.core.data.RecentSearch
import com.offlinemaps.core.data.SavedPlace
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class SavedUiState(
    val places: List<SavedPlace> = emptyList(),
    val recent: List<RecentSearch> = emptyList(),
    val message: String? = null,
)

class SavedViewModel(private val locator: ServiceLocator) : ViewModel() {

    private val _state = MutableStateFlow(SavedUiState())
    val state: StateFlow<SavedUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            locator.savedPlaces.observe().collect { places -> _state.value = _state.value.copy(places = places) }
        }
        viewModelScope.launch {
            locator.recentSearches.observe(20).collect { recent -> _state.value = _state.value.copy(recent = recent) }
        }
    }

    fun save(name: String, label: SavedPlaceLabel, point: GeoPoint) {
        viewModelScope.launch {
            locator.savedPlaces.save(SavedPlace(name = name, label = label, point = point))
            _state.value = _state.value.copy(message = "Saved \"$name\"")
        }
    }

    fun delete(place: SavedPlace) {
        viewModelScope.launch {
            locator.savedPlaces.delete(place)
            _state.value = _state.value.copy(message = "Removed \"${place.name}\"")
        }
    }

    fun clearRecent() {
        viewModelScope.launch {
            locator.recentSearches.clear()
            _state.value = _state.value.copy(message = "Search history cleared")
        }
    }

    fun dismissMessage() {
        _state.value = _state.value.copy(message = null)
    }
}
