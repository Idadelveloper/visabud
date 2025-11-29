package online.visabud.app.visabud_multiplatform.ai

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import online.visabud.app.visabud_multiplatform.data.UserProfile

/**
 * Visa Checklist Tool
 *
 * Purpose:
 * - Generate a personalized visa document checklist based on destination country, visa type/goal,
 *   and the user's profile signals (nationality, work/education, finances, travel history).
 * - When critical information is missing, produce a short prompt for the agent to ask the user,
 *   then call this tool again once filled.
 *
 * Privacy: Fully local. Uses VisaFactsRag (local JSON) and UserProfile (in-memory).
 */
object VisaChecklistTool {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = false }

    // ---------------- Public DTOs ----------------
    @Serializable
    data class Checklist(
        val visaType: String,
        val country: String,
        val requiredDocuments: List<String>,
        val optionalDocuments: List<String>,
        val warnings: List<String>,
        val officialLink: String
    )

    /** Convenience output helpers */
    fun toJson(checklist: Checklist): String = json.encodeToString(checklist)

    fun buildHumanReadable(checklist: Checklist): String = buildString {
        appendLine("${checklist.country} — ${checklist.visaType} checklist")
        appendLine("Official: ${checklist.officialLink}")
        appendLine()
        appendLine("Required:")
        checklist.requiredDocuments.forEach { appendLine("- $it") }
        if (checklist.optionalDocuments.isNotEmpty()) {
            appendLine()
            appendLine("Optional/If applicable:")
            checklist.optionalDocuments.forEach { appendLine("- $it") }
        }
        if (checklist.warnings.isNotEmpty()) {
            appendLine()
            appendLine("Warnings:")
            checklist.warnings.forEach { appendLine("- $it") }
        }
        appendLine()
        appendLine("Reminder: Always verify on the official site. Requirements can change.")
    }.trim()

    // ---------------- Agent helpers ----------------
    fun buildMissingInfoPrompt(profile: UserProfile?, destination: String?, visaTypeOrGoal: String?): String? {
        val missing = mutableListOf<String>()
        if (destination.isNullOrBlank()) missing += "destination country"
        if (visaTypeOrGoal.isNullOrBlank()) missing += "visa type or purpose (tourist, study, work, immigration)"
        // These improve personalization
        val p = profile
        if (p == null) {
            missing += "basic profile (nationality, finances optional)"
        } else {
            if (p.nationality.isNullOrBlank()) missing += "nationality"
        }
        return if (missing.isEmpty()) null else "To tailor your checklist, please share your ${missing.joinToString(", ")}."
    }

    // ---------------- Main entry ----------------
    fun generate(
        profile: UserProfile?,
        destination: String?,
        visaTypeOrGoal: String?
    ): Checklist {
        // Resolve country facts (best-effort)
        val entry = VisaFactsRag.findCountryByNameOrCode(destination)
        val countryName = entry?.country ?: (destination ?: "Unknown Country")
        val official = entry?.officialSite ?: ""

        val visaType = normalizeVisaType(visaTypeOrGoal)

        // Start with a base identity set
        val identity = mutableListOf(
            "Valid passport",
            "Passport-size photo per spec",
            "Completed application form",
            "Visa fee payment receipt"
        )
        // Include from local facts typical checklist if available
        entry?.checklist?.forEach { if (!identity.contains(it)) identity += it }

        // Category buckets (may overlap with entry.checklist; we'll dedupe later)
        val financial = mutableListOf(
            "Bank statements (last 3–6 months)",
            "Proof of funds / sponsorship"
        )
        val accommodation = mutableListOf("Accommodation booking or address of stay")
        val invitation = mutableListOf<String>()
        val insurance = mutableListOf("Travel/health insurance covering stay")
        val itinerary = mutableListOf("Flight booking or travel itinerary")
        val medical = mutableListOf<String>()
        val academic = mutableListOf<String>()
        val workDocs = mutableListOf<String>()

        // Personalization by visa type
        when (visaType) {
            "tourist" -> {
                // keep defaults; invitation optional only if visiting family/friends
                invitation += "Invitation letter (if visiting family/friends)"
            }
            "study" -> {
                academic += listOf("Offer/Admission letter", "Transcripts/degree certificates", "Language test (if required)")
                medical += "Medical/health checks (if required)"
                insurance += "Student health insurance (if required)"
            }
            "work" -> {
                workDocs += listOf("Job offer/Employment contract", "Employer sponsorship/LMIA/CoS (as applicable)", "CV/Resume")
                // licensing may apply
                workDocs += "Credentials evaluation / licensing (if applicable)"
                medical += "Medical/health checks (if required)"
            }
            "immigration" -> {
                workDocs += listOf("CV/Resume", "Employment letters/pay slips")
                academic += "Education assessment (if applicable)"
                medical += "Medical examination (panel physician, if required)"
                identity += "Police clearance certificates"
                invitation += "Civil status documents (marriage/birth certificates, if applicable)"
            }
            else -> {
                // generic catch-all
            }
        }

        // Profile-based tweaks
        val warnings = mutableListOf<String>()
        profile?.let { p ->
            // Passport validity warning using DocumentReview heuristics approach (simple date compare with session date)
            p.passportExpiry?.let { exp ->
                if (!isValidSixMonthsBeyond(exp)) warnings += "Passport may expire within ~6 months of travel. Renew if needed."
            }
            // Finances hint
            if (p.finances?.lowercase() == "low") {
                warnings += "Financial status marked low — consider adding sponsor letter or additional funds."
            }
            // Travel history implies optional previous visas evidence
            if (p.travelHistory.isNotEmpty()) {
                itinerary += "Previous visas/passport pages (optional)"
            }
        }

        // Build required vs optional lists
        val required = mutableListOf<String>()
        val optional = mutableListOf<String>()

        fun pushRequired(vararg items: String) { items.forEach { if (it.isNotBlank()) required += it } }
        fun pushOptional(vararg items: String) { items.forEach { if (it.isNotBlank()) optional += it } }

        // Identity is always required
        pushRequired(*identity.toTypedArray())
        // Tourist: finances, accommodation, itinerary, insurance usually required
        when (visaType) {
            "tourist" -> {
                pushRequired(*financial.toTypedArray())
                pushRequired(*accommodation.toTypedArray())
                pushRequired(*itinerary.toTypedArray())
                pushRequired(*insurance.toTypedArray())
                pushOptional(*invitation.toTypedArray())
            }
            "study" -> {
                pushRequired(*academic.toTypedArray())
                pushRequired(*financial.toTypedArray())
                pushRequired(*insurance.toTypedArray())
                pushRequired(*itinerary.toTypedArray())
                pushOptional(*accommodation.toTypedArray())
            }
            "work" -> {
                pushRequired(*workDocs.toTypedArray())
                pushRequired(*financial.toTypedArray())
                pushRequired(*insurance.toTypedArray())
                pushOptional(*accommodation.toTypedArray())
                pushOptional(*itinerary.toTypedArray())
            }
            "immigration" -> {
                pushRequired(*workDocs.toTypedArray())
                pushRequired(*academic.toTypedArray())
                pushRequired(*financial.toTypedArray())
                pushRequired(*medical.toTypedArray())
                pushOptional(*accommodation.toTypedArray())
                pushOptional(*itinerary.toTypedArray())
                pushOptional(*invitation.toTypedArray())
            }
            else -> {
                // Generic defaults
                pushRequired(*financial.toTypedArray())
                pushOptional(*accommodation.toTypedArray())
                pushOptional(*itinerary.toTypedArray())
                pushOptional(*insurance.toTypedArray())
                pushOptional(*invitation.toTypedArray())
                pushOptional(*medical.toTypedArray())
                pushOptional(*academic.toTypedArray())
                pushOptional(*workDocs.toTypedArray())
            }
        }

        val req = required.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        val opt = (optional.map { it.trim() } + suggestionExtras(visaType)).filter { it.isNotBlank() }.distinct().filterNot { it in req }

        return Checklist(
            visaType = visaTypeOrGoal ?: "",
            country = countryName,
            requiredDocuments = req,
            optionalDocuments = opt,
            warnings = warnings,
            officialLink = official
        )
    }

    // ---------------- Internal helpers ----------------
    private fun normalizeVisaType(s: String?): String {
        val t = (s ?: "").trim().lowercase()
        return when {
            t.contains("tour") || t.contains("visit") -> "tourist"
            t.contains("study") || t.contains("student") -> "study"
            t.contains("work") || t.contains("job") -> "work"
            t.contains("immig") || t.contains("residen") || t == "pr" -> "immigration"
            else -> "generic"
        }
    }

    private fun suggestionExtras(visaType: String): List<String> = when (visaType) {
        "tourist" -> listOf("Return/onward ticket", "Proof of ties to home country (employment letter, property)")
        "study" -> listOf("Proof of tuition payment (if applicable)", "Accommodation letter")
        "work" -> listOf("Reference letters", "Police clearance (sometimes required)")
        "immigration" -> listOf("Tax records", "Detailed travel history")
        else -> listOf("Police clearance (if requested)")
    }

    private fun isValidSixMonthsBeyond(expiryIso: String): Boolean {
        // Use session date constant as done elsewhere to keep common code simple
        val today = "2025-11-29"
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
}
