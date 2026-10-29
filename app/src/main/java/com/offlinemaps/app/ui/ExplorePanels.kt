package com.offlinemaps.app.ui

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.offlinemaps.app.nav.NavigationSessionUi
import com.offlinemaps.core.common.ConnectivityState
import com.offlinemaps.core.common.Formatters
import com.offlinemaps.core.common.PlaceResult
import com.offlinemaps.core.common.SavedPlaceLabel
import com.offlinemaps.core.common.SearchSource
import com.offlinemaps.core.common.TravelProfile
import com.offlinemaps.core.location.PermissionRationale
import com.offlinemaps.core.ui.FloatingPanel
import com.offlinemaps.core.ui.Hairline
import com.offlinemaps.core.ui.StatusChip
import com.offlinemaps.core.ui.StatusTone

@Composable
fun ExploreSearchBar(
    state: MapUiState,
    onSearchClick: () -> Unit,
    onQueryChange: (String) -> Unit,
    onResultClick: (PlaceResult) -> Unit,
    onClear: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FloatingPanel(modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = state.searchQuery,
                onValueChange = onQueryChange,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
                placeholder = { Text("Search places, streets or 18.52, 73.85") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = {
                    if (state.searchQuery.isNotEmpty()) {
                        IconButton(onClick = onClear) {
                            Icon(Icons.Filled.Close, contentDescription = "Clear search")
                        }
                    }
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSearchClick() }),
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            StatusChip(
                text = state.coverageChip,
                tone = if (state.installedRegions.isEmpty()) StatusTone.WARNING else StatusTone.POSITIVE,
            )
            StatusChip(
                text = if (state.connectivity == ConnectivityState.ONLINE) "Online" else "Offline",
                tone = if (state.connectivity == ConnectivityState.ONLINE) StatusTone.NEUTRAL else StatusTone.POSITIVE,
            )
            if (!state.gpsEnabled) StatusChip(text = "GPS off", tone = StatusTone.WARNING)
        }

        if (state.searchQuery.length >= 2) {
            FloatingPanel(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(vertical = 4.dp)) {
                    if (state.searchNote != null && state.searchResults.isEmpty()) {
                        Text(
                            state.searchNote,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        )
                    }
                    LazyColumn(modifier = Modifier.heightIn(max = 280.dp)) {
                        items(state.searchResults, key = { it.id }) { result ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onResultClick(result) }
                                    .padding(horizontal = 16.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        result.name,
                                        style = MaterialTheme.typography.titleMedium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        listOfNotNull(
                                            result.address,
                                            result.type.replaceFirstChar { it.uppercase() },
                                            if (result.source == SearchSource.COORDINATE) "coordinates" else "offline index",
                                        ).joinToString(" · "),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                Text(
                                    result.point.format(4),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Hairline(Modifier.padding(start = 16.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ExploreBottomPanel(
    state: MapUiState,
    onStartNavigation: () -> Unit,
    onSavePlace: (SavedPlaceLabel) -> Unit,
    onInstallSample: () -> Unit,
    onDismissDestination: () -> Unit,
    onProfileChange: (TravelProfile) -> Unit,
) {
    FloatingPanel(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
        Column(Modifier.padding(16.dp)) {
            val destination = state.destination
            if (destination != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            destination.name,
                            style = MaterialTheme.typography.headlineSmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            destination.point.format(5) + (destination.address?.let { " · $it" } ?: ""),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    IconButton(onClick = onDismissDestination) {
                        Icon(Icons.Filled.Close, contentDescription = "Clear destination")
                    }
                }
                Spacer(Modifier.height(8.dp))
                ProfileSelector(selected = state.profile, onSelect = onProfileChange)
                Spacer(Modifier.height(10.dp))
                if (state.routeSummary != null) {
                    Text(state.routeSummary, style = MaterialTheme.typography.titleMedium)
                }
                if (state.routeMessage != null) {
                    Text(
                        state.routeMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onStartNavigation, enabled = state.routeSummary != null) { Text("Start") }
                    TextButton(onClick = { onSavePlace(SavedPlaceLabel.CUSTOM) }) { Text("Save") }
                    TextButton(onClick = { onSavePlace(SavedPlaceLabel.HOME) }) { Text("Set as home") }
                }
            } else {
                Text("Explore offline", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Search a place to start. Everything works without a network once a region is downloaded.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (state.installedRegions.isEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "No offline data yet. Install the bundled sample region to try real offline routing " +
                            "and search in Andorra, or open Downloads for more.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = onInstallSample, enabled = !state.installingSample) {
                        Text(if (state.installingSample) "Installing…" else "Install sample region")
                    }
                }
                if (state.sampleMessage != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        state.sampleMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
fun ProfileSelector(selected: TravelProfile, onSelect: (TravelProfile) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TravelProfile.entries.forEach { profile ->
            FilterChip(
                selected = profile == selected,
                onClick = { onSelect(profile) },
                label = { Text(profile.label, style = MaterialTheme.typography.labelSmall) },
            )
        }
    }
}

@Composable
fun NavigatePanel(
    state: MapUiState,
    navigation: NavigationSessionUi?,
    onProfileChange: (TravelProfile) -> Unit,
    onStart: () -> Unit,
    onEnd: () -> Unit,
) {
    FloatingPanel(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
        Column(Modifier.padding(16.dp)) {
            if (navigation == null) {
                Text("Plan a route", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(6.dp))
                Text(
                    state.destination?.let { "Destination: ${it.name}" }
                        ?: "Pick a destination on the Explore tab first.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                ProfileSelector(state.profile, onProfileChange)
                Spacer(Modifier.height(10.dp))
                if (state.routeSummary != null) {
                    Text(state.routeSummary, style = MaterialTheme.typography.titleMedium)
                }
                if (state.routeMessage != null) {
                    Text(state.routeMessage, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                Spacer(Modifier.height(12.dp))
                Button(onClick = onStart, enabled = state.routeSummary != null) { Text("Start navigation") }
                if (!state.hasRoutingData) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "No routing data installed. Download a region's routing package on the Downloads tab.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                Text("Guidance active", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(6.dp))
                Text(
                    "${navigation.remainingDistance} · ${navigation.remainingTime} · ETA " +
                        Formatters.eta(navigation.etaMillis),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(10.dp))
                Button(onClick = onEnd) { Text("End navigation") }
            }
        }
    }
}

@Composable
fun NavigationTurnCard(
    session: NavigationSessionUi,
    onEnd: () -> Unit,
    onMuteToggle: () -> Unit,
    onRepeat: () -> Unit,
) {
    FloatingPanel(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        session.nextInstruction,
                        style = MaterialTheme.typography.headlineSmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "in ${session.nextInstructionDistance} · ${session.remainingDistance} left · ${session.remainingTime}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = onMuteToggle) { Text(if (session.muted) "Muted" else "Voice") }
                TextButton(onClick = onRepeat) { Text("Repeat") }
            }
            if (session.gpsWeak) {
                Spacer(Modifier.height(6.dp))
                StatusChip(text = "Weak GPS signal", tone = StatusTone.WARNING)
            }
            if (session.rerouting) {
                Spacer(Modifier.height(6.dp))
                StatusChip(text = "Recalculating route…", tone = StatusTone.NEUTRAL)
            }
            if (session.message != null) {
                Spacer(Modifier.height(6.dp))
                Text(session.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onEnd) { Text("End navigation") }
        }
    }
}

@Composable
fun LocationPermissionDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(PermissionRationale.LOCATION_TITLE) },
        text = { Text(PermissionRationale.LOCATION_BODY) },
        confirmButton = { Button(onClick = onConfirm) { Text("Allow") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Not now") } },
    )
}
