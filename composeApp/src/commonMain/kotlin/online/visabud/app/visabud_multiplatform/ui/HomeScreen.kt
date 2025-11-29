package online.visabud.app.visabud_multiplatform.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Calculate
import androidx.compose.material.icons.outlined.Flight
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.ui.tooling.preview.Preview
import visabud_multiplatform.composeapp.generated.resources.Res
import visabud_multiplatform.composeapp.generated.resources.visabud_logo
import visabud_multiplatform.composeapp.generated.resources.visabud_welcome_hero

@Composable
fun HomeScreen(
    paddingValues: PaddingValues,
    onNavigateToChat: () -> Unit
) {
    val scrollState = rememberScrollState()
    var showEmbassyPopup by remember { mutableStateOf(false) }
    var showInterviewPopup by remember { mutableStateOf(false) }
    var countrySummaryToShow by remember { mutableStateOf<String?>(null) }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(paddingValues)
            .verticalScroll(scrollState)
            .background(MaterialTheme.colorScheme.surfaceContainer)
    ) {
        HeroCard(onNavigateToChat)
        RecommendedTools(
            onEmbassyClick = { showEmbassyPopup = true },
            onInterviewClick = { showInterviewPopup = true }
        )
        CountrySpotlight(onCountryClick = { summary -> countrySummaryToShow = summary })
        RecentQueries()
    }

    if (showEmbassyPopup) {
        AlertDialog(
            onDismissRequest = { showEmbassyPopup = false },
            confirmButton = {
                Button(onClick = { showEmbassyPopup = false }) { Text("Close") }
            },
            title = { Text("Embassy Locator") },
            text = { Text("Find embassies and consulates worldwide. Go to Tools → Embassy Locator for full experience.") }
        )
    }

    if (showInterviewPopup) {
        AlertDialog(
            onDismissRequest = { showInterviewPopup = false },
            confirmButton = { Button(onClick = { showInterviewPopup = false }) { Text("Got it") } },
            title = { Text("Visa Interview — Coming soon") },
            text = { Text("We’re building a guided practice with common questions and AI feedback. Stay tuned!") }
        )
    }

    countrySummaryToShow?.let { summary ->
        AlertDialog(
            onDismissRequest = { countrySummaryToShow = null },
            confirmButton = { Button(onClick = { countrySummaryToShow = null }) { Text("Close") } },
            title = { Text("Visa Summary") },
            text = { Text(summary) }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeHeader() {
    TopAppBar(
        title = {
            Image(
                painter = painterResource(Res.drawable.visabud_logo),
                contentDescription = "Visabud Logo",
                modifier = Modifier.height(32.dp)
            )
        },
        actions = {
            Image(
                painter = painterResource(Res.drawable.visabud_welcome_hero),
                contentDescription = "Profile Photo",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
            )
            Spacer(modifier = Modifier.width(16.dp))
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest
        )
    )
}

@Composable
private fun HeroCard(onNavigateToChat: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Your Visa Companion",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Ask anything. Plan your journey.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = onNavigateToChat,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary
                    )
                ) {
                    Text("Ask VisaBud")
                }
            }
            // TODO: Replace with actual illustration
            Icon(
                Icons.Outlined.Flight,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
    }
}

@Composable
private fun RecommendedTools(
    onEmbassyClick: () -> Unit,
    onInterviewClick: () -> Unit
) {
    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
        Text(
            "Tools",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(bottom = 12.dp)
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(modifier = Modifier.weight(1f)) {
                QuickActionItem(
                    icon = Icons.Outlined.LocationOn,
                    label = "Embassy Locator",
                    onClick = onEmbassyClick
                )
            }
            Box(modifier = Modifier.weight(1f)) {
                QuickActionItem(
                    icon = Icons.Outlined.Map,
                    label = "Visa Interview",
                    badge = "Coming soon",
                    onClick = onInterviewClick
                )
            }
            // Intentionally only two tools per requirements
        }
    }
}

@Composable
private fun QuickActionItem(
    icon: ImageVector,
    label: String,
    badge: String? = null,
    onClick: (() -> Unit)? = null
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .let { if (onClick != null) it.clickable { onClick() } else it },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
            Spacer(Modifier.height(8.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 4.dp)
            )
            if (badge != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = badge,
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 9.sp,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@Composable
private fun CountrySpotlight(onCountryClick: (String) -> Unit) {
    val scrollState = rememberScrollState()
    Column(modifier = Modifier.padding(top = 24.dp)) {
        Text(
            "Country Spotlight",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier
                .horizontalScroll(scrollState)
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            CountryCard("🇺🇸", "United States") {
                onCountryClick(
                    "The U.S. requires most non-visa-exempt travelers to apply for a B-1 (Business) or B-2 (Tourism) Visa. The core requirement is proving Non-Immigrant Intent—that you have strong ties to your home country and will leave after your temporary stay. Submit DS-160, pay the fee, and attend an in-person embassy/consulate interview with biometrics."
                )
            }
            CountryCard("🇬🇧", "United Kingdom") {
                onCountryClick(
                    "Apply for a UK Standard Visitor Visa to visit up to six months. Show you’re a genuine visitor with sufficient funds and a clear itinerary. Complete the online application, pay the fee, and attend a VAC appointment to submit biometrics and documents."
                )
            }
            CountryCard("🇨🇦", "Canada") {
                onCountryClick(
                    "Canada’s Temporary Resident Visa (Visitor Visa) requires proof of funds and intent to depart. Apply online, receive a Biometric Instruction Letter (BIL), then book a VAC appointment to give biometrics (usually valid for 10 years)."
                )
            }
            CountryCard("🇦🇺", "Australia") {
                onCountryClick(
                    "Australia’s Visitor Visa (Subclass 600) uses a streamlined online process via ImmiAccount. Meet health and character requirements; you may be asked for medicals or police certificates. Apply online, upload documents, and pay. Biometrics may be requested separately."
                )
            }
        }
    }
}

@Composable
private fun CountryCard(flag: String, name: String, onClick: (() -> Unit)? = null) {
    Card(
        modifier = Modifier
            .width(140.dp)
            .let { if (onClick != null) it.clickable { onClick() } else it },
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(flag, fontSize = 24.sp)
            Spacer(Modifier.height(8.dp))
            Text(name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
            Text("Details", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun RecentQueries() {
    Column(
        modifier = Modifier.padding(16.dp)
    ) {
        Text(
            "Recent Queries",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(bottom = 12.dp)
        )
        QueryItem("UK Visa Requirements")
        Spacer(Modifier.height(8.dp))
        QueryItem("Document Review Result")
        Spacer(Modifier.height(8.dp))
        QueryItem("Interview Tips")
    }
}

@Composable
private fun QueryItem(query: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Text(
            text = query,
            modifier = Modifier.padding(16.dp),
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

@Preview
@Composable
private fun HomeScreenPreview() {
    online.visabud.app.visabud_multiplatform.theme.VisabudTheme {
        HomeScreen(PaddingValues(), onNavigateToChat = {})
    }
}
