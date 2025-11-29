package online.visabud.app.visabud_multiplatform.platform

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@SuppressLint("MissingPermission")
actual suspend fun getCurrentLocationOrNull(): LocationResult? = withContext(Dispatchers.Default) {
    val ctx: Context = online.visabud.app.visabud_multiplatform.ai.ToastPlatform.contextOrNull() ?: return@withContext null
    val fine = ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    val coarse = ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    if (!fine && !coarse) return@withContext null
    val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return@withContext null
    val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
    var best: Location? = null
    for (p in providers) {
        try {
            val loc = lm.getLastKnownLocation(p)
            if (loc != null && (best == null || loc.accuracy < best!!.accuracy)) best = loc
        } catch (_: SecurityException) {
        }
    }
    best?.let { LocationResult(it.latitude, it.longitude) }
}