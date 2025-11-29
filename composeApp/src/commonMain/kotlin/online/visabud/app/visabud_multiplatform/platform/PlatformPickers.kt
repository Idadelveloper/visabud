package online.visabud.app.visabud_multiplatform.platform

import androidx.compose.runtime.Composable

/**
 * Simple cross‑platform pickers facade. On non‑Android targets it's a no‑op and callers
 * should fall back to manual path/URI entry.
 */
class PlatformPickers(
    val pickImage: () -> Unit,
    val pickFile: () -> Unit
)

@Composable
expect fun rememberPlatformPickers(onPicked: (String) -> Unit): PlatformPickers
