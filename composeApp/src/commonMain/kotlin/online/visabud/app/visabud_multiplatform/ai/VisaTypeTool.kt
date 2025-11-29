package online.visabud.app.visabud_multiplatform.ai

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import online.visabud.app.visabud_multiplatform.data.UserProfile

/**
 * Visa Type Tool
 *
 * Purpose:
 * - Given a destination, purpose (tourist/work/study/immigration or specific like conference),
 *   and light context (duration, paid work), recommend the most likely visa category to apply for.
 * - Uses local VisaFactsRag entries to ground with official site and known visa types.
 * - Fully local; heuristic mapping with clear disclaimers and citations.
 */
object VisaTypeTool {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = false }

    // ------------ Public DTOs ------------
    @Serializable
    data class RecommendationResult(
        val destination: String?,
        val purpose: String?,
        val recommendedVisa: String?,
        val alternatives: List<String> = emptyList(),
        val reasoning: String = "",
        val warnings: List<String> = emptyList(),
        val officialLink: String? = null,
        val missing: List<String> = emptyList(),
        val prompt: String? = null
    )

    fun toJson(r: RecommendationResult): String = json.encodeToString(r)

    fun buildHumanReadable(r: RecommendationResult): String = buildString {
        if (r.prompt != null) {
            appendLine(r.prompt)
        } else {
            val dest = r.destination ?: "the destination"
            appendLine("Visa suggestion for $dest")
            r.recommendedVisa?.let { appendLine("Recommended: $it") }
            if (r.alternatives.isNotEmpty()) appendLine("Alternatives: ${r.alternatives.joinToString()}")
            if (r.reasoning.isNotBlank()) {
                appendLine()
                appendLine(r.reasoning)
            }
            r.officialLink?.let {
                if (it.isNotBlank()) { appendLine(); appendLine("Official site: $it") }
            }
            if (r.warnings.isNotEmpty()) {
                appendLine()
                appendLine("Warnings:")
                r.warnings.forEach { appendLine("- $it") }
            }
            appendLine()
            appendLine("Note: Always confirm category and rules on the official site.")
        }
    }.trim()

    // ------------ Agent helper ------------
    fun buildMissingInfoPrompt(
        profile: UserProfile?,
        destination: String?,
        purpose: String?,
        durationDays: Int? = null,
        paidWork: Boolean? = null
    ): String? {
        val missing = mutableListOf<String>()
        if (destination.isNullOrBlank()) missing += "destination country"
        if (purpose.isNullOrBlank()) missing += "purpose (tourist, work, study, conference, immigration)"
        // Optional clarifiers improve accuracy
        if (paidWork == null) missing += "whether it involves paid work (yes/no)"
        if (purpose?.contains("study", true) == true && durationDays == null) missing += "approximate duration (days/weeks/months)"
        if (missing.isEmpty()) return null
        return "To suggest the right visa type, please share: ${missing.joinToString(", ")}."
    }

    // ------------ Main entry ------------
    fun recommend(
        profile: UserProfile?,
        destination: String?,
        purposeInput: String?,
        durationDays: Int? = null,
        paidWork: Boolean? = null,
        isAcademic: Boolean? = null
    ): RecommendationResult {
        val entry = VisaFactsRag.findCountryByNameOrCode(destination)
        val destName = entry?.country ?: destination
        val purpose = normalizePurpose(purposeInput)
        val ask = buildMissingInfoPrompt(profile, destination, purpose, durationDays, paidWork)
        if (ask != null) {
            return RecommendationResult(
                destination = destName,
                purpose = purpose,
                recommendedVisa = null,
                officialLink = entry?.officialSite,
                missing = listOf("destination").filter { destination.isNullOrBlank() } + listOf("purpose").filter { purpose.isNullOrBlank() },
                prompt = ask
            )
        }

        // Heuristic mapping by country
        val code = entry?.code?.uppercase()
        val (visa, alts, reason, warnings) = when (code) {
            "US" -> mapUs(purpose, durationDays, paidWork, isAcademic)
            "UK" -> mapUk(purpose, durationDays, paidWork, isAcademic)
            "CA" -> mapCa(purpose, durationDays, paidWork, isAcademic)
            "AU" -> mapAu(purpose, durationDays, paidWork, isAcademic)
            else -> mapGeneric(purpose, durationDays, paidWork, isAcademic)
        }

        // If nationality known and US + tourist/business: hint about VWP/ESTA
        val natWarns = mutableListOf<String>()
        if (code == "US" && (purpose == "business" || purpose == "tourist")) {
            natWarns += "Some nationalities may use the Visa Waiver Program (ESTA) for short visits; check eligibility."
        }

        val official = entry?.officialSite
        val availableTypes = entry?.visaTypes ?: emptyList()
        val altsEnriched = if (alts.isEmpty()) availableTypes.take(3) else alts

        return RecommendationResult(
            destination = destName,
            purpose = purpose,
            recommendedVisa = visa,
            alternatives = altsEnriched,
            reasoning = reason,
            warnings = (warnings + natWarns).distinct(),
            officialLink = official,
            missing = emptyList(),
            prompt = null
        )
    }

    // ------------ Country mappings ------------
    private fun mapUs(purpose: String?, durationDays: Int?, paidWork: Boolean?, isAcademic: Boolean?): Quad {
        val p = purpose ?: ""
        val short = (durationDays ?: 0) in 1..180
        return when {
            p == "conference" || (p == "business" && isAcademic == true) -> Quad("Business (B-1)", listOf("Tourist (B-2)"), "Conference/business meetings typically fall under B-1 visitor category (no employment).", warnNoWork())
            p == "business" -> Quad("Business (B-1)", listOf("Tourist (B-2)"), "Short business activities are under B-1. No local employment allowed.", warnNoWork())
            p == "tourist" -> Quad("Tourist (B-2)", listOf("Business (B-1)"), "Leisure tourism/visiting friends is B-2.", emptyList())
            p == "study" && short -> Quad("Visitor (B-1/B-2) short course", listOf("Student (F-1/M-1)"), "Very short, non-credit courses may be under visitor; full-time academic study requires F-1/M-1.", listOf("Full-time or degree study requires F-1/M-1."))
            p == "study" -> Quad("Student (F-1/M-1)", listOf("Exchange (J-1)"), "Academic programs generally require F-1/M-1 (or J-1 for exchanges).", emptyList())
            p == "work" || paidWork == true -> Quad("Work (H-1B/L-1/O-1) — employer-sponsored", listOf("Exchange (J-1)"), "Paid employment typically requires a work-authorized nonimmigrant category.", emptyList())
            p == "immigration" -> Quad("Immigrant (family/employment-based)", listOf("Diversity (if eligible)"), "Permanent move paths are immigrant categories.", emptyList())
            else -> Quad("Visitor (B-1/B-2)", emptyList(), "Generic short visit fits visitor categories.", warnNoWork())
        }
    }

    private fun mapUk(purpose: String?, durationDays: Int?, paidWork: Boolean?, isAcademic: Boolean?): Quad {
        val p = purpose ?: ""
        val short = (durationDays ?: 0) in 1..180
        return when {
            p == "conference" || p == "business" -> Quad("Standard Visitor (business)", listOf("Permitted Paid Engagement"), "Business visits and conferences typically use Standard Visitor.", warnNoWork())
            p == "tourist" -> Quad("Standard Visitor (tourism)", emptyList(), "Tourism/short visits.", emptyList())
            p == "study" && short -> Quad("Short-term study (up to 6 months)", listOf("Student visa"), "Short courses may use Visitor short study; longer study requires Student visa.", emptyList())
            p == "study" -> Quad("Student visa", listOf("Graduate route (post-study)"), "Longer academic courses require Student visa.", emptyList())
            p == "work" || paidWork == true -> Quad("Skilled Worker or relevant work route", listOf("Global Talent", "Scale-up"), "Paid work generally requires a work route with sponsorship.", emptyList())
            p == "immigration" -> Quad("Long-term residence/family routes", listOf("Skilled Worker to ILR"), "Permanent stay requires long-term routes.", emptyList())
            else -> Quad("Standard Visitor", emptyList(), "Generic short visit.", warnNoWork())
        }
    }

    private fun mapCa(purpose: String?, durationDays: Int?, paidWork: Boolean?, isAcademic: Boolean?): Quad {
        val p = purpose ?: ""
        val short = (durationDays ?: 0) in 1..180
        return when {
            p == "business" || p == "conference" -> Quad("Visitor (business) — TRV/eTA", listOf("Visitor (tourism)"), "Business visits use visitor class; nationality decides TRV vs eTA.", warnNoWork())
            p == "tourist" -> Quad("Visitor (tourism) — TRV/eTA", emptyList(), "Tourism/short visits.", emptyList())
            p == "study" && short -> Quad("Visitor for short course", listOf("Study Permit"), "Short courses may not need Study Permit; >6 months generally requires Study Permit.", emptyList())
            p == "study" -> Quad("Study Permit", listOf("Co-op work authorization if applicable"), "Longer study needs Study Permit.", emptyList())
            p == "work" || paidWork == true -> Quad("Work Permit (employer-specific or open)", listOf("IEC (if eligible)"), "Paid work requires a Work Permit.", emptyList())
            p == "immigration" -> Quad("Permanent Residence (Express Entry/family)", emptyList(), "Permanent immigration pathways.", emptyList())
            else -> Quad("Visitor", emptyList(), "Generic short visit.", warnNoWork())
        }
    }

    private fun mapAu(purpose: String?, durationDays: Int?, paidWork: Boolean?, isAcademic: Boolean?): Quad {
        val p = purpose ?: ""
        val short = (durationDays ?: 0) in 1..180
        return when {
            p == "business" || p == "conference" -> Quad("Visitor (subclass 600) — business stream", listOf("Visitor (tourist)"), "Business activities/conferences under visitor streams.", warnNoWork())
            p == "tourist" -> Quad("Visitor (subclass 600) — tourist", emptyList(), "Tourism/short visits.", emptyList())
            p == "study" && short -> Quad("Visitor (short study permitted)", listOf("Student (subclass 500)"), "Short study allowed on visitor; full courses need Student visa.", emptyList())
            p == "study" -> Quad("Student (subclass 500)", emptyList(), "Longer study requires Student visa.", emptyList())
            p == "work" || paidWork == true -> Quad("Temporary Skill Shortage / other work routes", emptyList(), "Paid work requires work-authorized visas.", emptyList())
            p == "immigration" -> Quad("Permanent residence pathways", emptyList(), "Long-term migration routes.", emptyList())
            else -> Quad("Visitor (subclass 600)", emptyList(), "Generic short visit.", warnNoWork())
        }
    }

    private fun mapGeneric(purpose: String?, durationDays: Int?, paidWork: Boolean?, isAcademic: Boolean?): Quad {
        val p = purpose ?: ""
        val short = (durationDays ?: 0) in 1..180
        return when {
            p == "conference" || p == "business" -> Quad("Business visitor/short-stay", listOf("Tourist/visitor"), "Conferences/meetings are usually business visitor with no local employment.", warnNoWork())
            p == "tourist" -> Quad("Tourist/visitor short-stay", emptyList(), "Tourism/short visits.", emptyList())
            p == "study" && short -> Quad("Visitor short study", listOf("Student/long-stay"), "Short non-credit study may be under visitor; longer study needs student/residence.", emptyList())
            p == "study" -> Quad("Student/long-stay study", emptyList(), "Academic study typically needs a student/residence visa.", emptyList())
            p == "work" || paidWork == true -> Quad("Work (employer-sponsored) route", emptyList(), "Paid work requires a work-authorized visa.", emptyList())
            p == "immigration" -> Quad("Immigration/permanent residence route", emptyList(), "For permanent moves, look at immigration categories.", emptyList())
            else -> Quad("Visitor short-stay", emptyList(), "Generic short visit.", warnNoWork())
        }
    }

    // ------------ Utils ------------
    private data class Quad(
        val visa: String,
        val alts: List<String>,
        val reason: String,
        val warnings: List<String>
    )

    private fun warnNoWork(): List<String> = listOf("Visitor/business visitor categories generally do not permit local employment or income in the destination.")

    private fun normalizePurpose(input: String?): String? {
        if (input == null) return null
        val t = input.trim().lowercase()
        return when {
            t.contains("conference") || t.contains("seminar") || t.contains("meeting") -> "conference"
            t.contains("business") -> "business"
            t.contains("tour") || t.contains("visit") || t.contains("vacation") || t.contains("holiday") -> "tourist"
            t.contains("study") || t.contains("student") || t.contains("course") -> "study"
            t.contains("work") || t.contains("job") || t.contains("paid") -> "work"
            t.contains("immig") || t.contains("residen") || t.contains("pr") -> "immigration"
            else -> t
        }
    }
}
