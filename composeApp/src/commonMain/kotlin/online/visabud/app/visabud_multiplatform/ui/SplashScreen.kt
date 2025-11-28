package online.visabud.app.visabud_multiplatform.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.material3.MaterialTheme
import org.jetbrains.compose.resources.painterResource
import visabud_multiplatform.composeapp.generated.resources.Res
import visabud_multiplatform.composeapp.generated.resources.visabud_logo

@Composable
fun SplashScreen() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center
    ) {
        Image(
            painter = painterResource(Res.drawable.visabud_logo),
            contentDescription = null
        )
    }
}
