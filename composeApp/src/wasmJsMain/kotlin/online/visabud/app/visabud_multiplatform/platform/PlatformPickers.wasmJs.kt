package online.visabud.app.visabud_multiplatform.platform

import androidx.compose.runtime.Composable

@Composable
actual fun rememberPlatformPickers(onPicked: (String) -> Unit): PlatformPickers {
    return PlatformPickers(
        pickImage = {},
        pickFile = {}
    )
}
