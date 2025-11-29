package online.visabud.app.visabud_multiplatform.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import online.visabud.app.visabud_multiplatform.data.DataModule
import online.visabud.app.visabud_multiplatform.data.DocumentMeta
import online.visabud.app.visabud_multiplatform.doc.documentPipeline
import online.visabud.app.visabud_multiplatform.doc.mapToJson

@Composable
fun DocumentReviewScreen(paddingValues: PaddingValues) {
    val scope = rememberCoroutineScope()
    var imagePath by remember { mutableStateOf("") }
    var targetVisa by remember { mutableStateOf("") }
    var parsedFields by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var reviewJson by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(paddingValues)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Document Review", style = MaterialTheme.typography.titleLarge)
        OutlinedTextField(
            value = imagePath,
            onValueChange = { imagePath = it },
            label = { Text("Image file path or URI") },
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = targetVisa,
            onValueChange = { targetVisa = it },
            label = { Text("Target visa (e.g., UK Visitor, US B1/B2)") },
            modifier = Modifier.fillMaxWidth()
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                scope.launch {
                    status = "Extracting fields…"
                    try {
                        val fields = documentPipeline().extractFields(imagePath)
                        parsedFields = fields
                        // Persist a simple DocumentMeta record
                        val doc = DocumentMeta(
                            id = "doc_" + (imagePath.hashCode().toString()),
                            type = "passport",
                            filename = imagePath.substringAfterLast('/')
                                .ifBlank { imagePath },
                            parsedFieldsJson = mapToJson(fields),
                            uploadedAt = 0L,
                            encryptedPath = imagePath // placeholder: path stored as-is for demo
                        )
                        DataModule.documents.add(doc)
                        // Update user profile: add saved doc ID and fill missing fields if available
                        try {
                            val existing = DataModule.profiles.getProfile() ?: online.visabud.app.visabud_multiplatform.data.UserProfile()
                            val updated = existing.copy(
                                savedDocs = (existing.savedDocs + doc.id).distinct(),
                                name = existing.name ?: fields["name"],
                                dob = existing.dob ?: fields["dob"]
                            )
                            DataModule.profiles.upsertProfile(updated)
                        } catch (_: Throwable) {}
                        status = "Fields extracted."
                    } catch (e: Throwable) {
                        status = "Extraction failed: ${e.message}"
                    }
                }
            }) { Text("Extract Fields") }
            Button(enabled = parsedFields.isNotEmpty() && targetVisa.isNotBlank(), onClick = {
                scope.launch {
                    status = "Reviewing document…"
                    try {
                        val json = documentPipeline().reviewDocument(parsedFields, targetVisa)
                        reviewJson = json
                        status = "Review ready."
                    } catch (e: Throwable) {
                        status = "Review failed: ${e.message}"
                    }
                }
            }) { Text("Run Review") }
        }

        if (status.isNotBlank()) Text(status, color = MaterialTheme.colorScheme.primary)

        if (parsedFields.isNotEmpty()) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Parsed Fields", style = MaterialTheme.typography.titleMedium)
                    parsedFields.forEach { (k, v) -> Text("$k: $v") }
                }
            }
        }

        if (reviewJson.isNotBlank()) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Review JSON", style = MaterialTheme.typography.titleMedium)
                    Text(reviewJson)
                }
            }
        }
    }
}
