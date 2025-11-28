package online.visabud.app.visabud_multiplatform

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Construction
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
        var screen by remember { mutableStateOf<Screen>(Screen.Welcome) }

        when (val s = screen) {
            is Screen.Welcome -> WelcomeScreen(
                onContinueAsGuest = { screen = Screen.Main(MainScreen.Home) },
                onLoginSignup = { screen = Screen.Main(MainScreen.Home) }
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
                MainScreen.Profile -> TopAppBar(title = { Text("Profile") })
            }
        },
        bottomBar = {
            BottomAppBar {
                val items = listOf(MainScreen.Home, MainScreen.Tools, MainScreen.Profile)
                val icons = listOf(Icons.Outlined.Home, Icons.Outlined.Construction, Icons.Outlined.Person)

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
    ) { paddingValues ->
        when (currentScreen) {
            MainScreen.Home -> HomeScreen(paddingValues)
            MainScreen.Tools -> ToolsScreen(paddingValues)
            MainScreen.Profile -> { /* TODO: Profile Screen */ }
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
    Profile
}
