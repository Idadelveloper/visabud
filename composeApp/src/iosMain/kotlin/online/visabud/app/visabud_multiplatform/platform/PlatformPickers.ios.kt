package online.visabud.app.visabud_multiplatform.platform

import androidx.compose.runtime.Composable

@Composable
actual fun rememberPlatformPickers(onPicked: (String) -> Unit): PlatformPickers {
    // iOS picker not implemented in this MVP; return no-op handlers
    return PlatformPickers(
        pickImage = {},
        pickFile = {}
    )
}
