package online.visabud.app.visabud_multiplatform.platform

import androidx.compose.runtime.Composable

@Composable
actual fun rememberLocationPermissionRequester(onResult: (Boolean) -> Unit): LocationPermissionRequester {
    return LocationPermissionRequester { onResult(false) }
}

actual fun isLocationPermissionGranted(): Boolean = false
