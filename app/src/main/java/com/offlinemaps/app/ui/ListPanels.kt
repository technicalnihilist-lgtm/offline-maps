package com.offlinemaps.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.offlinemaps.app.di.ServiceLocator
import com.offlinemaps.core.common.DownloadStatus
import com.offlinemaps.core.common.Formatters
import com.offlinemaps.core.common.GeoPoint
import com.offlinemaps.core.common.GraphicsQuality
import com.offlinemaps.core.common.MapTheme
import com.offlinemaps.core.common.RegionCatalogEntry
import com.offlinemaps.core.common.RegionPackage
import com.offlinemaps.core.data.DownloadRecord
import com.offlinemaps.core.offline.DownloadProgress
import com.offlinemaps.core.ui.EmptyState
import com.offlinemaps.core.ui.FloatingPanel
import com.offlinemaps.core.ui.Hairline
import com.offlinemaps.core.ui.InfoRow
import com.offlinemaps.core.ui.ProgressRow
import com.offlinemaps.core.ui.SectionHeader
import com.offlinemaps.core.ui.StatusChip
import com.offlinemaps.core.ui.StatusTone

@Composable
fun DownloadsPanel(locator: ServiceLocator) {
    val factory = remember { MapViewModelFactory(locator) }
    val viewModel: DownloadsViewModel = viewModel(factory = factory)
    val state by viewModel.state.collectAsState()

    FloatingPanel(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text("Offline maps", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Storage: ${Formatters.bytes(state.availableBytes)} free · " +
                    "${Formatters.bytes(state.installed.sumOf { it.sizeBytes })} used by maps",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (state.banner != null) {
                Spacer(Modifier.height(6.dp))
                StatusChip(text = state.banner!!, tone = StatusTone.WARNING, onClick = viewModel::dismissBanner)
            }
            Spacer(Modifier.height(8.dp))
            LazyColumn(modifier = Modifier.heightIn(max = 380.dp)) {
                item { SectionHeader("Available regions", trailing = "${state.regions.size}") }
                items(state.regions, key = { it.id }) { region ->
                    RegionRow(
                        region = region,
                        state = state,
                        onProbe = { pkg -> viewModel.probeSize(region, pkg) },
                        onStart = { pkg -> viewModel.start(region, pkg) },
                        estimate = { viewModel.estimatedTileBytes(region) },
                        zoomNote = viewModel.zoomDescription(region),
                    )
                    Hairline()
                }
                if (state.downloads.isNotEmpty()) {
                    item { SectionHeader("Downloads") }
                    items(state.downloads, key = { it.packageKey }) { record ->
                        DownloadRow(
                            record = record,
                            progress = state.progress[record.packageKey],
                            message = state.messages[record.packageKey],
                            onPause = { viewModel.pause(record) },
                            onResume = { viewModel.resume(record) },
                            onCancel = { viewModel.cancel(record) },
                            onDelete = { viewModel.delete(record) },
                        )
                        Hairline()
                    }
                }
                if (state.installed.isNotEmpty()) {
                    item { SectionHeader("Installed") }
                    items(state.installed, key = { it.regionId }) { region ->
                        InfoRow(
                            leading = if (region.routingReady) "RT" else "MP",
                            title = region.name,
                            subtitle = buildString {
                                append(Formatters.bytes(region.sizeBytes))
                                if (region.routingReady) append(" · routing ready")
                                if (region.searchReady) append(" · search ready")
                                if (!region.routingReady && !region.searchReady) append(" · tiles only")
                            },
                            trailing = { TextButton(onClick = { viewModel.deleteRegion(region) }) { Text("Delete") } },
                        )
                    }
                }
                if (state.regions.isEmpty() && !state.loading) {
                    item { EmptyState("No catalogue", "The bundled region catalogue could not be read.") }
                }
            }
        }
    }
}

