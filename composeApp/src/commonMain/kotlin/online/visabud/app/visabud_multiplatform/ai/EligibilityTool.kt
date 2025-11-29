package online.visabud.app.visabud_multiplatform.ai

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import online.visabud.app.visabud_multiplatform.data.UserProfile

/**
 * Visa Eligibility Tool
 *
 * Purpose:
 * - Assess whether a user is likely eligible, partially eligible, ineligible, or unknown for a
 *   given visa goal in a destination, based on the local profile + lightweight facts.
 * - Provide reasons and actionable recommendations to improve eligibility.
 *
 * Design:
 * - Fully local heuristics. Uses:
 *   - UserProfile (nationality, passportExpiry, education, workYears, finances, travel history)
 *   - VisaFactsRag (official link, basic notes)
 * - Does not make final determinations; returns a conservative assessment with a clear disclaimer.
 */
object EligibilityTool {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = false }

    // ---------- Public DTOs ----------
    @Serializable
    data class Assessment(
        val destination: String?,
        val visaType: String?,
        val status: String, // ELIGIBLE | PARTIALLY_ELIGIBLE | INELIGIBLE | UNKNOWN
        val reasons: List<String>,
        val recommendations: List<String>,
        val missing: List<String> = emptyList(),
        val prompt: String? = null,
        val officialLink: String? = null,
        val citations: List<String> = emptyList()
    )

    fun toJson(a: Assessment): String = json.encodeToString(a)

    fun buildHumanReadable(a: Assessment): String = buildString {
        if (a.prompt != null) {
            appendLine(a.prompt)
        } else {
            val dest = a.destination ?: "the destination"
            val vt = a.visaType ?: "visa"
            appendLine("Eligibility for $dest — $vt")
            appendLine("Status: ${a.status.replace('_', ' ')}")
            if (a.reasons.isNotEmpty()) {
                appendLine()
                appendLine("Why:")
                a.reasons.forEach { appendLine("- $it") }
            }
            if (a.recommendations.isNotEmpty()) {
                appendLine()
                appendLine("How to improve:")
                a.recommendations.forEach { appendLine("- $it") }
            }
            a.officialLink?.takeIf { it.isNotBlank() }?.let {
                appendLine()
                appendLine("Official site: $it")
            }
            if (a.citations.isNotEmpty()) {
                appendLine()
                appendLine("Sources:")
                a.citations.distinct().forEach { appendLine("- $it") }
            }
            appendLine()
            appendLine("Note: Heuristic assessment only. Always verify on official sites.")
        }
    }.trim()

    // ---------- Agent helper ----------
    fun buildMissingInfoPrompt(profile: UserProfile?, destination: String?, visaTypeOrGoal: String?): String? {
        val missing = mutableListOf<String>()
        if (destination.isNullOrBlank()) missing += "destination country"
        if (visaTypeOrGoal.isNullOrBlank()) missing += "visa type/purpose (tourist, study, work, immigration)"
        val p = profile
        if (p == null) {
            missing += "basic profile (nationality, education/work years)"
        } else {
            if (p.nationality.isNullOrBlank()) missing += "nationality"
            if (p.passportExpiry.isNullOrBlank()) missing += "passport expiry"
            if (p.education.isNullOrBlank() && p.workYears == null) missing += "education or years of work"
        }
        return if (missing.isEmpty()) null else "To assess eligibility, please share your ${missing.joinToString(", ")}."
    }

    // ---------- Main entry ----------
    fun assess(profile: UserProfile?, destination: String?, visaTypeOrGoal: String?): Assessment {
        val normalizedType = normalizeVisaType(visaTypeOrGoal)
        val entry = VisaFactsRag.findCountryByNameOrCode(destination)
        val official = entry?.officialSite
        val destName = entry?.country ?: destination

        val ask = buildMissingInfoPrompt(profile, destination, visaTypeOrGoal)
        if (ask != null) {
            val missingFields = mutableListOf<String>()
            if (destination.isNullOrBlank()) missingFields += "destination"
            if (visaTypeOrGoal.isNullOrBlank()) missingFields += "visaType"
            return Assessment(
                destination = destName,
                visaType = normalizedType,
                status = "UNKNOWN",
                reasons = emptyList(),
                recommendations = emptyList(),
                missing = missingFields,
                prompt = ask,
                officialLink = official,
                citations = listOfNotNull(official?.let { "Official: $it" })
            )
        }

        val p = profile!!
        val reasons = mutableListOf<String>()
        val recs = mutableListOf<String>()
        var negativeHits = 0
        var partialHits = 0

        // Passport validity 6+ months
        val passOk = p.passportExpiry?.let { isValidSixMonthsBeyond(it) } ?: false
        if (!passOk) {
            negativeHits++
            reasons += "Passport may not be valid for 6+ months beyond travel."
            recs += "Renew your passport to have 6+ months validity beyond your planned travel date."
        } else {
            reasons += "Passport validity appears OK (6+ months)."
        }

        // Visa-free hint for tourist
        if (normalizedType == "tourist" && !entry?.visaFreePolicy.isNullOrBlank()) {
            reasons += "Destination notes visa-free/waiver policy: ${entry!!.visaFreePolicy}. Eligibility depends on nationality."
            partialHits++ // because we don't know exact nationality mapping
        }

        // Finances for non-tourist
        val finances = p.finances?.lowercase()
        if (normalizedType in setOf("study","work","immigration")) {
            when (finances) {
                "low" -> { partialHits++; reasons += "Financial capacity marked low."; recs += "Increase available funds or add a sponsor to strengthen financial proof." }
                "medium", "high" -> reasons += "Financial capacity provided: $finances."
                else -> { partialHits++; reasons += "Financial capacity unknown."; recs += "Prepare recent bank statements and funding evidence." }
            }
        }

        // Education/work alignment
        val workYears = p.workYears ?: 0
        val hasEducation = !p.education.isNullOrBlank()
        when (normalizedType) {
            "study" -> {
                if (!hasEducation) { partialHits++; reasons += "Education history not provided."; recs += "Provide transcripts/degree and an admission offer." }
                else reasons += "Education info present."
            }
            "work" -> {
                if (workYears < 1) { partialHits++; reasons += "Limited documented work experience."; recs += "Gain relevant experience or consider study/internship routes." }
                else reasons += "${workYears} years of work experience recorded."
            }
            "immigration" -> {
                if (!hasEducation && workYears < 3) { partialHits++; reasons += "Limited human capital signals (education/work)."; recs += "Consider enhancing language tests, education credential assessment, or skilled work first." }
            }
        }

        // Travel history can help
        if (p.travelHistory.isNotEmpty()) {
            reasons += "Prior travel history present (${p.travelHistory.size} event(s)), which can support ties/compliance."
        }

        // Country specific generic restrictions/notes
        entry?.restrictions?.takeIf { it.isNotEmpty() }?.let {
            reasons += "Restrictions noted for ${entry.country}: ${it.take(2).joinToString("; ")}"
        }

        // Determine status
        val status = when {
            negativeHits >= 1 && normalizedType != "tourist" -> "INELIGIBLE"
            negativeHits >= 1 && normalizedType == "tourist" -> "PARTIALLY_ELIGIBLE" // can fix passport
            partialHits >= 2 -> "PARTIALLY_ELIGIBLE"
            else -> "ELIGIBLE"
        }

        // Generic recommendations by type
        recs += genericRecs(normalizedType)

        // Build citations
        val citations = buildList {
            official?.let { add("Official: $it") }
        }

        return Assessment(
            destination = destName,
            visaType = normalizedType,
            status = status,
            reasons = reasons.distinct(),
            recommendations = recs.distinct(),
            missing = emptyList(),
            prompt = null,
            officialLink = official,
            citations = citations
        )
    }

    // ---------- Internals ----------
    private fun normalizeVisaType(s: String?): String {
        val t = (s ?: "").trim().lowercase()
        return when {
            t.contains("tour") || t.contains("visit") -> "tourist"
            t.contains("study") || t.contains("student") -> "study"
            t.contains("work") || t.contains("job") -> "work"
            t.contains("immig") || t.contains("residen") || t == "pr" -> "immigration"
            else -> t.ifBlank { "generic" }
        }
    }

    private fun isValidSixMonthsBeyond(expiryIso: String): Boolean {
        val today = "2025-11-29" // session date constant
        val plusSix = addMonthsIso(today, 6)
        return expiryIso >= plusSix
    }

    private fun addMonthsIso(iso: String, months: Int): String {
        val y = iso.substring(0, 4).toIntOrNull() ?: return iso
        val m = iso.substring(5, 7).toIntOrNull() ?: return iso
        val d = iso.substring(8, 10)
        val total = m + months
        val newY = y + (total - 1) / 12
        val newM = ((total - 1) % 12) + 1
        val mm = newM.toString().padStart(2, '0')
        val yStr = newY.toString().padStart(4, '0')
        return "$yStr-$mm-$d"
    }

    private fun genericRecs(type: String): List<String> = when (type) {
        "tourist" -> listOf(
            "Prepare funds, accommodation booking, travel insurance, and return ticket.",
            "Demonstrate ties to your home country (employment, property, family)."
        )
        "study" -> listOf(
            "Secure admission (offer/CAS/I-20) and arrange proof of funds/block account if applicable.",
            "Take required language tests and gather transcripts/certificates."
        )
        "work" -> listOf(
            "Obtain an employer job offer and required sponsorship/authorization.",
            "Update CV, gather experience letters, and check licensing requirements."
        )
        "immigration" -> listOf(
            "Consider language tests and education credential assessment to improve points.",
            "Accrue relevant skilled work experience and maintain clean background checks."
        )
        else -> listOf("Collect standard documents (passport, funds, itinerary) and verify category on official site.")
    }
}
