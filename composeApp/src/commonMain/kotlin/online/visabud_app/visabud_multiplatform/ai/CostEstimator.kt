package online.visabud.app.visabud_multiplatform.ai

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import online.visabud.app.visabud_multiplatform.data.UserProfile

/**
 * Cost / Expense Estimator (Cost Calculator Tool)
 *
 * Purpose:
 * - Estimates approximate costs for visa application (fees, biometrics), supporting documents
 *   (translations, photocopies), travel (flights), accommodation setup, living expenses, and other ground costs.
 *
 * Design notes:
 * - Fully local and heuristic; no network calls.
 * - Uses VisaFactsRag structured facts (fees, official site) when available to ground visa fees + citations.
 * - Personalizes ranges via UserProfile when possible (residence, budgetLevel preferences).
 * - Multiplatform-safe: avoid platform time APIs. Currency kept in a single unit (USD) for MVP.
 */
object CostEstimator {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    // ------------------ Public API ------------------

    @Serializable
    data class LineItem(
        val label: String,
        val amount: Double,
        val currency: String = "USD",
        val notes: String? = null,
        val confidence: Int = 70
    )

    @Serializable
    data class CostEstimate(
        val destination: String?,
        val goal: String?,
        val durationMonths: Int?,
        val travelers: Int,
        val currency: String = "USD",
        val items: List<LineItem>,
        val total: Double,
        val assumptions: List<String> = emptyList(),
        val citations: List<String> = emptyList()
    )

    @Serializable
    data class Options(
        val travelers: Int = 1,
        val currency: String = "USD",
        val includeLiving: Boolean = true,
        val includeTravel: Boolean = true,
        // New (fees DB integration)
        val includeDependents: Boolean = true,
        val includeAncillary: Boolean = true,
        val expressProcessing: Boolean = false,
        val spouseCount: Int = 0,
        val childCount: Int = 0
    )

    /**
     * Build a short prompt asking for missing parameters required to produce a better estimate.
     */
    fun buildMissingInfoPrompt(profile: UserProfile?, destination: String?, goal: String?, durationMonths: Int?): String? {
        val missing = mutableListOf<String>()
        if (destination.isNullOrBlank()) missing += "destination country"
        if (goal.isNullOrBlank()) missing += "visa goal (tourist, study, work, immigration)"
        // Duration helps for living/accommodation for non-tourist
        val g = goal?.lowercase()
        val needsDuration = g == null || g.contains("study") || g.contains("work") || g.contains("immig")
        if (needsDuration && (durationMonths == null || durationMonths <= 0)) missing += "expected duration in months"
        if (profile?.countryOfResidence.isNullOrBlank()) missing += "your current country of residence (for flight estimate)"
        return if (missing.isEmpty()) null else "To estimate costs accurately, please share: ${missing.joinToString(", ")}."
    }

