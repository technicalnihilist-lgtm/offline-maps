package com.offlinemaps.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.offlinemaps.app.di.ServiceLocator
import com.offlinemaps.app.speech.SpeechGuidance
import com.offlinemaps.core.common.Formatters
import com.offlinemaps.core.common.GraphicsQuality
import com.offlinemaps.core.common.MapTheme
import com.offlinemaps.core.data.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SettingsUiState(
    val settings: AppSettings = AppSettings(),
    val voices: List<SpeechGuidance.VoiceInfo> = emptyList(),
    val speechMessage: String? = null,
    val storageUsedBytes: Long = 0L,
    val message: String? = null,
)

class SettingsViewModel(private val locator: ServiceLocator) : ViewModel() {

    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            locator.settings.settings.collect { settings -> _state.value = _state.value.copy(settings = settings) }
        }
        refreshStorage()
        loadVoices()
    }

    fun setTheme(theme: MapTheme) = locator.settings.update { it.copy(theme = theme) }
    fun setQuality(quality: GraphicsQuality) = locator.settings.update { it.copy(quality = quality) }
    fun setWifiOnly(value: Boolean) = locator.settings.update { it.copy(wifiOnlyDownloads = value) }
    fun setStorageLimitMb(value: Long) = locator.settings.update { it.copy(storageLimitMb = value) }
    fun setVoiceEnabled(value: Boolean) = locator.settings.update { it.copy(voiceGuidanceEnabled = value) }
    fun setTtsVolume(value: Float) = locator.settings.update { it.copy(ttsVolume = value) }
    fun setMetric(value: Boolean) = locator.settings.update { it.copy(metricUnits = value) }
    fun setTerrain(value: Boolean) = locator.settings.update { it.copy(terrainEnabled = value) }
    fun setBuildings(value: Boolean) = locator.settings.update { it.copy(buildings3DEnabled = value) }
    fun setTileCacheMb(value: Long) = locator.settings.update { it.copy(tileCacheMb = value) }

    fun setLanguage(tag: String?) {
        locator.settings.update { it.copy(ttsLanguageTag = tag) }
        val engine = locator.navigationManager.speechGuidance ?: return
        _state.value = _state.value.copy(speechMessage = engine.setLanguage(tag))
    }

    private fun loadVoices() {
        viewModelScope.launch {
            val engine = SpeechGuidance(locator.appContext)
            engine.initialise { _, _ -> }
            val voices = withContext(Dispatchers.Default) { engine.availableVoices() }
            _state.value = _state.value.copy(voices = voices)
            engine.shutdown()
        }
    }

    fun refreshStorage() {
        viewModelScope.launch {
            val used = withContext(Dispatchers.IO) {
                locator.packagesDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
            }
            _state.value = _state.value.copy(storageUsedBytes = used)
        }
    }

    fun clearSearchHistory() {
        viewModelScope.launch {
            locator.recentSearches.clear()
            _state.value = _state.value.copy(message = "Search history cleared")
        }
    }

    fun clearSavedPlaces() {
        viewModelScope.launch {
            locator.savedPlaces.deleteAll()
            _state.value = _state.value.copy(message = "Saved places cleared")
        }
    }

    fun clearTileCache() {
        viewModelScope.launch {
            val ok = locator.offlineRegions.clearAmbientCache()
            _state.value = _state.value.copy(message = if (ok) "Cached map data cleared" else "Could not clear the cache")
        }
    }

    fun clearAllMapData() {
        viewModelScope.launch {
            locator.installedRegions.deleteAll()
            locator.search.clear()
            locator.packagesDir.deleteRecursively()
            locator.packagesDir.mkdirs()
            locator.routingEngine.close()
            _state.value = _state.value.copy(message = "All downloaded map data deleted")
            refreshStorage()
        }
    }

    fun dismissMessage() {
        _state.value = _state.value.copy(message = null, speechMessage = null)
    }

    fun storageLabel(): String = Formatters.bytes(_state.value.storageUsedBytes)
}
