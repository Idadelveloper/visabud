package online.visabud.app.visabud_multiplatform.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import online.visabud.app.visabud_multiplatform.cost.CostCalculatorEngine
import online.visabud.app.visabud_multiplatform.cost.CostInputs
import online.visabud.app.visabud_multiplatform.cost.TravelClass
import online.visabud.app.visabud_multiplatform.cost.VisaFeesRepo
import online.visabud.app.visabud_multiplatform.platform.getCurrentLocationOrNull
import online.visabud.app.visabud_multiplatform.platform.isLocationPermissionGranted
import online.visabud.app.visabud_multiplatform.platform.rememberLocationPermissionRequester

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CostCalculatorScreen(paddingValues: PaddingValues) {
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    var countries by remember { mutableStateOf(listOf<String>()) }
    var selectedCountry by remember { mutableStateOf<String?>(null) }
    var visaTypes by remember { mutableStateOf(listOf<String>()) }
    var selectedVisaType by remember { mutableStateOf<String?>(null) }

    var pages by remember { mutableStateOf(0f) }
    var includeCourier by remember { mutableStateOf(false) }
    var includeLegal by remember { mutableStateOf(false) }
    var legalFactor by remember { mutableStateOf(0.5f) }
    var nights by remember { mutableStateOf(0f) }
    var nightlyRate by remember { mutableStateOf(0f) }
    var travelClassIndex by remember { mutableStateOf(0f) } // 0,1,2
    var expedited by remember { mutableStateOf(false) }

    var originLat by remember { mutableStateOf<String>("") }
    var originLon by remember { mutableStateOf<String>("") }

    var breakdown by remember { mutableStateOf<online.visabud.app.visabud_multiplatform.cost.CostBreakdown?>(null) }

    val permissionRequester = rememberLocationPermissionRequester { granted ->
        scope.launch {
            if (granted) {
                val loc = getCurrentLocationOrNull()
                if (loc != null) {
                    originLat = loc.latitude.toString(); originLon = loc.longitude.toString()
                    snackbar.showSnackbar("Location filled")
                } else snackbar.showSnackbar("Location unavailable")
            } else snackbar.showSnackbar("Permission denied")
        }
    }

    LaunchedEffect(Unit) {
        val list = VisaFeesRepo.getAll()
        countries = list.map { it.country }.sorted()
    }

    fun recalc() {
        val country = selectedCountry ?: return
        val visa = selectedVisaType ?: return
        scope.launch {
            try {
                val inputs = CostInputs(
                    countryCodeOrName = country,
                    visaType = visa,
                    pagesToTranslate = pages.toInt(),
                    includeCourier = includeCourier,
                    includeLegalAssist = includeLegal,
                    legalAssistFactor = legalFactor.toDouble(),
                    nights = nights.toInt(),
                    nightlyRate = nightlyRate.toDouble(),
                    travelClass = when (travelClassIndex.toInt()) {
                        1 -> TravelClass.PREMIUM
                        2 -> TravelClass.BUSINESS
                        else -> TravelClass.ECONOMY
                    },
                    processingExpedited = expedited,
                    originLat = originLat.toDoubleOrNull(),
                    originLon = originLon.toDoubleOrNull()
                )
                breakdown = CostCalculatorEngine.compute(inputs)
            } catch (e: Throwable) {
                snackbar.showSnackbar(e.message ?: "Calculation error")
            }
        }
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .padding(paddingValues),
        snackbarHost = { SnackbarHost(hostState = snackbar) }
    ) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Cost Calculator", style = MaterialTheme.typography.titleLarge)
            Text("Estimate visa costs including fees, travel, and more. Offline-first.", color = MaterialTheme.colorScheme.onSurfaceVariant)

            // Country dropdown
            var expandedCountry by remember { mutableStateOf(false) }
            ExposedDropdownMenuBox(expanded = expandedCountry, onExpandedChange = { expandedCountry = it }) {
                OutlinedTextField(
                    value = selectedCountry ?: "",
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Country") },
                    modifier = Modifier.menuAnchor().fillMaxWidth(),
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expandedCountry) },
                    placeholder = { Text("Select country") }
                )
                ExposedDropdownMenu(expanded = expandedCountry, onDismissRequest = { expandedCountry = false }) {
                    countries.forEach { c ->
                        DropdownMenuItem(text = { Text(c) }, onClick = {
                            selectedCountry = c
                            expandedCountry = false
                            // Load visa types
                            scope.launch {
                                val entry = VisaFeesRepo.getByCodeOrName(c)
                                visaTypes = entry?.visaTypes?.keys?.toList()?.sorted() ?: emptyList()
                                selectedVisaType = visaTypes.firstOrNull()
                                recalc()
                            }
                        })
                    }
                }
            }

            // Visa type dropdown
            var expandedVisa by remember { mutableStateOf(false) }
            ExposedDropdownMenuBox(expanded = expandedVisa, onExpandedChange = { expandedVisa = it }) {
                OutlinedTextField(
                    value = selectedVisaType ?: "",
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Visa type") },
                    modifier = Modifier.menuAnchor().fillMaxWidth(),
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expandedVisa) },
                    placeholder = { Text("Select visa type") },
                    enabled = visaTypes.isNotEmpty()
                )
                ExposedDropdownMenu(expanded = expandedVisa, onDismissRequest = { expandedVisa = false }) {
                    visaTypes.forEach { v ->
                        DropdownMenuItem(text = { Text(v) }, onClick = {
                            selectedVisaType = v
                            expandedVisa = false
                            recalc()
                        })
                    }
                }
            }

            // Location row
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = originLat,
                    onValueChange = { originLat = it; recalc() },
                    label = { Text("Origin lat") },
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = originLon,
                    onValueChange = { originLon = it; recalc() },
                    label = { Text("Origin lon") },
                    modifier = Modifier.weight(1f)
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    scope.launch {
                        if (isLocationPermissionGranted()) {
                            val loc = getCurrentLocationOrNull()
                            if (loc == null) snackbar.showSnackbar("Location unavailable") else {
                                originLat = loc.latitude.toString(); originLon = loc.longitude.toString(); recalc()
                            }
                        } else permissionRequester.request()
                    }
                }) { Text("Use my location") }
                Button(onClick = { recalc() }, enabled = selectedCountry != null && selectedVisaType != null) { Text("Recalculate") }
            }

            // Sliders and toggles
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Pages to translate: ${pages.toInt()}")
                Slider(value = pages, onValueChange = { pages = it; recalc() }, valueRange = 0f..50f, steps = 49)

                Text("Nights of stay: ${nights.toInt()}")
                Slider(value = nights, onValueChange = { nights = it; recalc() }, valueRange = 0f..60f, steps = 59)

                OutlinedTextField(
                    value = if (nightlyRate == 0f) "" else nightlyRate.toInt().toString(),
                    onValueChange = { nightlyRate = it.toFloatOrNull() ?: 0f; recalc() },
                    label = { Text("Nightly rate") },
                    modifier = Modifier.fillMaxWidth()
                )

                Text("Travel class: ${when(travelClassIndex.toInt()){0->"Economy";1->"Premium";else->"Business"}}")
                Slider(value = travelClassIndex, onValueChange = { travelClassIndex = it; recalc() }, valueRange = 0f..2f, steps = 1)

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AssistChip(onClick = { expedited = !expedited; recalc() }, label = { Text(if (expedited) "Expedited" else "Standard") })
                    FilterChip(selected = includeCourier, onClick = { includeCourier = !includeCourier; recalc() }, label = { Text("Courier") })
                    FilterChip(selected = includeLegal, onClick = { includeLegal = !includeLegal; recalc() }, label = { Text("Legal assist") })
                }

                if (includeLegal) {
                    Text("Legal assist level: ${(legalFactor*100).toInt()}% of range")
                    Slider(value = legalFactor, onValueChange = { legalFactor = it; recalc() })
                }
            }

            Divider()
            Text("Breakdown", style = MaterialTheme.typography.titleMedium)
            val b = breakdown
            if (b == null) {
                Text("Select country and visa to see costs.")
            } else {
                BreakdownList(b)
            }
        }
    }
}