    /**
     * Main entry: compute a heuristic cost estimate.
     * - embedder not required (kept for future RAG use); facts are looked up locally.
     */
    suspend fun estimate(
        profile: UserProfile?,
        destination: String?,
        goal: String?,
        durationMonths: Int? = null,
        options: Options = Options()
    ): CostEstimate {
        val destEntry = VisaFactsRag.findCountryByNameOrCode(destination)
        val destCode = destEntry?.code
        val destName = destEntry?.country ?: destination
        val site = destEntry?.officialSite

        // Visa fee grounded by facts when present
        val visaFee = estimateVisaFee(goal, destEntry)
        val biometrics = estimateBiometrics(goal, destCode)
        val serviceFees = estimateServiceFees(goal, destCode)
        val translations = estimateTranslations(goal)
        val medical = estimateMedical(goal)

        val items = mutableListOf<LineItem>()
        items += LineItem("Visa application fee", visaFee.first, notes = visaFee.second, confidence = 80)
        if (biometrics > 0) items += LineItem("Biometrics/centre fee", biometrics, confidence = 70)
        if (serviceFees > 0) items += LineItem("Service/courier/VFS fees", serviceFees, confidence = 60)
        if (translations > 0) items += LineItem("Translations/photocopies", translations, confidence = 50)
        if (medical > 0) items += LineItem("Medical/health checks (if applicable)", medical, confidence = 50)

        val travelers = if (options.travelers <= 0) 1 else options.travelers

        if (options.includeTravel) {
            val flights = estimateFlights(profile?.countryOfResidence, destCode)
            if (flights > 0) items += LineItem("Flights", flights * travelers, notes = passengersNote(travelers), confidence = 40)
            val localTransport = estimateLocalTransport(durationMonths)
            if (localTransport > 0) items += LineItem("Local transport/setup", localTransport, confidence = 40)
        }

        val g = goal?.lowercase()
        val needStay = g != null && (g.contains("study") || g.contains("work") || g.contains("immig"))

        // Accommodation (setup) and Living expenses
        if (needStay) {
            val months = (durationMonths ?: 6).coerceAtLeast(1)
            val accSetup = estimateAccommodationSetup(destCode)
            if (accSetup > 0) items += LineItem("Accommodation deposit/setup", accSetup, confidence = 50)
            if (options.includeLiving) {
                val livingPm = estimateLivingPerMonth(destCode, profile)
                val living = livingPm * months
                items += LineItem("Living expenses (${months} mo)", living, notes = livingBudgetNote(profile), confidence = 50)
            }
        } else if (g?.contains("tourist") == true) {
            // Simple tourist accommodation approximation
            val nights = ((durationMonths ?: 0) * 30).coerceAtLeast(5)
            val nightly = estimateHotelPerNight(destCode)
            if (nightly > 0) items += LineItem("Accommodation (~${nights} nights)", nightly * nights, confidence = 50)
        }

        val total = items.sumOf { it.amount }
        val assumptions = buildAssumptions(goal, durationMonths, travelers)
        val citations = buildCitations(site)

        return CostEstimate(
            destination = destName,
            goal = goal,
            durationMonths = durationMonths,
            travelers = travelers,
            currency = options.currency,
            items = items,
            total = round2(total),
            assumptions = assumptions,
            citations = citations
        )
    }

    fun buildHumanReadable(estimate: CostEstimate): String {
        val sb = StringBuilder()
        sb.appendLine("Estimated Costs (${estimate.currency})")
        estimate.destination?.let { sb.appendLine("Destination: $it") }
        estimate.goal?.let { sb.appendLine("Goal: $it") }
        estimate.durationMonths?.let { sb.appendLine("Duration: ${it} months") }
        if (estimate.travelers > 1) sb.appendLine("Travelers: ${estimate.travelers}")
        sb.appendLine()
        estimate.items.forEach { li ->
            sb.appendLine("- ${li.label}: ${formatAmount(li.amount, estimate.currency)}${li.notes?.let { " — $it" } ?: ""}")
        }
        sb.appendLine()
        sb.appendLine("Approximate total: ${formatAmount(estimate.total, estimate.currency)}")
        if (estimate.assumptions.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("Assumptions:")
            estimate.assumptions.forEach { sb.appendLine("- $it") }
        }
        if (estimate.citations.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("Sources:")
            estimate.citations.forEach { sb.appendLine("- $it") }
        }
        sb.appendLine()
        sb.appendLine("Note: These are ballpark figures. Always verify visa fees and requirements on official sites.")
        return sb.toString().trim()
    }

    fun toJson(estimate: CostEstimate): String = json.encodeToString(estimate)