@Composable
private fun RegionRow(
    region: RegionCatalogEntry,
    state: DownloadsUiState,
    onProbe: (RegionPackage) -> Unit,
    onStart: (RegionPackage) -> Unit,
    estimate: () -> Long,
    zoomNote: String,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(region.name, style = MaterialTheme.typography.titleMedium)
        Text(
            "${region.country} · zooms ${region.minZoom}-${region.maxZoom} · est. ${Formatters.bytes(estimate())}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(zoomNote, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        region.packages.forEach { pkg ->
            val key = "${region.id}-${pkg.type.name}"
            val existing = state.downloads.firstOrNull { it.packageKey == key }
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(pkg.type.name.lowercase().replace('_', ' '), style = MaterialTheme.typography.labelLarge)
                    Text(
                        "Size: " + (state.resolvedSizes[pkg.url] ?: pkg.sizeBytes)
                            .let { if (it > 0) Formatters.bytes(it) else "unknown until download starts" },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                when (existing?.status) {
                    DownloadStatus.RUNNING -> StatusChip(text = "Downloading", tone = StatusTone.NEUTRAL)
                    DownloadStatus.PAUSED -> StatusChip(text = "Paused", tone = StatusTone.WARNING)
                    DownloadStatus.COMPLETED -> StatusChip(text = "Installed", tone = StatusTone.POSITIVE)
                    DownloadStatus.FAILED -> StatusChip(text = "Failed", tone = StatusTone.ERROR)
                    else -> {
                        TextButton(onClick = { onProbe(pkg) }) { Text("Size") }
                        Button(onClick = { onStart(pkg) }) { Text("Download") }
                    }
                }
            }
        }
    }
}

@Composable
private fun DownloadRow(
    record: DownloadRecord,
    progress: DownloadProgress?,
    message: String?,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(record.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        val done = progress?.bytesDownloaded ?: record.bytesDownloaded
        val total = progress?.bytesTotal ?: record.bytesTotal
        ProgressRow(
            fraction = if (total > 0) (done.toFloat() / total) else 0f,
            label = when (record.status) {
                DownloadStatus.RUNNING -> "Downloading ${Formatters.bytes(done)} / ${Formatters.bytes(total)}"
                DownloadStatus.PAUSED -> "Paused at ${Formatters.bytes(done)}"
                DownloadStatus.VERIFYING -> "Verifying checksum"
                DownloadStatus.COMPLETED -> "Complete · ${Formatters.bytes(total)}"
                DownloadStatus.FAILED -> "Failed"
                else -> record.status.name.lowercase()
            },
            detail = progress?.bytesPerSecond?.takeIf { it > 0 }?.let { Formatters.rate(it) },
            indeterminate = record.status == DownloadStatus.VERIFYING,
        )
        if (message != null) {
            Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            when (record.status) {
                DownloadStatus.RUNNING -> TextButton(onClick = onPause) { Text("Pause") }
                DownloadStatus.PAUSED, DownloadStatus.FAILED -> TextButton(onClick = onResume) { Text("Resume") }
                else -> Unit
            }
            if (record.status != DownloadStatus.COMPLETED) {
                TextButton(onClick = onCancel) { Text("Cancel") }
            } else {
                TextButton(onClick = onDelete) { Text("Delete") }
            }
        }
    }
}