@Composable
private fun BreakdownList(b: online.visabud.app.visabud_multiplatform.cost.CostBreakdown) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        KeyValue("Currency", b.currency)
        b.distanceKm?.let { KeyValue("Flight distance", "${it} km") }
        MoneyRow("Base visa fee", b.baseVisaFee, b.currency)
        MoneyRow("Biometrics", b.biometrics, b.currency)
        MoneyRow("Embassy", b.embassy, b.currency)
        MoneyRow("Translations", b.translations, b.currency)
        MoneyRow("Courier", b.courier, b.currency)
        MoneyRow("Flights", b.flights, b.currency)
        MoneyRow("Accommodation", b.accommodation, b.currency)
        MoneyRow("Legal assistance", b.legalAssist, b.currency)
        MoneyRow("Contingency (10%)", b.contingency, b.currency)
        Divider()
        MoneyRow("Total", b.total, b.currency, bold = true)
    }
}

@Composable
private fun KeyValue(k: String, v: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(k)
        Text(v, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun MoneyRow(label: String, amount: Double, currency: String, bold: Boolean = false) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label)
        Text(formatMoney(amount, currency), fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal)
    }
}

private fun formatMoney(v: Double, c: String): String {
    fun r2(x: Double) = kotlin.math.round(x * 100.0) / 100.0
    fun r0(x: Double) = kotlin.math.round(x)
    return when (c.uppercase()) {
        "GBP" -> "£" + r2(v).toPlainString(2)
        "EUR" -> "€" + r2(v).toPlainString(2)
        "USD" -> "$" + r2(v).toPlainString(2)
        "CAD" -> "CA$" + r2(v).toPlainString(2)
        "AUD" -> "A$" + r2(v).toPlainString(2)
        "JPY" -> "¥" + r0(v).toLong().toString()
        else -> r2(v).toPlainString(2) + " " + c
    }
}

private fun Double.toPlainString(decimals: Int): String {
    val factor = 10.0.pow(decimals)
    val rounded = kotlin.math.round(this * factor) / factor
    val parts = rounded.toString().split('.')
    val intPart = parts[0]
    val frac = if (decimals <= 0) "" else {
        val current = if (parts.size > 1) parts[1] else ""
        (current + "0".repeat(decimals)).take(decimals)
    }
    return if (decimals > 0) "$intPart.$frac" else intPart
}

private fun Double.pow(exp: Int): Double {
    var res = 1.0
    repeat(exp) { res *= this }
    return res
}