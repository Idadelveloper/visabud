package online.visabud.app.visabud_multiplatform

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.cactus.CactusContextInitializer

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        CactusContextInitializer.initialize(this)

        // Set global context for Android Toasts
        online.visabud.app.visabud_multiplatform.ai.ToastPlatform.appContext = applicationContext
        // Initialize persistent data repositories
        online.visabud.app.visabud_multiplatform.data.ensureDataModuleInitialized()

        setContent {
            App()
        }
    }
}

@Preview
@Composable
fun AppAndroidPreview() {
    App()
}