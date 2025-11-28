package online.visabud.app.visabud_multiplatform

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.safeContentPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import online.visabud.app.visabud_multiplatform.theme.VisabudTheme
import online.visabud.app.visabud_multiplatform.ui.WelcomeScreen
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.ui.tooling.preview.Preview

import visabud_multiplatform.composeapp.generated.resources.Res
import visabud_multiplatform.composeapp.generated.resources.click_me
import visabud_multiplatform.composeapp.generated.resources.compose_multiplatform
import visabud_multiplatform.composeapp.generated.resources.compose_prefix
import visabud_multiplatform.composeapp.generated.resources.visabud_logo

@Composable
@Preview
fun App() {
    VisabudTheme {
        WelcomeScreen(
            onContinueAsGuest = { /* TODO: navigate to app as guest */ },
            onLoginSignup = { /* TODO: navigate to auth flow */ }
        )
    }
}