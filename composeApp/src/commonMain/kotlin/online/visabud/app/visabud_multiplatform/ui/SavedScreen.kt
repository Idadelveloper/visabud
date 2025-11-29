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
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import online.visabud.app.visabud_multiplatform.ai.CostEstimator
import online.visabud.app.visabud_multiplatform.ai.defaultEmbedderOrNull
import online.visabud.app.visabud_multiplatform.ai.RoadmapGenerator
import online.visabud.app.visabud_multiplatform.ai.defaultLlmFnOrNull
import online.visabud.app.visabud_multiplatform.data.DataModule
import online.visabud.app.visabud_multiplatform.data.Roadmap
import online.visabud.app.visabud_multiplatform.platform.saveTextFile

@Composable
fun SavedScreen(paddingValues: PaddingValues, onRequestOpenSettings: () -> Unit = {}) {
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf<List<Roadmap>>(emptyList()) }
    var selected by remember { mutableStateOf<Roadmap?>(null) }
    var previewText by remember { mutableStateOf("") }
    var showIncompleteDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        items = runCatching { DataModule.roadmaps.list() }.getOrElse { emptyList() }
    }

    // Generate roadmap dialog
    var showGen by remember { mutableStateOf(false) }
    var destination by remember { mutableStateOf("") }
    var goal by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(paddingValues)
    ) {
        Row(modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = {
                scope.launch {
                    val profile = runCatching { DataModule.profiles.getProfile() }.getOrNull()
                    val complete = profile?.nationality?.isNotBlank() == true &&
                            (!profile.education.isNullOrBlank() || profile.workYears != null)
                    if (complete) {
                        showGen = true
                    } else {
                        showIncompleteDialog = true
                    }
                }
            }) {
                Icon(Icons.Outlined.Map, contentDescription = null)
                Text("  Generate Visa Roadmap")
            }
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(items) { r ->
                SavedItemCard(roadmap = r,
                    onClick = {
                        scope.launch {
                            val text = buildRoadmapWithCosts(r)
                            previewText = text
                            selected = r
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
        }
    }

    if (showGen) {
        GenerateDialog(
            destination = destination,
            goal = goal,
            onDestinationChange = { destination = it },
            onGoalChange = { goal = it },
            onDismiss = { showGen = false },
            onGenerate = {
                showGen = false
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
                    items = runCatching { DataModule.roadmaps.list() }.getOrElse { emptyList() }
                }
            }
        )
    }

    if (selected != null) {
        AlertDialog(
            onDismissRequest = { selected = null },
            confirmButton = {
                TextButton(onClick = { selected = null }) { Text("Close") }
            },
            title = { Text(selected!!.title, fontWeight = FontWeight.SemiBold) },
            text = {
                Text(previewText)
            }
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
            dismissButton = {
                TextButton(onClick = { showIncompleteDialog = false }) { Text("Cancel") }
            },
            title = { Text("Complete your profile") },
            text = {
                Text("Please complete your profile (nationality + education or work years) in Settings to generate roadmaps or checklists.")
            }
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