    // ------------------ New: Fees DB powered estimate ------------------
    /**
     * Attempt to compute costs using the structured Visa Fees DB when a concrete visaTypeId is known.
     * Falls back to heuristic if data is missing.
     */
    suspend fun estimateUsingFeesDb(
        profile: UserProfile?,
        destinationCodeOrName: String?,
        visaTypeId: String?,
        options: Options = Options()
    ): CostEstimate? {
        if (visaTypeId.isNullOrBlank()) return null
        val entry = VisaFactsRag.findCountryByNameOrCode(destinationCodeOrName)
        val destCode = entry?.code ?: destinationCodeOrName?.trim()?.uppercase()
        val destName = entry?.country ?: destinationCodeOrName
        if (destCode.isNullOrBlank()) return null
        val fee = try { VisaFeesDb.loadVisaFee(destCode, visaTypeId) } catch (_: Throwable) { null }
        if (fee == null) return null

        val items = mutableListOf<LineItem>()
        // Base fee in USD
        val baseUsd = VisaFeesDb.convertToUsd(fee.baseFee.amount, fee.baseFee.currency) ?: fee.baseFee.amount
        items += LineItem(label = fee.visaTypeName ?: "Application fee", amount = baseUsd, currency = "USD", notes = "(${fee.baseFee.amount} ${fee.baseFee.currency})", confidence = 90)

        // Dependents
        if (options.includeDependents && fee.dependentFees.isNotEmpty()) {
            val spouse = options.spouseCount.coerceAtLeast(0)
            val child = options.childCount.coerceAtLeast(0)
            fee.dependentFees["spouse"]?.let { m ->
                val usd = VisaFeesDb.convertToUsd(m.amount, m.currency) ?: m.amount
                val total = usd * spouse
                if (spouse > 0) items += LineItem(label = "Spouse/Partner (x$spouse)", amount = total, notes = "${m.amount} ${m.currency} each", confidence = 85)
            }
            fee.dependentFees["child"]?.let { m ->
                val usd = VisaFeesDb.convertToUsd(m.amount, m.currency) ?: m.amount
                val total = usd * child
                if (child > 0) items += LineItem(label = "Child (x$child)", amount = total, notes = "${m.amount} ${m.currency} each", confidence = 85)
            }
        }

        // Ancillary mandatory
        if (options.includeAncillary) {
            for (a in fee.ancillaryFees) {
                val usd = VisaFeesDb.convertToUsd(a.amount, a.currency) ?: a.amount
                items += LineItem(label = a.serviceName, amount = usd, notes = if (a.mandatory) "mandatory" else null, confidence = 80)
            }
        }
        // Optional: add English test if present and likely required
        fee.optionalAncillaryFees.firstOrNull { it.serviceId.contains("english", true) || it.serviceName.contains("English", true) }?.let { a ->
            val usd = VisaFeesDb.convertToUsd(a.amount, a.currency) ?: a.amount
            items += LineItem(label = a.serviceName, amount = usd, notes = "if required", confidence = 60)
        }

        // Travel/living remain heuristic (optional)
        if (options.includeTravel) {
            val flights = estimateFlights(profile?.countryOfResidence, destCode)
            if (flights > 0) items += LineItem("Flights", flights * (options.travelers.coerceAtLeast(1)), notes = passengersNote(options.travelers), confidence = 40)
        }

        val totalUsd = round2(items.sumOf { it.amount })
        val assumptions = buildAssumptions(null, null, options.travelers)
        val citations = buildList {
            entry?.officialSite?.let { add(it) }
        }
        return CostEstimate(
            destination = destName,
            goal = null,
            durationMonths = null,
            travelers = options.travelers.coerceAtLeast(1),
            currency = "USD",
            items = items,
            total = totalUsd,
            assumptions = assumptions + listOf("Grounded by local visa fees database where available"),
            citations = citations
        )
    }

    // ------------------ Heuristics ------------------

    private fun estimateVisaFee(goal: String?, entry: VisaFactsEntry?): Pair<Double, String?> {
        // Try parse numeric from entry.fees
        val fromFacts = entry?.fees?.let { parseAnyUsd(it) }
        if (fromFacts != null) {
            return round2(fromFacts) to ("Based on local facts: \"${entry.fees}\"")
        }
        val g = (goal ?: "").lowercase()
        val fallback = when {
            g.contains("tour") -> 100.0
            g.contains("study") -> 350.0
            g.contains("work") -> 190.0
            g.contains("immig") -> 535.0
            else -> 150.0
        }
        return fallback to null
    }

    private fun estimateBiometrics(goal: String?, destCode: String?): Double {
        val g = (goal ?: "").lowercase()
        val base = when {
            g.contains("immig") -> 85.0
            g.contains("work") || g.contains("study") -> 75.0
            else -> 60.0
        }
        val bump = if (destCode in setOf("US", "CA", "UK")) 10.0 else 0.0
        return base + bump
    }

    private fun estimateServiceFees(goal: String?, destCode: String?): Double {
        val g = (goal ?: "").lowercase()
        val base = when {
            g.contains("immig") -> 90.0
            g.contains("work") || g.contains("study") -> 70.0
            else -> 40.0
        }
        val vfs = if (destCode in setOf("CA", "UK", "AU", "IN")) 15.0 else 0.0
        return base + vfs
    }

    private fun estimateTranslations(goal: String?): Double {
        val g = (goal ?: "").lowercase()
        return when {
            g.contains("immig") || g.contains("study") || g.contains("work") -> 150.0
            else -> 30.0
        }
    }

    private fun estimateMedical(goal: String?): Double {
        val g = (goal ?: "").lowercase()
        return when {
            g.contains("immig") || g.contains("study") -> 180.0
            else -> 0.0
        }
    }

