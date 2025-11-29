package online.visabud.app.visabud_multiplatform.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForwardIos
import androidx.compose.material.icons.outlined.Calculate
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.QuestionAnswer
import androidx.compose.material.icons.outlined.RateReview
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.ui.tooling.preview.Preview
import online.visabud.app.visabud_multiplatform.data.ChecklistEntity
import online.visabud.app.visabud_multiplatform.data.DataModule
import online.visabud.app.visabud_multiplatform.data.Roadmap

private val tools = listOf(
    Tool("Cost Calculator", "Estimate your visa application expenses.", Icons.Outlined.Calculate),
    Tool("Embassy Locator", "Find embassies and consulates worldwide.", Icons.Outlined.LocationOn),
    Tool("Visa Roadmap", "Get a step-by-step application plan.", Icons.Outlined.Map),
    Tool("Document Checklist", "Generate a personalized list of required documents.", Icons.Outlined.Checklist),
    Tool("Document Review", "Have your documents checked by AI.", Icons.Outlined.RateReview),
    Tool("Interview Prep", "Practice common visa interview questions.", Icons.Outlined.QuestionAnswer),
)

@Composable
fun ToolsScreen(paddingValues: PaddingValues, onOpenDocumentReview: () -> Unit = {}, onOpenEmbassyLocator: () -> Unit = {}, onOpenCostCalculator: () -> Unit = {}) {
    var roadmaps by remember { mutableStateOf<List<Roadmap>>(emptyList()) }
    var checklists by remember { mutableStateOf<List<ChecklistEntity>>(emptyList()) }

    LaunchedEffect(Unit) {
        // Load user content from repositories; in-memory impls are fast
        roadmaps = DataModule.roadmaps.list()
        checklists = DataModule.checklists.list()
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(paddingValues),
        contentPadding = PaddingValues(vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(tools) { tool ->
            val click: (() -> Unit)? = when (tool.label) {
                "Document Review" -> onOpenDocumentReview
                "Embassy Locator" -> onOpenEmbassyLocator
                "Cost Calculator" -> onOpenCostCalculator
                else -> null
            }
            ToolListItem(
                icon = tool.icon,
                title = tool.label,
                subtitle = tool.subtitle,
                modifier = Modifier.padding(horizontal = 16.dp),
                onClick = click
            )
        }

        // Your Roadmaps section
        item {
            SectionHeader(title = "Your Roadmaps")
        }
        if (roadmaps.isEmpty()) {
            item { EmptyState(text = "No saved roadmaps yet.") }
        } else {
            items(roadmaps) { rm ->
                ToolListItem(
                    icon = Icons.Outlined.Map,
                    title = rm.title,
                    subtitle = rm.description ?: "",
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            }
        }

        // Your Checklists section
        item {
            SectionHeader(title = "Your Checklists")
        }
        if (checklists.isEmpty()) {
            item { EmptyState(text = "No saved checklists yet.") }
        } else {
            items(checklists) { ck ->
                ToolListItem(
                    icon = Icons.Outlined.Checklist,
                    title = ck.title,
                    subtitle = listOfNotNull(ck.country, ck.visaType).joinToString(" · "),
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ToolListItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .let { if (onClick != null) it.clickable { onClick() } else it },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        ListItem(
            headlineContent = { Text(title, fontWeight = FontWeight.SemiBold) },
            supportingContent = { Text(subtitle) },
            leadingContent = {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(32.dp)
                )
            },
            trailingContent = {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowForwardIos,
                    contentDescription = "Go",
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            modifier = Modifier.then(if (onClick != null) Modifier.padding(0.dp) else Modifier),
            )
    }
}

private data class Tool(val label: String, val subtitle: String, val icon: ImageVector)

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
    )
}

@Composable
private fun EmptyState(text: String) {
    Column(modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(text = text, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Preview
@Composable
private fun ToolsScreenPreview() {
    online.visabud.app.visabud_multiplatform.theme.VisabudTheme {
        ToolsScreen(PaddingValues())
    }
}
