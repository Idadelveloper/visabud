package online.visabud.app.visabud_multiplatform

import android.os.Parcelable
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import kotlinx.parcelize.Parcelize
import online.visabud.app.visabud_multiplatform.theme.VisabudTheme
import online.visabud.app.visabud_multiplatform.ui.HomeHeader
import online.visabud.app.visabud_multiplatform.ui.HomeScreen
import online.visabud.app.visabud_multiplatform.ui.ToolsScreen
import online.visabud.app.visabud_multiplatform.ui.WelcomeScreen
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
        var screen by rememberSaveable { mutableStateOf(initialScreen) }

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
    var currentScreen by rememberSaveable { mutableStateOf(initialScreen) }

    Scaffold(
        topBar = {
            when (currentScreen) {
                MainScreen.Home -> HomeHeader()
                MainScreen.Tools -> TopAppBar(title = { Text("Tools") })
                MainScreen.Profile -> TopAppBar(title = { Text("Profile") })
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
                    val items = listOf(MainScreen.Home, MainScreen.Tools, MainScreen.Profile)
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
            MainScreen.Tools -> ToolsScreen(paddingValues)
            MainScreen.Profile -> { /* TODO: Profile Screen */
            }
            MainScreen.Chat -> online.visabud.app.visabud_multiplatform.ui.ChatScreen(paddingValues)
        }
    }
}

@Parcelize
private sealed class Screen : Parcelable {
    @Parcelize
    data object Welcome : Screen()
    @Parcelize
    data class Main(val startScreen: MainScreen) : Screen()
}

@Parcelize
private enum class MainScreen : Parcelable {
    Home,
    Tools,
    Profile,
    Chat
}
