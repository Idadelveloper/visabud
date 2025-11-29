package online.visabud.app.visabud_multiplatform

// import android.os.Parcelable (removed for KMP common)
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Message
import androidx.compose.material.icons.outlined.Construction
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
// removed parcelize import for KMP common
import online.visabud.app.visabud_multiplatform.theme.VisabudTheme
import online.visabud.app.visabud_multiplatform.ui.ChatScreen
import online.visabud.app.visabud_multiplatform.ui.HomeHeader
import online.visabud.app.visabud_multiplatform.ui.HomeScreen
import online.visabud.app.visabud_multiplatform.ui.ToolsScreen
import online.visabud.app.visabud_multiplatform.ui.DocumentReviewScreen
import online.visabud.app.visabud_multiplatform.ui.WelcomeScreen
import online.visabud.app.visabud_multiplatform.ui.EmbassyLocatorScreen
import online.visabud.app.visabud_multiplatform.ui.CostCalculatorScreen
import online.visabud.app.visabud_multiplatform.ui.SettingsScreen
import org.jetbrains.compose.ui.tooling.preview.Preview


@Composable
@Preview
fun App() {
    VisabudTheme {
        val initialScreen = if (AppSettings.hasBeenWelcomed) {
            Screen.Main(MainScreen.Home)
        } else {
            Screen.Welcome
        }
        var screen by remember { mutableStateOf(initialScreen) }

        when (val s = screen) {
            is Screen.Welcome -> WelcomeScreen(
                onContinueAsGuest = {
                    AppSettings.hasBeenWelcomed = true
                    screen = Screen.Main(MainScreen.Home)
                },
                onLoginSignup = {
                    AppSettings.hasBeenWelcomed = true
                    screen = Screen.Main(MainScreen.Home)
                }
            )
            is Screen.Main -> MainScreen(
                initialScreen = s.startScreen,
                onNavigate = { nextScreen -> screen = Screen.Main(nextScreen) }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScreen(initialScreen: MainScreen, onNavigate: (MainScreen) -> Unit) {
    var currentScreen by remember { mutableStateOf(initialScreen) }

    Scaffold(
        topBar = {
            when (currentScreen) {
                MainScreen.Home -> HomeHeader()
                MainScreen.Tools -> TopAppBar(title = { Text("Tools") })
                MainScreen.EmbassyLocator -> TopAppBar(title = { Text("Embassy Locator") })
                MainScreen.CostCalculator -> TopAppBar(title = { Text("Cost Calculator") })
                MainScreen.Settings -> TopAppBar(title = { Text("Settings") })
                MainScreen.DocumentReview -> TopAppBar(title = { Text("Document Review") })
                MainScreen.Chat -> TopAppBar(
                    title = { Text("VisaBud Chat") }, 
                    navigationIcon = {
                        IconButton(onClick = { currentScreen = MainScreen.Home }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                )
            }
        },
        floatingActionButton = {
            if (currentScreen != MainScreen.Chat) {
                FloatingActionButton(
                    onClick = { currentScreen = MainScreen.Chat },
                    containerColor = MaterialTheme.colorScheme.primary
                ) {
                    Icon(Icons.Filled.Message, contentDescription = "New Chat")
                }
            }
        },
        bottomBar = {
            if (currentScreen != MainScreen.Chat) {
                BottomAppBar {
                    val items = listOf(MainScreen.Home, MainScreen.Tools, MainScreen.Settings)
                    val icons =
                        listOf(Icons.Outlined.Home, Icons.Outlined.Construction, Icons.Outlined.Person)

                    items.forEachIndexed { index, screen ->
                        NavigationBarItem(
                            selected = currentScreen == screen,
                            onClick = { currentScreen = screen },
                            icon = { Icon(icons[index], contentDescription = screen.name) },
                            label = { Text(screen.name) }
                        )
                    }
                }
            }
        }
    ) { paddingValues ->
        when (currentScreen) {
            MainScreen.Home -> HomeScreen(
                paddingValues,
                onNavigateToChat = { currentScreen = MainScreen.Chat })
            MainScreen.Tools -> ToolsScreen(
                paddingValues,
                onOpenDocumentReview = { currentScreen = MainScreen.DocumentReview },
                onOpenEmbassyLocator = { currentScreen = MainScreen.EmbassyLocator },
                onOpenCostCalculator = { currentScreen = MainScreen.CostCalculator }
            )
            MainScreen.Settings -> SettingsScreen(paddingValues)
            MainScreen.Chat -> ChatScreen(paddingValues)
            MainScreen.DocumentReview -> DocumentReviewScreen(paddingValues)
            MainScreen.EmbassyLocator -> EmbassyLocatorScreen(paddingValues)
            MainScreen.CostCalculator -> CostCalculatorScreen(paddingValues)
        }
    }
}

private sealed class Screen {
    data object Welcome : Screen()
    data class Main(val startScreen: MainScreen) : Screen()
}

private enum class MainScreen {
    Home,
    Tools,
    Settings,
    Chat,
    DocumentReview,
    EmbassyLocator,
    CostCalculator
}
