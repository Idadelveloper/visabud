package online.visabud.app.visabud_multiplatform.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import online.visabud.app.visabud_multiplatform.embassy.EmbassyDistance
import online.visabud.app.visabud_multiplatform.embassy.EmbassyRepo
import online.visabud.app.visabud_multiplatform.embassy.nearestEmbassies
import online.visabud.app.visabud_multiplatform.embassy.nearestEmbassiesInCountry
import online.visabud.app.visabud_multiplatform.platform.getCurrentLocationOrNull
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi

private enum class LocatorMode { NEAREST_ME, NEAREST_IN_COUNTRY, ALL_IN_COUNTRY, ALL_GLOBAL }

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun EmbassyLocatorScreen(paddingValues: PaddingValues) {
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    var latText by remember { mutableStateOf("") }
    var lonText by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<EmbassyDistance>>(emptyList()) }
    var status by remember { mutableStateOf("") }

    var mode by remember { mutableStateOf(LocatorMode.NEAREST_ME) }

    // Location permission requester (Android shows prompt; others no-op)
    val permissionRequester = online.visabud.app.visabud_multiplatform.platform.rememberLocationPermissionRequester { granted ->
        scope.launch {
            if (granted) {
                val loc = getCurrentLocationOrNull()
                if (loc != null) {
                    latText = loc.latitude.toString()
                    lonText = loc.longitude.toString()
                    snackbar.showSnackbar("Location updated.")
                } else {
                    snackbar.showSnackbar("Location unavailable even after granting permission.")
                }
            } else {
                snackbar.showSnackbar("Location permission denied. Enter coordinates manually or grant permission in Settings.")
            }
        }
    }

    // Country dropdown state
    var countries by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var expanded by remember { mutableStateOf(false) }
    var selectedCountry by remember { mutableStateOf<Pair<String, String>?>(null) }

    LaunchedEffect(Unit) {
        // Load countries list
        countries = try { EmbassyRepo.listCountries() } catch (_: Throwable) { emptyList() }
        // Try current location; ignore if unavailable
        try {
            val loc = getCurrentLocationOrNull()
            if (loc != null) {
                latText = loc.latitude.toString()
                lonText = loc.longitude.toString()
                results = nearestEmbassies(loc.latitude, loc.longitude, limit = 5)
            }
        } catch (_: Throwable) {}
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(paddingValues)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Embassy Locator", style = MaterialTheme.typography.titleLarge)
            Text(
                "Find embassies by country and distance. Works offline using a bundled dataset.",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Mode selector chips
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = mode == LocatorMode.NEAREST_ME,
                    onClick = { mode = LocatorMode.NEAREST_ME },
                    label = { Text("Nearest to me") }
                )
                FilterChip(
                    selected = mode == LocatorMode.NEAREST_IN_COUNTRY,
                    onClick = { mode = LocatorMode.NEAREST_IN_COUNTRY },
                    label = { Text("Nearest in country") }
                )
                FilterChip(
                    selected = mode == LocatorMode.ALL_IN_COUNTRY,
                    onClick = { mode = LocatorMode.ALL_IN_COUNTRY },
                    label = { Text("All in country") }
                )
                FilterChip(
                    selected = mode == LocatorMode.ALL_GLOBAL,
                    onClick = { mode = LocatorMode.ALL_GLOBAL },
                    label = { Text("All embassies") }
                )
            }

            // Country dropdown (for country modes)
            ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
                OutlinedTextField(
                    value = selectedCountry?.second ?: "",
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Country") },
                    modifier = Modifier
                        .menuAnchor()
                        .fillMaxWidth(),
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                    placeholder = { Text("Select country") },
                    enabled = mode != LocatorMode.NEAREST_ME && mode != LocatorMode.ALL_GLOBAL
                )
                ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    countries.forEach { pair ->
                        DropdownMenuItem(
                            text = { Text("${pair.second} (${pair.first})") },
                            onClick = {
                                selectedCountry = pair
                                expanded = false
                            }
                        )
                    }
                }
            }

            // Coordinates row
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = latText,
                    onValueChange = { latText = it },
                    label = { Text("Latitude") },
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = lonText,
                    onValueChange = { lonText = it },
                    label = { Text("Longitude") },
                    modifier = Modifier.weight(1f)
                )
            }

            // Actions
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    scope.launch {
                        // If permission is granted, fetch immediately; otherwise request it
                        if (online.visabud.app.visabud_multiplatform.platform.isLocationPermissionGranted()) {
                            val loc = getCurrentLocationOrNull()
                            if (loc == null) {
                                snackbar.showSnackbar("Location unavailable. Try again or enter coordinates.")
                            } else {
                                latText = loc.latitude.toString(); lonText = loc.longitude.toString()
                                snackbar.showSnackbar("Location updated.")
                            }
                        } else {
                            permissionRequester.request()
                        }
                    }
                }) { Text("Use my location") }

                Button(onClick = {
                    scope.launch {
                        status = "Calculating…"
                        try {
                            val lat = latText.toDoubleOrNull()
                            val lon = lonText.toDoubleOrNull()
                            results = when (mode) {
                                LocatorMode.NEAREST_ME -> {
                                    if (lat == null || lon == null) {
                                        snackbar.showSnackbar("Enter or fetch your coordinates first."); emptyList()
                                    } else nearestEmbassies(lat, lon, 5)
                                }
                                LocatorMode.NEAREST_IN_COUNTRY -> {
                                    if (lat == null || lon == null) {
                                        snackbar.showSnackbar("Enter or fetch your coordinates first."); emptyList()
                                    } else if (selectedCountry == null) {
                                        snackbar.showSnackbar("Select a country."); emptyList()
                                    } else nearestEmbassiesInCountry(lat, lon, selectedCountry!!.first, 5)
                                }
                                LocatorMode.ALL_IN_COUNTRY -> {
                                    if (selectedCountry == null) {
                                        snackbar.showSnackbar("Select a country."); emptyList()
                                    } else {
                                        val list = EmbassyRepo.filterByCountry(selectedCountry!!.first)
                                        // If coords entered, compute distance; otherwise show 0
                                        val lat = latText.toDoubleOrNull()
                                        val lon = lonText.toDoubleOrNull()
                                        list.map {
                                            val d = if (lat != null && lon != null) online.visabud.app.visabud_multiplatform.embassy.distanceKm(lat, lon, it.lat, it.lon) else 0.0
                                            EmbassyDistance(it, d)
                                        }
                                    }
                                }
                                LocatorMode.ALL_GLOBAL -> {
                                    val list = EmbassyRepo.getAll()
                                    val lat = latText.toDoubleOrNull(); val lon = lonText.toDoubleOrNull()
                                    list.map {
                                        val d = if (lat != null && lon != null) online.visabud.app.visabud_multiplatform.embassy.distanceKm(lat, lon, it.lat, it.lon) else 0.0
                                        EmbassyDistance(it, d)
                                    }
                                }
                            }
                        } finally {
                            status = ""
                        }
                    }
                }) { Text("Search") }
            }

            if (status.isNotBlank()) Text(status, color = MaterialTheme.colorScheme.primary)

            if (results.isEmpty()) {
                Text("No results. Adjust mode or country and try again.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(results) { r -> EmbassyCard(r) }
                }
            }
        }

        SnackbarHost(snackbar, modifier = Modifier.align(androidx.compose.ui.Alignment.BottomCenter))
    }
}

@Composable
private fun EmbassyCard(item: EmbassyDistance) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("${item.embassy.country} — ${item.embassy.city}", style = MaterialTheme.typography.titleMedium)
            Text(item.embassy.address)
            item.embassy.phone?.let { Text("Phone: $it") }
            item.embassy.website?.let { Text("Site: $it") }
            val kmRounded = kotlin.math.round(item.distanceKm * 10.0) / 10.0
            if (item.distanceKm > 0) Text("Distance: ${kmRounded} km", color = MaterialTheme.colorScheme.primary)
        }
    }
}
