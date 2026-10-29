package com.offlinemaps.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.offlinemaps.app.di.ServiceLocator

/** Constructs view models with the service locator, without a DI framework. */
class MapViewModelFactory(private val locator: ServiceLocator) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = when {
        modelClass.isAssignableFrom(MapViewModel::class.java) -> MapViewModel(locator) as T
        modelClass.isAssignableFrom(DownloadsViewModel::class.java) -> DownloadsViewModel(locator) as T
        modelClass.isAssignableFrom(SavedViewModel::class.java) -> SavedViewModel(locator) as T
        modelClass.isAssignableFrom(SettingsViewModel::class.java) -> SettingsViewModel(locator) as T
        else -> throw IllegalArgumentException("Unknown view model ${modelClass.name}")
    }
}
