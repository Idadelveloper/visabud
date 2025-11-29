package online.visabud.app.visabud_multiplatform.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Divider
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import online.visabud.app.visabud_multiplatform.ai.CostEstimator
import online.visabud.app.visabud_multiplatform.ai.defaultEmbedderOrNull
import online.visabud.app.visabud_multiplatform.ai.RoadmapGenerator
import online.visabud.app.visabud_multiplatform.ai.VisaChecklistTool
import online.visabud.app.visabud_multiplatform.ai.defaultLlmFnOrNull
import online.visabud.app.visabud_multiplatform.data.DataModule
import online.visabud.app.visabud_multiplatform.data.Roadmap
import online.visabud.app.visabud_multiplatform.platform.saveTextFile

@Composable
fun SavedScreen(paddingValues: PaddingValues, onRequestOpenSettings: () -> Unit = {}) {
    val scope = rememberCoroutineScope()

    // Roadmaps state
    var roadmaps by remember { mutableStateOf<List<Roadmap>>(emptyList()) }
    var selectedRoadmap by remember { mutableStateOf<Roadmap?>(null) }
    var roadmapPreview by remember { mutableStateOf("") }

    // Checklists state
    var checklists by remember { mutableStateOf<List<online.visabud.app.visabud_multiplatform.data.Checklist>>(emptyList()) }
    var selectedChecklist by remember { mutableStateOf<online.visabud.app.visabud_multiplatform.data.Checklist?>(null) }
    var checklistPreview by remember { mutableStateOf("") }

    var showIncompleteDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        roadmaps = runCatching { DataModule.roadmaps.list() }.getOrElse { emptyList() }
        checklists = runCatching { DataModule.checklists.list() }.getOrElse { emptyList() }
    }

    // Generate roadmap dialog
    var showGenRoadmap by remember { mutableStateOf(false) }
    var destination by remember { mutableStateOf("") }
    var goal by remember { mutableStateOf("") }

    // Generate checklist dialog
    var showGenChecklist by remember { mutableStateOf(false) }
    var ckDestination by remember { mutableStateOf("") }
    var ckVisaType by remember { mutableStateOf("") }
    var ckNotes by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(paddingValues)
    ) {
        // Actions
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilledTonalButton(onClick = {
                scope.launch {
                    val profile = runCatching { DataModule.profiles.getProfile() }.getOrNull()
                    val complete = profile?.nationality?.isNotBlank() == true &&
                            (!profile.education.isNullOrBlank() || profile.workYears != null)
                    if (complete) showGenRoadmap = true else showIncompleteDialog = true
                }
            }) {
                Icon(Icons.Outlined.Map, contentDescription = null)
                Text("  Generate Roadmap")
            }
            FilledTonalButton(onClick = {
                scope.launch {
                    val profile = runCatching { DataModule.profiles.getProfile() }.getOrNull()
                    val complete = profile?.nationality?.isNotBlank() == true &&
                            (!profile.education.isNullOrBlank() || profile.workYears != null)
                    if (complete) showGenChecklist = true else showIncompleteDialog = true
                }
            }) {
                Icon(Icons.Outlined.Checklist, contentDescription = null)
                Text("  Generate Checklist")
            }
        }

        // Content list with sections
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Roadmaps section header
            item {
                Text("Your Roadmaps", modifier = Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.titleMedium)
                Divider(modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
                if (roadmaps.isEmpty()) {
                    Text("No roadmaps yet. Generate one to get started.", modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            items(roadmaps) { r ->
                SavedItemCard(
                    roadmap = r,
                    onClick = {
                        scope.launch {
                            val text = buildRoadmapWithCosts(r)
                            roadmapPreview = text
                            selectedRoadmap = r
                        }
                    },
                    onDownload = {
                        scope.launch {
                            val text = buildRoadmapWithCosts(r)
                            val fname = sanitizeFilename(r.title.ifBlank { "roadmap" }) + ".md"
                            saveTextFile(fname, text)
                        }
                    }
                )
            }

            // Checklists section header
            item {
                Text("Your Checklists", modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.titleMedium)
                Divider(modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
                if (checklists.isEmpty()) {
                    Text("No checklists yet. Generate a personalized checklist.", modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            items(checklists) { c ->
                ChecklistItemCard(
                    checklist = c,
                    onClick = {
                        checklistPreview = c.humanText
                        selectedChecklist = c
                    },
                    onDownload = {
                        val fname = sanitizeFilename(c.title.ifBlank { "checklist" }) + ".md"
                        saveTextFile(fname, c.humanText)
                    }
                )
            }
        }
    }

    if (showGenRoadmap) {
        GenerateDialog(
            destination = destination,
            goal = goal,
            onDestinationChange = { destination = it },
            onGoalChange = { goal = it },
            onDismiss = { showGenRoadmap = false },
            onGenerate = {
                showGenRoadmap = false
                scope.launch {
                    val profile = runCatching { DataModule.profiles.getProfile() }.getOrNull()
                    val title = aiTitle(destination, goal)
                    val opts = RoadmapGenerator.GenerateOptions(
                        useRepoRetrieval = true,
                        persist = true,
                        roadmapTitle = title
                    )
                    val embedder = runCatching { defaultEmbedderOrNull() }.getOrNull()
                    val llm = runCatching { defaultLlmFnOrNull() }.getOrNull()
                    val p = profile ?: online.visabud.app.visabud_multiplatform.data.UserProfile()
                    RoadmapGenerator.generate(p, destination, goal, embedder, llm, opts)
                    roadmaps = runCatching { DataModule.roadmaps.list() }.getOrElse { emptyList() }
                }
            }
        )
    }

    if (showGenChecklist) {
        GenerateChecklistDialog(
            destination = ckDestination,
            visaType = ckVisaType,
            notes = ckNotes,
            onDestinationChange = { ckDestination = it },
            onVisaTypeChange = { ckVisaType = it },
            onNotesChange = { ckNotes = it },
            onDismiss = { showGenChecklist = false },
            onGenerate = {
                showGenChecklist = false
                scope.launch {
                    val profile = runCatching { DataModule.profiles.getProfile() }.getOrNull()
                    val ck = VisaChecklistTool.generate(profile, ckDestination, ckVisaType)
                    val human = VisaChecklistTool.buildHumanReadable(ck)
                    val entity = online.visabud.app.visabud_multiplatform.data.Checklist(
                        id = randomId(),
                        title = buildChecklistTitle(ckDestination, ckVisaType),
                        destination = ckDestination,
                        visaType = ckVisaType,
                        humanText = human,
                        jsonPayload = VisaChecklistTool.toJson(ck),
                        createdAt = now(),
                        updatedAt = now()
                    )
                    runCatching { DataModule.checklists.upsert(entity) }
                    checklists = runCatching { DataModule.checklists.list() }.getOrElse { emptyList() }
                }
            }
        )
    }

    if (selectedRoadmap != null) {
        AlertDialog(
            onDismissRequest = { selectedRoadmap = null },
            confirmButton = { TextButton(onClick = { selectedRoadmap = null }) { Text("Close") } },
            title = { Text(selectedRoadmap!!.title, fontWeight = FontWeight.SemiBold) },
            text = { Text(roadmapPreview) }
        )
    }

    if (selectedChecklist != null) {
        AlertDialog(
            onDismissRequest = { selectedChecklist = null },
            confirmButton = { TextButton(onClick = { selectedChecklist = null }) { Text("Close") } },
            title = { Text(selectedChecklist!!.title, fontWeight = FontWeight.SemiBold) },
            text = { Text(checklistPreview) }
        )
    }

    if (showIncompleteDialog) {
        AlertDialog(
            onDismissRequest = { showIncompleteDialog = false },
            confirmButton = {
                Button(onClick = {
                    showIncompleteDialog = false
                    onRequestOpenSettings()
                }) { Text("Open Settings") }
            },
            dismissButton = { TextButton(onClick = { showIncompleteDialog = false }) { Text("Cancel") } },
            title = { Text("Complete your profile") },
            text = { Text("Please complete your profile (nationality + education or work years) in Settings to generate roadmaps or checklists.") }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SavedItemCard(roadmap: Roadmap, onClick: () -> Unit, onDownload: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clickable { onClick() },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        ListItem(
            headlineContent = { Text(roadmap.title, fontWeight = FontWeight.SemiBold) },
            supportingContent = { Text(roadmap.description ?: "Visa roadmap") },
            trailingContent = {
                IconButton(onClick = onDownload) {
                    Icon(Icons.Outlined.Download, contentDescription = "Download")
                }
            },
            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        )
    }
}

@Composable
private fun GenerateDialog(
    destination: String,
    goal: String,
    onDestinationChange: (String) -> Unit,
    onGoalChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onGenerate: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            Button(onClick = onGenerate, enabled = destination.isNotBlank() && goal.isNotBlank()) {
                Text("Generate")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
        title = { Text("New Visa Roadmap") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = destination, onValueChange = onDestinationChange, label = { Text("Destination Country") })
                OutlinedTextField(value = goal, onValueChange = onGoalChange, label = { Text("Goal (study, work, immigration, tourist)") })
            }
        }
    )
}

private suspend fun buildRoadmapWithCosts(r: Roadmap): String {
    // Rebuild human-readable roadmap text from stored JSON, then append costs
    val paths = try { Json.decodeFromString<List<RoadmapGenerator.RoadmapPathDTO>>(r.stepsJson) } catch (_: Throwable) { emptyList() }
    val roadmapText = RoadmapGenerator.buildHumanReadable(paths)
    val (dest, goal) = parseDestGoal(r)
    val costText = try {
        val profile = runCatching { DataModule.profiles.getProfile() }.getOrNull()
        val estimate = CostEstimator.estimate(profile, dest, goal, null)
        "\n\nCost breakdown (approx.):\n" + CostEstimator.buildHumanReadable(estimate)
    } catch (_: Throwable) { "" }
    return roadmapText + costText
}

private fun parseDestGoal(r: Roadmap): Pair<String?, String?> {
    val d = r.description ?: return null to null
    // Format: "Auto-generated roadmap for $destination ($goal)"
    val regex = Regex("Auto-generated roadmap for (.+) \\((.+)\\)")
    val m = regex.find(d)
    return if (m != null) m.groupValues[1] to m.groupValues[2] else null to null
}

private suspend fun aiTitle(destination: String, goal: String): String {
    val llm = try { defaultLlmFnOrNull() } catch (_: Throwable) { null }
    val sys = "You are a helpful assistant that writes a concise, catchy document title (max 7 words)."
    val user = "Create a title for a visa roadmap to $destination for $goal."
    return try {
        val raw = llm?.invoke(sys, user)?.trim().orEmpty()
        raw.ifBlank { fallbackTitle(destination, goal) }
    } catch (_: Throwable) {
        fallbackTitle(destination, goal)
    }
}

private fun fallbackTitle(destination: String, goal: String): String =
    "${'$'}destination ${'$'}{goal.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }} Roadmap".trim()

private fun sanitizeFilename(name: String): String = name.replace(Regex("[^A-Za-z0-9_. -]"), "").replace(' ', '_')

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChecklistItemCard(
    checklist: online.visabud.app.visabud_multiplatform.data.Checklist,
    onClick: () -> Unit,
    onDownload: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clickable { onClick() },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        ListItem(
            headlineContent = { Text(checklist.title, fontWeight = FontWeight.SemiBold) },
            supportingContent = { Text("${'$'}{checklist.destination} — ${'$'}{checklist.visaType} checklist") },
            trailingContent = {
                IconButton(onClick = onDownload) {
                    Icon(Icons.Outlined.Download, contentDescription = "Download")
                }
            },
            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        )
    }
}

@Composable
private fun GenerateChecklistDialog(
    destination: String,
    visaType: String,
    notes: String,
    onDestinationChange: (String) -> Unit,
    onVisaTypeChange: (String) -> Unit,
    onNotesChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onGenerate: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            Button(onClick = onGenerate, enabled = destination.isNotBlank() && visaType.isNotBlank()) {
                Text("Generate")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text("New Visa Checklist") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = destination, onValueChange = onDestinationChange, label = { Text("Destination Country") })
                OutlinedTextField(value = visaType, onValueChange = onVisaTypeChange, label = { Text("Visa Type (tourist, study, work, immigration)") })
                OutlinedTextField(value = notes, onValueChange = onNotesChange, label = { Text("Notes (optional)") })
            }
        }
    )
}

private fun buildChecklistTitle(destination: String, visaType: String): String =
    "${'$'}destination ${'$'}{visaType.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }} Checklist".trim()

private fun randomId(): String = "ck_" + kotlin.random.Random.nextLong().toString(16)

// Keep consistent with other commonMain placeholders; avoid KMP time API issues here
private fun now(): Long = 0L
