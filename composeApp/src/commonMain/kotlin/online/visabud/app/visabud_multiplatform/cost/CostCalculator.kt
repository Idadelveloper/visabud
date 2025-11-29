package online.visabud.app.visabud_multiplatform.cost

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jetbrains.compose.resources.ExperimentalResourceApi
import visabud_multiplatform.composeapp.generated.resources.Res
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.round
import kotlin.math.sin

@Serializable
data class Capital(
    val name: String,
    val lat: Double,
    val lon: Double
)

@Serializable
data class VisaFeesCountry(
    val code: String,
    val country: String,
    val capital: Capital,
    val visaTypes: Map<String, Double>,
    val biometricsFee: Double = 0.0,
    val embassyFee: Double = 0.0,
    val translationPerPageRange: List<Double> = listOf(0.0, 0.0),
    val courierFeeRange: List<Double> = listOf(0.0, 0.0),
    val expeditedMultiplier: Double = 1.2,
    val legalAssistRange: List<Double> = listOf(0.0, 0.0),
    val currency: String = "USD"
)

object VisaFeesRepo {
    private const val RESOURCE_PATH = "files/visa_fees.json"
    private var cache: List<VisaFeesCountry>? = null
    private val json = Json { ignoreUnknownKeys = true }

    @OptIn(ExperimentalResourceApi::class)
    suspend fun getAll(): List<VisaFeesCountry> {
        cache?.let { return it }
        val bytes = Res.readBytes(RESOURCE_PATH)
        val list: List<VisaFeesCountry> = json.decodeFromString(bytes.decodeToString())
        cache = list
        return list
    }

    suspend fun getByCodeOrName(q: String): VisaFeesCountry? {
        val all = getAll()
        val needle = q.trim().lowercase()
        return all.firstOrNull { it.code.lowercase() == needle || it.country.lowercase() == needle }
    }
}

/** Haversine distance in km */
fun distanceKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val R = 6371.0
    fun rad(d: Double) = d * PI / 180.0
    val dLat = rad(lat2 - lat1)
    val dLon = rad(lon2 - lon1)
    val a = sin(dLat / 2) * sin(dLat / 2) + cos(rad(lat1)) * cos(rad(lat2)) * sin(dLon / 2) * sin(dLon / 2)
    val c = 2 * atan2(kotlin.math.sqrt(a), kotlin.math.sqrt(1 - a))
    return R * c
}

enum class TravelClass { ECONOMY, PREMIUM, BUSINESS }

data class CostInputs(
    val countryCodeOrName: String,
    val visaType: String,
    val pagesToTranslate: Int = 0,
    val includeCourier: Boolean = false,
    val includeLegalAssist: Boolean = false,
    val legalAssistFactor: Double = 0.5, // 0..1 across the legal range
    val nights: Int = 0,
    val nightlyRate: Double = 0.0,
    val travelClass: TravelClass = TravelClass.ECONOMY,
    val processingExpedited: Boolean = false,
    val originLat: Double? = null,
    val originLon: Double? = null
)

data class CostBreakdown(
    val currency: String,
    val baseVisaFee: Double,
    val biometrics: Double,
    val embassy: Double,
    val translations: Double,
    val courier: Double,
    val flights: Double,
    val accommodation: Double,
    val legalAssist: Double,
    val contingency: Double,
    val total: Double,
    val distanceKm: Double?
)

object CostCalculatorEngine {
    /**
     * Estimate flight price by distance band and class.
     */
    private fun estimateFlight(distanceKm: Double, klass: TravelClass): Double {
        val base = when {
            distanceKm < 800 -> 120.0
            distanceKm < 3000 -> 350.0
            distanceKm < 7000 -> 700.0
            else -> 1000.0
        }
        val classCoef = when (klass) {
            TravelClass.ECONOMY -> 1.0
            TravelClass.PREMIUM -> 1.6
            TravelClass.BUSINESS -> 2.3
        }
        return base * classCoef
    }

    suspend fun compute(inputs: CostInputs): CostBreakdown {
        val country = VisaFeesRepo.getByCodeOrName(inputs.countryCodeOrName)
            ?: error("Country not found in fee table: ${inputs.countryCodeOrName}")
        val baseVisa = country.visaTypes[inputs.visaType]
            ?: error("Visa type not found for ${country.country}: ${inputs.visaType}")

        // Apply expedited multiplier only to base + embassy (not biometrics per assumption)
        val expeditedMul = if (inputs.processingExpedited) country.expeditedMultiplier else 1.0
        val embassy = country.embassyFee * expeditedMul
        val biometrics = country.biometricsFee
        val baseWithSpeed = baseVisa * expeditedMul

        val transPerPage = ((country.translationPerPageRange.getOrNull(0) ?: 0.0) + (country.translationPerPageRange.getOrNull(1)
            ?: 0.0)) / 2.0
        val translations = inputs.pagesToTranslate.coerceAtLeast(0) * transPerPage

        val courier = if (inputs.includeCourier) {
            ((country.courierFeeRange.getOrNull(0) ?: 0.0) + (country.courierFeeRange.getOrNull(1) ?: 0.0)) / 2.0
        } else 0.0

        val legalAssist = if (inputs.includeLegalAssist) {
            val lo = country.legalAssistRange.getOrNull(0) ?: 0.0
            val hi = country.legalAssistRange.getOrNull(1) ?: lo
            lo + (hi - lo) * inputs.legalAssistFactor.coerceIn(0.0, 1.0)
        } else 0.0

        // Travel cost
        val distance = if (inputs.originLat != null && inputs.originLon != null) {
            distanceKm(inputs.originLat, inputs.originLon, country.capital.lat, country.capital.lon)
        } else null
        val flights = distance?.let { estimateFlight(it, inputs.travelClass) } ?: 0.0
        val accommodation = inputs.nights.coerceAtLeast(0) * inputs.nightlyRate.coerceAtLeast(0.0)

        val subtotal = baseWithSpeed + embassy + biometrics + translations + courier + flights + accommodation + legalAssist
        val contingency = subtotal * 0.10
        val total = subtotal + contingency

        fun r(v: Double) = round(v * 100.0) / 100.0
        return CostBreakdown(
            currency = country.currency,
            baseVisaFee = r(baseWithSpeed),
            biometrics = r(biometrics),
            embassy = r(embassy),
            translations = r(translations),
            courier = r(courier),
            flights = r(flights),
            accommodation = r(accommodation),
            legalAssist = r(legalAssist),
            contingency = r(contingency),
            total = r(total),
            distanceKm = distance?.let { r(it) }
        )
    }
}
