package online.visabud.app.visabud_multiplatform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import online.visabud.app.visabud_multiplatform.theme.VisabudTheme
import online.visabud.app.visabud_multiplatform.ui.HomeScreen
import online.visabud.app.visabud_multiplatform.ui.WelcomeScreen
import org.jetbrains.compose.ui.tooling.preview.Preview

@Composable
@Preview
fun App() {
    VisabudTheme {
        Content()
    }
}

@Composable
private fun Content() {
    var screen by remember { mutableStateOf<Screen>(Screen.Welcome) }

    when (screen) {
        Screen.Welcome -> WelcomeScreen(
            onContinueAsGuest = { screen = Screen.Home },
            onLoginSignup = { screen = Screen.Home }
        )
        Screen.Home -> HomeScreen()
    }
}

private sealed class Screen {
    data object Welcome : Screen()
    data object Home : Screen()
}
