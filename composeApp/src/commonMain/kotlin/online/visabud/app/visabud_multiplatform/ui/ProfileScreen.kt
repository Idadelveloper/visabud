package online.visabud.app.visabud_multiplatform.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import online.visabud.app.visabud_multiplatform.data.DataModule
import online.visabud.app.visabud_multiplatform.data.UserProfile

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(paddingValues: PaddingValues, onBack: (() -> Unit)? = null) {
    val scope = remember { CoroutineScope(Dispatchers.Main) }
    val snackbar = remember { SnackbarHostState() }

    var profile by remember { mutableStateOf<UserProfile?>(null) }
    var editMode by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        profile = DataModule.profiles.getProfile() ?: UserProfile()
    }

    fun saveProfile() {
        val p = profile ?: return
        scope.launch {
            DataModule.profiles.upsertProfile(p.copy(lastSeen = 0L))
            snackbar.showSnackbar("Profile saved")
            editMode = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(paddingValues)
    ) {
        TopAppBar(title = { Text("Profile") }, navigationIcon = {
            if (onBack != null) {
                TextButton(onClick = { onBack() }) { Text("Back") }
            }
        })

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Your Details", style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold))
            val p = profile
            if (p == null) {
                Text("Loading profile…")
            } else {
                if (editMode) {
                    EditableProfile(p) { updated -> profile = updated }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { saveProfile() }) { Text("Save") }
                        TextButton(onClick = { editMode = false }) { Text("Cancel") }
                    }
                } else {
                    ReadOnlyProfile(p)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { editMode = true }) { Text("Edit Profile") }
                    }
                }
                Divider()
                Text("Tip: This profile is stored locally and helps personalize your visa advice.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        SnackbarHost(hostState = snackbar)
    }
}

@Composable
private fun ReadOnlyProfile(p: UserProfile) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        @Composable
        fun line(label: String, value: String?) {
            Text("$label: ${value?.ifBlank { "—" } ?: "—"}")
        }
        line("Name", p.name)
        line("Date of birth", p.dob)
        line("Nationality", p.nationality)
        line("Residence", p.countryOfResidence)
        line("Current visa", p.currentVisa)
        line("Education", p.education)
        line("Work years", p.workYears?.toString())
        line("Languages", p.languages)
        line("Finances", p.finances)
        line("Work status", p.workStatus)
        line("Passport expiry", p.passportExpiry)
    }
}

@Composable
private fun EditableProfile(p: UserProfile, onChange: (UserProfile) -> Unit) {
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
        OutlinedTextField(
            value = p.passportExpiry ?: "",
            onValueChange = { onChange(p.copy(passportExpiry = it)) },
            label = { Text("Passport expiry (YYYY-MM-DD)") },
            modifier = Modifier.fillMaxWidth()
        )
    }
}
