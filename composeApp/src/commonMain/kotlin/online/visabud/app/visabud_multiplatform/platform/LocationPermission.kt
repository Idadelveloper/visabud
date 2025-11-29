package online.visabud.app.visabud_multiplatform.platform

import androidx.compose.runtime.Composable

/** Cross-platform location permission requester facade. */
class LocationPermissionRequester(val request: () -> Unit)

@Composable
expect fun rememberLocationPermissionRequester(onResult: (Boolean) -> Unit): LocationPermissionRequester

/** Returns true if location permission is already granted on this platform. */
expect fun isLocationPermissionGranted(): Boolean
