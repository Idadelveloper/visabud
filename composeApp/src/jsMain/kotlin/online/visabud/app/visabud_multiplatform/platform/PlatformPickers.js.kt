package online.visabud.app.visabud_multiplatform.platform

import androidx.compose.runtime.Composable

@Composable
actual fun rememberPlatformPickers(onPicked: (String) -> Unit): PlatformPickers {
    // Web: no native picker wired here; you can add <input type="file"> integration later.
    return PlatformPickers(
        pickImage = {},
        pickFile = {}
    )
}