@Composable
fun SavedPanel(locator: ServiceLocator, onGoTo: (GeoPoint) -> Unit) {
    val factory = remember { MapViewModelFactory(locator) }
    val viewModel: SavedViewModel = viewModel(factory = factory)
    val state by viewModel.state.collectAsState()

    FloatingPanel(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text("Saved places", style = MaterialTheme.typography.headlineSmall)
            if (state.message != null) {
                StatusChip(text = state.message!!, tone = StatusTone.POSITIVE, onClick = viewModel::dismissMessage)
            }
            LazyColumn(Modifier.heightIn(max = 360.dp)) {
                if (state.places.isEmpty()) {
                    item { EmptyState("Nothing saved yet", "Search a place and use Save, or set it as home or work.") }
                }
                items(state.places, key = { it.id }) { place ->
                    InfoRow(
                        leading = place.label.label.take(2).uppercase(),
                        title = place.name,
                        subtitle = "${place.label.label} · ${place.point.format(5)}",
                        trailing = {
                            Row {
                                TextButton(onClick = { onGoTo(place.point) }) { Text("Go") }
                                TextButton(onClick = { viewModel.delete(place) }) { Text("Remove") }
                            }
                        },
                    )
                    Hairline()
                }
                item { SectionHeader("Recent searches") }
                items(state.recent, key = { it.query }) { recent ->
                    InfoRow(
                        leading = "R",
                        title = recent.name,
                        subtitle = recent.point.format(5),
                        trailing = { TextButton(onClick = { onGoTo(recent.point) }) { Text("Go") } },
                    )
                }
            }
            if (state.recent.isNotEmpty()) {
                TextButton(onClick = viewModel::clearRecent) { Text("Clear search history") }
            }
        }
    }
}

@Composable
fun SettingsPanel(locator: ServiceLocator) {
    val factory = remember { MapViewModelFactory(locator) }
    val viewModel: SettingsViewModel = viewModel(factory = factory)
    val state by viewModel.state.collectAsState()
    val settings = state.settings

    FloatingPanel(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text("Settings", style = MaterialTheme.typography.headlineSmall)
            if (state.message != null) {
                StatusChip(text = state.message!!, tone = StatusTone.POSITIVE, onClick = viewModel::dismissMessage)
            }
            if (state.speechMessage != null) {
                StatusChip(text = state.speechMessage!!, tone = StatusTone.WARNING, onClick = viewModel::dismissMessage)
            }
            LazyColumn(Modifier.heightIn(max = 380.dp)) {
                item { SectionHeader("Appearance") }
                item {
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Dark theme", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        Switch(
                            checked = settings.theme == MapTheme.DARK,
                            onCheckedChange = { viewModel.setTheme(if (it) MapTheme.DARK else MapTheme.LIGHT) },
                        )
                    }
                }
                item {
                    Text("Graphics quality", style = MaterialTheme.typography.bodyMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        GraphicsQuality.entries.forEach { quality ->
                            TextButton(onClick = { viewModel.setQuality(quality) }) {
                                Text(
                                    quality.label,
                                    color = if (settings.quality == quality) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                )
                            }
                        }
                    }
                }
                item { ToggleRow("3D buildings", settings.buildings3DEnabled, viewModel::setBuildings) }
                item { ToggleRow("Terrain relief", settings.terrainEnabled, viewModel::setTerrain) }
                item { SectionHeader("Downloads") }
                item { ToggleRow("Wi-Fi only downloads", settings.wifiOnlyDownloads, viewModel::setWifiOnly) }
                item { ToggleRow("Metric units", settings.metricUnits, viewModel::setMetric) }
                item { SectionHeader("Voice guidance") }
                item { ToggleRow("Spoken directions", settings.voiceGuidanceEnabled, viewModel::setVoiceEnabled) }
                item {
                    Text(
                        "Offline voices installed: ${state.voices.count { it.offline }} of ${state.voices.size}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                item { SectionHeader("Privacy and storage") }
                item {
                    Text(
                        "Offline map data: ${Formatters.bytes(state.storageUsedBytes)}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = viewModel::clearSearchHistory) { Text("Clear history") }
                        TextButton(onClick = viewModel::clearSavedPlaces) { Text("Clear saved") }
                        TextButton(onClick = viewModel::clearTileCache) { Text("Clear cache") }
                    }
                }
                item { TextButton(onClick = viewModel::clearAllMapData) { Text("Delete all downloaded maps") } }
                item {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Map data © OpenStreetMap contributors (ODbL 1.0). Tiles served by OpenFreeMap. " +
                            "Terrain: Mapzen/AWS Open Data. Your location, saved places and history stay on this device.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