    private fun estimateFlights(residence: String?, destCode: String?): Double {
        if (destCode.isNullOrBlank()) return 600.0
        val fromRegion = regionOf(residence)
        val toRegion = regionOf(destCode)
        val distanceBucket = when {
            fromRegion == null || toRegion == null -> 2
            fromRegion == toRegion -> 1
            else -> 3
        }
        return when (distanceBucket) {
            1 -> 250.0 // same region
            2 -> 600.0 // unknown mix
            else -> 900.0 // intercontinental
        }
    }

    private fun estimateLocalTransport(durationMonths: Int?): Double {
        val months = (durationMonths ?: 1).coerceAtLeast(1)
        return months * 50.0
    }

    private fun estimateAccommodationSetup(destCode: String?): Double {
        val bucket = costBucket(destCode)
        return when (bucket) {
            1 -> 800.0
            2 -> 1200.0
            else -> 2000.0
        }
    }

    private fun estimateLivingPerMonth(destCode: String?, profile: UserProfile?): Double {
        val bucket = costBucket(destCode)
        val base = when (bucket) {
            1 -> 600.0
            2 -> 1000.0
            else -> 1700.0
        }
        val pref = profile?.preferences?.budgetLevel?.lowercase()
        val factor = when (pref) {
            "low" -> 0.8
            "high" -> 1.2
            else -> 1.0
        }
        return base * factor
    }

    private fun estimateHotelPerNight(destCode: String?): Double {
        val bucket = costBucket(destCode)
        return when (bucket) {
            1 -> 40.0
            2 -> 80.0
            else -> 120.0
        }
    }

    private fun passengersNote(travelers: Int): String? = if (travelers > 1) "$travelers passengers" else null

    private fun livingBudgetNote(profile: UserProfile?): String? {
        val b = profile?.preferences?.budgetLevel ?: return null
        return "Adjusted for budget level: $b"
    }

    private fun buildAssumptions(goal: String?, durationMonths: Int?, travelers: Int): List<String> = buildList {
        add("USD currency; exchange rates not applied in MVP.")
        goal?.let { add("Goal assumed: $it") }
        durationMonths?.takeIf { it > 0 }?.let { add("Duration: ${it} months") }
        if (travelers > 1) add("Travelers: $travelers")
        add("Flight costs vary by season and city; shown as typical economy fares.")
    }

    private fun buildCitations(officialSite: String?): List<String> {
        if (officialSite.isNullOrBlank()) return emptyList()
        return listOf("Official site: $officialSite")
    }

    // ------------------ Helpers ------------------

    private fun parseAnyUsd(s: String): Double? {
        // Extract first number-like token as USD amount. E.g., "$185", "around 185"
        val m = Regex("([0-9]{2,5})(?:\\.[0-9]{1,2})?").find(s)
        return m?.value?.toDoubleOrNull()
    }

    private fun round2(x: Double): Double = kotlin.math.round(x * 100.0) / 100.0

    private fun formatAmount(amount: Double, currency: String): String {
        val a = round2(amount)
        return when (currency.uppercase()) {
            "USD" -> "$${a}"
            "EUR" -> "€${a}"
            "GBP" -> "£${a}"
            else -> "$a $currency"
        }
    }

    private fun regionOf(codeOrName: String?): String? {
        if (codeOrName.isNullOrBlank()) return null
        val key = codeOrName.trim().uppercase()
        // Simple ISO2 guesses for MVP; extend as needed
        return when {
            key in setOf("US", "CA", "MX") -> "NA"
            key in setOf("UK", "GB", "FR", "DE", "ES", "IT", "NL", "IE") -> "EU"
            key in setOf("AE", "SA", "QA", "KW") -> "ME"
            key in setOf("IN", "PK", "BD", "LK") -> "SA"
            key in setOf("JP", "CN", "KR") -> "EA"
            key in setOf("AU", "NZ") -> "OC"
            key in setOf("ZA", "NG", "KE", "EG") -> "AF"
            else -> null
        }
    }

    private fun costBucket(destCode: String?): Int {
        // 1=low, 2=medium, 3=high cost
        val d = destCode?.uppercase()
        return when {
            d in setOf("IN", "PK", "BD", "LK", "NG", "KE") -> 1
            d in setOf("FR", "DE", "ES", "IT", "NL", "IE", "AE", "JP") -> 3
            d in setOf("US", "CA", "UK", "AU") -> 3
            else -> 2
        }
    }
}
