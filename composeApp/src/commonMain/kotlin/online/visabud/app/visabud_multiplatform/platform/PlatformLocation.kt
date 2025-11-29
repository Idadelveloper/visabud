package online.visabud.app.visabud_multiplatform.platform

// Simple cross-platform location result
 data class LocationResult(val latitude: Double, val longitude: Double)

// Try to get current location if permissions are already granted. Return null if unavailable/denied.
 expect suspend fun getCurrentLocationOrNull(): LocationResult?
