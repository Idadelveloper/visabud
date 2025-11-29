package online.visabud.app.visabud_multiplatform.embassy

import kotlin.math.*
import org.jetbrains.compose.resources.ExperimentalResourceApi
import visabud_multiplatform.composeapp.generated.resources.Res

/** Simple model for an embassy/consulate entry parsed from CSV. */
data class Embassy(
    val code: String,
    val country: String,
    val city: String,
    val lat: Double,
    val lon: Double,
    val address: String,
    val phone: String?,
    val website: String?
)

/** Offline-first repository that loads a bundled CSV of embassies. */
object EmbassyRepo {
    private const val RESOURCE_PATH = "files/embassies.csv"
    private var cache: List<Embassy>? = null

    @OptIn(ExperimentalResourceApi::class)
    suspend fun getAll(): List<Embassy> {
        cache?.let { return it }
        val bytes = Res.readBytes(RESOURCE_PATH)
        val text = bytes.decodeToString()
        val lines = text.lineSequence().filter { it.isNotBlank() }.toList()
        if (lines.size <= 1) return emptyList()
        val result = ArrayList<Embassy>(lines.size - 1)
        // Skip header
        for (i in 1 until lines.size) {
            val row = parseCsvLine(lines[i])
            if (row.size >= 8) {
                val lat = row[3].toDoubleOrNull()
                val lon = row[4].toDoubleOrNull()
                if (lat != null && lon != null) {
                    result += Embassy(
                        code = row[0],
                        country = row[1],
                        city = row[2],
                        lat = lat,
                        lon = lon,
                        address = row[5],
                        phone = row[6].ifBlank { null },
                        website = row[7].ifBlank { null }
                    )
                }
            }
        }
        cache = result
        return result
    }

    suspend fun listCountries(): List<Pair<String, String>> {
        val all = getAll()
        return all
            .asSequence()
            .map { it.code to it.country }
            .distinct()
            .sortedBy { it.second }
            .toList()
    }

    suspend fun filterByCountry(codeOrName: String): List<Embassy> {
        val all = getAll()
        val q = codeOrName.trim().lowercase()
        return all.filter { it.code.lowercase() == q || it.country.lowercase() == q }
    }

    // Very small CSV parser that tolerates commas in quotes
    private fun parseCsvLine(line: String): List<String> {
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when (c) {
                '"' -> {
                    inQuotes = !inQuotes
                }
                ',' -> {
                    if (inQuotes) sb.append(c) else {
                        out += sb.toString().trim().trim('"')
                        sb.clear()
                    }
                }
                else -> sb.append(c)
            }
            i++
        }
        out += sb.toString().trim().trim('"')
        return out
    }
}

/** Haversine distance between two lat/lon points in kilometers. */
fun distanceKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val R = 6371.0 // km
    fun rad(d: Double) = d * PI / 180.0
    val dLat = rad(lat2 - lat1)
    val dLon = rad(lon2 - lon1)
    val a = sin(dLat / 2).pow(2.0) + cos(rad(lat1)) * cos(rad(lat2)) * sin(dLon / 2).pow(2.0)
    val c = 2 * atan2(sqrt(a), sqrt(1 - a))
    return R * c
}

/** Simple result item with computed distance. */
data class EmbassyDistance(val embassy: Embassy, val distanceKm: Double)

suspend fun nearestEmbassies(lat: Double, lon: Double, limit: Int = 5): List<EmbassyDistance> {
    val all = EmbassyRepo.getAll()
    return all.asSequence()
        .map { EmbassyDistance(it, distanceKm(lat, lon, it.lat, it.lon)) }
        .sortedBy { it.distanceKm }
        .take(limit)
        .toList()
}

suspend fun nearestEmbassiesInCountry(lat: Double, lon: Double, codeOrName: String, limit: Int = 5): List<EmbassyDistance> {
    val all = EmbassyRepo.filterByCountry(codeOrName)
    return all.asSequence()
        .map { EmbassyDistance(it, distanceKm(lat, lon, it.lat, it.lon)) }
        .sortedBy { it.distanceKm }
        .take(limit)
        .toList()
}
