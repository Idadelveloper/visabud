package online.visabud.app.visabud_multiplatform.platform

import androidx.compose.runtime.Composable

@Composable
actual fun rememberLocationPermissionRequester(onResult: (Boolean) -> Unit): LocationPermissionRequester {
    // iOS permissions not implemented in this KMP sample; return a no-op requester
    return LocationPermissionRequester {
        onResult(false)
    }
}

actual fun isLocationPermissionGranted(): Boolean = false
