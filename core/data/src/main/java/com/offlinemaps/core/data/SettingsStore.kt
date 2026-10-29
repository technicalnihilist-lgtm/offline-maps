package com.offlinemaps.core.data

import android.content.Context
import android.content.SharedPreferences
import com.offlinemaps.core.common.GraphicsQuality
import com.offlinemaps.core.common.MapTheme
import com.offlinemaps.core.common.TravelProfile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * User preferences. Backed by SharedPreferences rather than a heavier store: the values are tiny
 * and reading them must never block map startup.
 */
class SettingsStore(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("offline_maps_settings", Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(read())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    fun current(): AppSettings = _settings.value

    fun update(transform: (AppSettings) -> AppSettings) {
        val next = transform(_settings.value)
        prefs.edit()
            .putString(KEY_THEME, next.theme.name)
            .putString(KEY_QUALITY, next.quality.name)
            .putBoolean(KEY_WIFI_ONLY, next.wifiOnlyDownloads)
            .putLong(KEY_STORAGE_LIMIT_MB, next.storageLimitMb)
            .putBoolean(KEY_VOICE, next.voiceGuidanceEnabled)
            .putString(KEY_TTS_LANG, next.ttsLanguageTag ?: "")
            .putFloat(KEY_TTS_VOLUME, next.ttsVolume)
            .putBoolean(KEY_METRIC, next.metricUnits)
            .putString(KEY_PROFILE, next.travelProfile.name)
            .putBoolean(KEY_TERRAIN, next.terrainEnabled)
            .putBoolean(KEY_BUILDINGS_3D, next.buildings3DEnabled)
            .putBoolean(KEY_ANALYTICS, next.shareAnonymousStats)
            .putLong(KEY_CACHE_MB, next.tileCacheMb)
            .apply()
        _settings.value = next
    }

    private fun read(): AppSettings = AppSettings(
        theme = prefs.getString(KEY_THEME, null)?.let { runCatching { MapTheme.valueOf(it) }.getOrNull() } ?: MapTheme.LIGHT,
        quality = prefs.getString(KEY_QUALITY, null)?.let { runCatching { GraphicsQuality.valueOf(it) }.getOrNull() } ?: GraphicsQuality.MEDIUM,
        wifiOnlyDownloads = prefs.getBoolean(KEY_WIFI_ONLY, true),
        storageLimitMb = prefs.getLong(KEY_STORAGE_LIMIT_MB, 4096L),
        voiceGuidanceEnabled = prefs.getBoolean(KEY_VOICE, true),
        ttsLanguageTag = prefs.getString(KEY_TTS_LANG, "")?.takeIf { it.isNotBlank() },
        ttsVolume = prefs.getFloat(KEY_TTS_VOLUME, 1.0f),
        metricUnits = prefs.getBoolean(KEY_METRIC, true),
        travelProfile = prefs.getString(KEY_PROFILE, null)?.let { runCatching { TravelProfile.valueOf(it) }.getOrNull() } ?: TravelProfile.CAR,
        terrainEnabled = prefs.getBoolean(KEY_TERRAIN, true),
        buildings3DEnabled = prefs.getBoolean(KEY_BUILDINGS_3D, true),
        shareAnonymousStats = prefs.getBoolean(KEY_ANALYTICS, false),
        tileCacheMb = prefs.getLong(KEY_CACHE_MB, 256L),
    )

    fun clearAll() {
        prefs.edit().clear().apply()
        _settings.value = AppSettings()
    }

    private companion object {
        const val KEY_THEME = "theme"
        const val KEY_QUALITY = "quality"
        const val KEY_WIFI_ONLY = "wifi_only"
        const val KEY_STORAGE_LIMIT_MB = "storage_limit_mb"
        const val KEY_VOICE = "voice"
        const val KEY_TTS_LANG = "tts_lang"
        const val KEY_TTS_VOLUME = "tts_volume"
        const val KEY_METRIC = "metric"
        const val KEY_PROFILE = "profile"
        const val KEY_TERRAIN = "terrain"
        const val KEY_BUILDINGS_3D = "buildings_3d"
        const val KEY_ANALYTICS = "analytics"
        const val KEY_CACHE_MB = "cache_mb"
    }
}

data class AppSettings(
    val theme: MapTheme = MapTheme.LIGHT,
    val quality: GraphicsQuality = GraphicsQuality.MEDIUM,
    val wifiOnlyDownloads: Boolean = true,
    val storageLimitMb: Long = 4096L,
    val voiceGuidanceEnabled: Boolean = true,
    val ttsLanguageTag: String? = null,
    val ttsVolume: Float = 1.0f,
    val metricUnits: Boolean = true,
    val travelProfile: TravelProfile = TravelProfile.CAR,
    val terrainEnabled: Boolean = true,
    val buildings3DEnabled: Boolean = true,
    val shareAnonymousStats: Boolean = false,
    val tileCacheMb: Long = 256L,
)
