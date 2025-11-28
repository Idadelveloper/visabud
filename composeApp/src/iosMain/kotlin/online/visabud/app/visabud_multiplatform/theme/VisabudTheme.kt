@file:Suppress("UnusedImport")
package online.visabud.app.visabud_multiplatform.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable

@Composable
internal actual fun platformColorScheme(darkTheme: Boolean, dynamicColor: Boolean): ColorScheme {
    // iOS does not support Material dynamic color; fall back to our static schemes.
    return if (darkTheme) darkScheme else lightScheme
}
