package online.visabud.app.visabud_multiplatform.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import online.visabud.app.visabud_multiplatform.data.DataModule
import online.visabud.app.visabud_multiplatform.data.UserProfile

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(paddingValues: PaddingValues, onOpenProfile: () -> Unit = {}) {
    val scope = remember { CoroutineScope(Dispatchers.Main) }
    val snackbar = remember { SnackbarHostState() }

    var profile by remember { mutableStateOf<UserProfile?>(null) }
    var showAbout by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        profile = DataModule.profiles.getProfile() ?: UserProfile()
    }

    fun saveProfile() {
        val p = profile ?: return
        scope.launch {
            DataModule.profiles.upsertProfile(p.copy(lastSeen = 0L))
            snackbar.showSnackbar("Profile saved")
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(paddingValues)
    ) {
        TopAppBar(title = { Text("Settings") })
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Your Profile", style = MaterialTheme.typography.titleLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onOpenProfile) { Text("Open Profile Page") }
                Button(onClick = {
                    // No-op login for now; show a friendly message
                    scope.launch { snackbar.showSnackbar("Login coming soon") }
                }) { Text("Log in") }
                Button(onClick = { showAbout = true }) { Text("About") }
            }
            val p = profile
            if (p != null) {
                // Inline profile editing has been removed from Settings. Use the Profile page instead.
                Divider()
                Text("Saved Documents", style = MaterialTheme.typography.titleMedium)
                SavedDocsList(p.savedDocs)
            } else {
                Text("Loading profile…")
            }
        }
        SnackbarHost(hostState = snackbar)

        if (showAbout) {
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { showAbout = false },
                title = { Text("About VisaBud") },
                text = {
                    Text(
                        "VisaBud is a privacy-first, on-device visa assistant. It helps you explore visa options, requirements, costs, and embassies using local data and your profile.\n\nVersion: MVP (2025-11-29)"
                    )
                },
                confirmButton = {
                    Button(onClick = { showAbout = false }) { Text("Close") }
                }
            )
        }
    }
}

@Composable
private fun ProfileEditor(p: UserProfile, onChange: (UserProfile) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = p.name ?: "",
            onValueChange = { onChange(p.copy(name = it)) },
            label = { Text("Name") },
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = p.dob ?: "",
            onValueChange = { onChange(p.copy(dob = it)) },
            label = { Text("Date of birth (YYYY-MM-DD)") },
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = p.nationality ?: "",
            onValueChange = { onChange(p.copy(nationality = it)) },
            label = { Text("Nationality") },
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = p.countryOfResidence ?: "",
            onValueChange = { onChange(p.copy(countryOfResidence = it)) },
            label = { Text("Current country") },
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = p.currentVisa ?: "",
            onValueChange = { onChange(p.copy(currentVisa = it)) },
            label = { Text("Current visa") },
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = p.education ?: "",
            onValueChange = { onChange(p.copy(education = it)) },
            label = { Text("Education") },
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = p.workYears?.toString() ?: "",
            onValueChange = { yrs -> onChange(p.copy(workYears = yrs.toIntOrNull())) },
            label = { Text("Work years") },
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = p.languages ?: "",
            onValueChange = { onChange(p.copy(languages = it)) },
            label = { Text("Languages (comma separated)") },
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = p.finances ?: "",
            onValueChange = { onChange(p.copy(finances = it)) },
            label = { Text("Finances (low/med/high)") },
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = p.workStatus ?: "",
            onValueChange = { onChange(p.copy(workStatus = it)) },
            label = { Text("Work status") },
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun SavedDocsList(ids: List<String>) {
    if (ids.isEmpty()) {
        Text("No documents saved yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(ids) { id ->
            SavedDocCard(id)
        }
    }
}

@Composable
private fun SavedDocCard(id: String) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Document ID: $id", style = MaterialTheme.typography.titleSmall)
            // In a fuller impl, we could fetch DocumentMeta by id and show filename/type
        }
    }
}
