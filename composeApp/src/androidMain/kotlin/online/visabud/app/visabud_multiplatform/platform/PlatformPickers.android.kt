package online.visabud.app.visabud_multiplatform.platform

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

@Composable
actual fun rememberPlatformPickers(onPicked: (String) -> Unit): PlatformPickers {
    val imageLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let { onPicked(it.toString()) }
    }

    val fileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let { onPicked(it.toString()) }
    }

    return remember {
        PlatformPickers(
            pickImage = { imageLauncher.launch("image/*") },
            pickFile = { fileLauncher.launch("*/*") }
        )
    }
}
