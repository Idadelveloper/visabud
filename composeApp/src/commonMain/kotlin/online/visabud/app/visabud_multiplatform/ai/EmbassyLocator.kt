package online.visabud.app.visabud_multiplatform.ai

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jetbrains.compose.resources.ExperimentalResourceApi
import visabud_multiplatform.composeapp.generated.resources.Res

import online.visabud.app.visabud_multiplatform.data.UserProfile

/**
 * Embassy / Consulate Locator Tool (fully local)
 *
 * Purpose:
 * - Provide addresses/contact info for embassies/consulates of a destination country
 * - Compute nearest mission to user's location (approximate, using built-in city coordinates)
 * - No network/geocoding; relies on bundled dataset and a small gazetteer
 */
object EmbassyLocator {
    // Resource path for bundled missions database
    private const val RESOURCE_PATH = "files/embassies.json"

    private val json = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true; prettyPrint = false }

    // ---- Data model (compat facade used by tools) ----
    @Serializable
    data class Mission(
        val type: String, // embassy | consulate_general | consulate | consular_agency | honorary_consulate
        val city: String,
        val address: String,
        val phone: String? = null,
        val email: String? = null,
        val website: String? = null,
        val lat: Double? = null,
        val lon: Double? = null
    )

    @Serializable
    data class EmbassyCountryEntry(
        val code: String, // ISO alpha-2 (target country code when available)
        val country: String,
        val missions: List<Mission> = emptyList()
    )

    // ---- New schema DTOs (rooted file with diplomaticMissions) ----
    @Serializable
    private data class EmbassiesDb(
        val version: String? = null,
        val lastUpdated: String? = null,
        val diplomaticMissions: List<DbMission>? = null
    )

    @Serializable
    private data class DbMission(
        val id: String? = null,
        val missionType: String? = null,
        val representingCountry: DbCountryRef? = null,
        val hostCountry: DbCountryRef? = null,
        val missionName: String? = null,
        val officialTitle: String? = null,
        val location: DbLocation? = null,
        val contact: DbContact? = null,
        val services: List<String>? = null
    )

    @Serializable
    private data class DbCountryRef(
        val countryName: String? = null,
        val countryCode: String? = null,
        val iso2: String? = null
    )

    @Serializable
    private data class DbLocation(
        val city: String? = null,
        val state: String? = null,
        val country: String? = null,
        val address: DbAddress? = null,
        val coordinates: DbCoordinates? = null
    )

    @Serializable
    private data class DbAddress(
        val city: String? = null,
        val country: String? = null,
        val postalCode: String? = null,
        val fullAddress: String? = null
    )

    @Serializable
    private data class DbCoordinates(
        val latitude: Double? = null,
        val longitude: Double? = null,
        val precision: String? = null
    )

    @Serializable
    private data class DbContact(
        val phone: String? = null,
        val email: String? = null,
        val website: String? = null
    )

    // ---- Mapper from new DB schema to compat entries ----
    private fun mapDbToCompat(db: EmbassiesDb): List<EmbassyCountryEntry> {
        val missions = db.diplomaticMissions ?: emptyList()
        if (missions.isEmpty()) return emptyList()
        // Group missions by representingCountry (iso2 preferred)
        val grouped = LinkedHashMap<String, MutableList<Pair<String, Mission>>>()
        for (m in missions) {
            val repName = m.representingCountry?.countryName ?: continue
            val repIso2 = (m.representingCountry?.iso2 ?: m.representingCountry?.countryCode)?.uppercase()
            val code = (repIso2?.takeIf { it.isNotBlank() } ?: repName.take(2).uppercase())
            val city = m.location?.address?.city ?: m.location?.city ?: ""
            val addr = m.location?.address?.fullAddress ?: listOfNotNull(city, m.location?.state, m.location?.country).filter { !it.isNullOrBlank() }.joinToString(", ")
            val lat = m.location?.coordinates?.latitude
            val lon = m.location?.coordinates?.longitude
            val type = (m.missionType ?: m.officialTitle ?: m.missionName ?: "mission").trim().lowercase()
            val phone = m.contact?.phone
            val email = m.contact?.email
            val website = m.contact?.website
            val compat = Mission(
                type = type,
                city = city.ifBlank { m.location?.country ?: repName },
                address = addr.ifBlank { city },
                phone = phone,
                email = email,
                website = website,
                lat = lat,
                lon = lon
            )
            val list = grouped.getOrPut(code) { mutableListOf() }
            list += (repName to compat)
        }
        val out = mutableListOf<EmbassyCountryEntry>()
        for ((code, pairs) in grouped) {
            val countryName = pairs.firstOrNull()?.first ?: code
            val missionsList = pairs.map { it.second }
            out += EmbassyCountryEntry(code = code, country = countryName, missions = missionsList)
        }
        return out
    }

    // ---- Query DTOs ----
    data class QueryResult(
        val destination: String?,
        val userLocation: String?,
        val countryEntry: EmbassyCountryEntry?,
        val nearest: MissionWithDistance?,
        val alternatives: List<MissionWithDistance>,
        val missing: List<String>,
        val prompt: String?
    )

    data class MissionWithDistance(val mission: Mission, val countryCode: String, val countryName: String, val distanceKm: Double)

    // ---- Loaded data ----
    private var entries: List<EmbassyCountryEntry> = emptyList()
    private var ready: Boolean = false

    // Minimal gazetteer: common cities likely to be referenced by users.
    // Note: This is not exhaustive and is intended for approximate nearest calculations.
    private val cityGazetteer: Map<String, Pair<Double, Double>> = mapOf(
        // UK
        "london" to (51.5074 to -0.1278),
        "manchester" to (53.4808 to -2.2426),
        // US
        "new york" to (40.7128 to -74.0060),
        "washington" to (38.9072 to -77.0369),
        "washington dc" to (38.9072 to -77.0369),
        "washington, d.c." to (38.9072 to -77.0369),
        // CA
        "toronto" to (43.6532 to -79.3832),
        // IN
        "mumbai" to (19.0760 to 72.8777),
        "delhi" to (28.6139 to 77.2090),
        // JP
        "tokyo" to (35.6762 to 139.6503),
        // DE
        "berlin" to (52.5200 to 13.4050),
        // FR
        "paris" to (48.8566 to 2.3522),
        // AE
        "dubai" to (25.2048 to 55.2708),
        // ZA
        "johannesburg" to (-26.2041 to 28.0473)
    )

    @OptIn(ExperimentalResourceApi::class)
    private suspend fun ensureLoaded() {
        if (ready && entries.isNotEmpty()) return
        val bytes = Res.readBytes(RESOURCE_PATH)
        val text = bytes.decodeToString()
        // Try new root schema first
        entries = try {
            val db: EmbassiesDb = json.decodeFromString(text)
            val mapped = mapDbToCompat(db)
            if (mapped.isNotEmpty()) mapped else throw IllegalStateException("empty mapped")
        } catch (_: Throwable) {
            // Fallback: legacy flat array of EmbassyCountryEntry
            try { json.decodeFromString(text) } catch (_: Throwable) { emptyList() }
        }
        ready = true
    }

    // ---- Agent helpers ----

    fun buildMissingInfoPrompt(destination: String?, userLocation: String?, profile: UserProfile?): String? {
        val missing = mutableListOf<String>()
        if (destination.isNullOrBlank()) missing += "destination country"
        if (userLocation.isNullOrBlank() && profile?.countryOfResidence.isNullOrBlank()) missing += "your current location (city)"
        if (missing.isEmpty()) return null
        return "To find the nearest embassy/consulate, please share ${missing.joinToString(", ")}."
    }

    suspend fun searchMissions(destination: String?): EmbassyCountryEntry? {
        ensureLoaded()
        val key = destination?.trim()?.lowercase() ?: return null
        return entries.firstOrNull { e ->
            e.code.lowercase() == key || e.country.lowercase() == key || e.country.lowercase().contains(key)
        }
    }

    suspend fun queryNearest(destination: String?, userLocation: String?, profile: UserProfile? = null): QueryResult {
        ensureLoaded()
        val missing = mutableListOf<String>()
        val country = if (destination.isNullOrBlank()) {
            missing += "destination"
            null
        } else {
            searchMissions(destination)
        }
        val locName = when {
            !userLocation.isNullOrBlank() -> userLocation
            !profile?.countryOfResidence.isNullOrBlank() -> profile?.countryOfResidence
            else -> null
        }
        if (locName.isNullOrBlank()) missing += "location"
        val userLatLon = resolveLocation(locName)
        val nearest = if (country != null && userLatLon != null) computeNearest(country, userLatLon.first, userLatLon.second) else null
        val alts = if (country != null && userLatLon != null) computeAlternatives(country, userLatLon.first, userLatLon.second, exclude = nearest?.mission) else emptyList()
        val prompt = if (missing.isEmpty()) null else buildMissingInfoPrompt(destination, userLocation, profile)
        return QueryResult(destination, locName, country, nearest, alts, missing, prompt)
    }

    fun buildHumanReadable(result: QueryResult): String {
        val sb = StringBuilder()
        if (result.countryEntry == null) {
            sb.appendLine("I couldn't find the destination country in the local missions database.")
            result.prompt?.let { sb.appendLine(it) }
            return sb.toString().trim()
        }
        val entry = result.countryEntry
        sb.appendLine("${entry.country} (${entry.code}) — embassies/consulates")
        val nearest = result.nearest
        if (nearest != null) {
            val m = nearest.mission
            sb.appendLine()
            sb.appendLine("Nearest to you: ${m.type} — ${m.city} (~${nearest.distanceKm.toInt()} km)")
            sb.appendLine(m.address)
            m.phone?.takeIf { it.isNotBlank() }?.let { sb.appendLine("Phone: $it") }
            m.email?.takeIf { it.isNotBlank() }?.let { sb.appendLine("Email: $it") }
            m.website?.takeIf { it.isNotBlank() }?.let { sb.appendLine("Website: $it") }
            sb.appendLine("Map: ${buildMapLink(m)}")
        } else {
            sb.appendLine()
            sb.appendLine("Provide your city (e.g., 'I'm in London') so I can suggest the closest mission.")
        }
        if (result.alternatives.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("Other nearby options:")
            result.alternatives.take(3).forEach { a ->
                val m = a.mission
                sb.appendLine("- ${m.type} — ${m.city} (~${a.distanceKm.toInt()} km) — ${m.website ?: m.address}")
            }
        }
        sb.appendLine()
        sb.appendLine("Note: Locations are approximate from a local dataset. Always verify addresses/booking instructions on the official site.")
        return sb.toString().trim()
    }

    // ---- Internals ----

    private fun resolveLocation(name: String?): Pair<Double, Double>? {
        if (name.isNullOrBlank()) return null
        val norm = name.trim().lowercase()
        // If input looks like "lat,lon" try to parse directly
        val latLon = parseLatLon(norm)
        if (latLon != null) return latLon
        // Try known cities
        cityGazetteer[norm]?.let { return it }
        // Try stripping country part "city, country"
        val cityOnly = norm.substringBefore(",").trim()
        cityGazetteer[cityOnly]?.let { return it }
        return null
    }

    private fun parseLatLon(s: String): Pair<Double, Double>? {
        val parts = s.split(",")
        if (parts.size == 2) {
            val lat = parts[0].trim().toDoubleOrNull()
            val lon = parts[1].trim().toDoubleOrNull()
            if (lat != null && lon != null) return lat to lon
        }
        return null
    }

    private fun computeNearest(entry: EmbassyCountryEntry, lat: Double, lon: Double): MissionWithDistance? {
        val candidates = entry.missions.mapNotNull { m ->
            val mlat = m.lat; val mlon = m.lon
            if (mlat == null || mlon == null) null else MissionWithDistance(m, entry.code, entry.country, haversineKm(lat, lon, mlat, mlon))
        }
        return candidates.minByOrNull { it.distanceKm }
    }

    private fun computeAlternatives(entry: EmbassyCountryEntry, lat: Double, lon: Double, exclude: Mission? = null): List<MissionWithDistance> {
        return entry.missions.mapNotNull { m ->
            if (exclude != null && m === exclude) return@mapNotNull null
            val mlat = m.lat; val mlon = m.lon
            if (mlat == null || mlon == null) null else MissionWithDistance(m, entry.code, entry.country, haversineKm(lat, lon, mlat, mlon))
        }.sortedBy { it.distanceKm }.take(5)
    }

    private fun haversineKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val R = 6371.0 // km
        fun toRad(d: Double) = d * kotlin.math.PI / 180.0
        val dLat = toRad(lat2 - lat1)
        val dLon = toRad(lon2 - lon1)
        val a = kotlin.math.sin(dLat / 2) * kotlin.math.sin(dLat / 2) +
                kotlin.math.cos(toRad(lat1)) * kotlin.math.cos(toRad(lat2)) *
                kotlin.math.sin(dLon / 2) * kotlin.math.sin(dLon / 2)
        val c = 2 * kotlin.math.atan2(kotlin.math.sqrt(a), kotlin.math.sqrt(1 - a))
        return (R * c)
    }

    private fun buildMapLink(m: Mission): String {
        val q = if (!m.address.isNullOrBlank()) m.address else listOfNotNull(m.type, m.city).joinToString(" ")
        val enc = urlEncode(q)
        return "https://maps.google.com/?q=$enc"
    }

    private fun urlEncode(s: String): String {
        // Minimal encoder avoiding platform specifics; sufficient for addresses
        val safe = StringBuilder()
        for (ch in s) {
            when (ch) {
                ' ' -> safe.append('+')
                in 'A'..'Z', in 'a'..'z', in '0'..'9', '-', '_', '.', '~', ',', '/', ':' -> safe.append(ch)
                else -> safe.append('%').append(ch.code.toString(16).uppercase().padStart(2, '0'))
            }
        }
        return safe.toString()
    }
}
