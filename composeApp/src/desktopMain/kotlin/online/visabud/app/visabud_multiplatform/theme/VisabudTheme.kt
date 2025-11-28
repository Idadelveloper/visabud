@file:kotlin.jvm.JvmName("VisabudThemeDesktop")
package online.visabud.app.visabud_multiplatform.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable

@Composable
internal actual fun platformColorScheme(darkTheme: Boolean, dynamicColor: Boolean): ColorScheme {
    return if (darkTheme) {
        darkScheme
    } else {
        lightScheme
    }
}
