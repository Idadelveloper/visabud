package online.visabud.app.visabud_multiplatform.ai

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import online.visabud.app.visabud_multiplatform.data.UserProfile

/**
 * Visa Comparison Tool
 *
 * Purpose:
 * - Compare visa "difficulty" across countries for a given visa type/goal (tourist, study, work, immigration).
 * - Accepts country list or group keywords (e.g., "Schengen").
 * - Outputs a table-like structured JSON suited for UI rendering by the agent.
 *
 * Design/Privacy:
 * - Fully local. Uses VisaFactsRag for official links and basic facts.
 * - Heuristics only; includes a disclaimer and citations to official sites.
 */
object VisaComparisonTool {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = false }

    // ---------------- Public DTOs ----------------
    @Serializable
    data class Criteria(
        val visaType: String,
        val nationality: String? = null,
        val notes: String? = "Heuristic comparison. Always verify on official sites."
    )

    @Serializable
    data class ComparisonRow(
        val countryCode: String,
        val country: String,
        val visaType: String,
        val difficultyScore: Int, // 1 (easiest) to 100 (hardest)
        val feesApproxUSD: Double? = null,
        val processingTimeNote: String? = null,
        val docStrictness: String? = null, // low|medium|high
        val interviewLikely: Boolean? = null,
        val visaFreeHint: String? = null,
        val notes: String? = null,
        val officialLink: String? = null,
        val citations: List<String> = emptyList()
    )

    @Serializable
    data class ComparisonResult(
        val criteria: Criteria,
        val rows: List<ComparisonRow>,
        val warnings: List<String> = emptyList(),
        val missing: List<String> = emptyList(),
        val prompt: String? = null
    )

    // --------------- Agent helpers ---------------
    fun buildMissingInfoPrompt(
        profile: UserProfile?,
        destinationsOrKeyword: String?,
        visaTypeOrGoal: String?
    ): String? {
        val missing = mutableListOf<String>()
        if (destinationsOrKeyword.isNullOrBlank()) missing += "countries or region (e.g., UK vs US or Schengen)"
        if (visaTypeOrGoal.isNullOrBlank()) missing += "visa type/purpose (tourist, study, work, immigration)"
        if (profile?.nationality.isNullOrBlank()) missing += "your nationality (optional, improves accuracy)"
        return if (missing.isEmpty()) null else "To compare visas, please share: ${missing.joinToString(", ")}."
    }

    fun toJson(result: ComparisonResult): String = json.encodeToString(result)

    fun buildHumanReadable(result: ComparisonResult): String {
        if (result.rows.isEmpty()) {
            return buildString {
                appendLine("I couldn't resolve the requested destinations.")
                result.prompt?.let { appendLine(it) }
            }.trim()
        }
        val sb = StringBuilder()
        sb.appendLine("Visa comparison — ${result.criteria.visaType}")
        result.criteria.nationality?.let { sb.appendLine("For nationality: $it") }
        sb.appendLine()
        result.rows.forEachIndexed { i, r ->
            sb.appendLine("${i + 1}. ${r.country} (${r.countryCode}) — difficulty ${r.difficultyScore}/100")
            r.feesApproxUSD?.let { sb.appendLine("   Fee approx: $${round2(it)}") }
            r.processingTimeNote?.let { sb.appendLine("   Processing: ${r.processingTimeNote}") }
            r.docStrictness?.let { sb.appendLine("   Documents strictness: ${r.docStrictness}") }
            r.interviewLikely?.let { sb.appendLine("   Interview likely: ${if (it) "Yes" else "No"}") }
            r.visaFreeHint?.let { sb.appendLine("   Visa-free hint: ${r.visaFreeHint}") }
            r.officialLink?.let { sb.appendLine("   Official: ${r.officialLink}") }
        }
        if (result.warnings.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("Warnings:")
            result.warnings.forEach { sb.appendLine("- $it") }
        }
        sb.appendLine()
        sb.appendLine("Note: Heuristic comparison. Always verify on official sites.")
        return sb.toString().trim()
    }

    // --------------- Main entry ---------------
    suspend fun compare(
        profile: UserProfile?,
        destinationsOrKeyword: String?,
        visaTypeOrGoal: String?
    ): ComparisonResult {
        val normalizedType = normalizeVisaType(visaTypeOrGoal)
        val countries = resolveCountries(destinationsOrKeyword)
        val missing = mutableListOf<String>()
        if (countries.isEmpty()) missing += "destinations"
        if (normalizedType == "generic") missing += "specific visa type/purpose"
        val prompt = if (missing.isEmpty()) null else buildMissingInfoPrompt(profile, destinationsOrKeyword, visaTypeOrGoal)

        val nat = profile?.nationality
        val rows = countries.map { entry ->
            val (score, details) = scoreCountry(entry, normalizedType, nat)
            val fee = parseAnyUsd(entry.fees)
            val citations = listOfNotNull(entry.officialSite.takeIf { it.isNotBlank() }?.let { "Official: $it" })
            ComparisonRow(
                countryCode = entry.code,
                country = entry.country,
                visaType = normalizedType,
                difficultyScore = score,
                feesApproxUSD = fee,
                processingTimeNote = entry.processingTime,
                docStrictness = details.docStrictness,
                interviewLikely = details.interviewLikely,
                visaFreeHint = details.visaFreeHint,
                notes = details.notes,
                officialLink = entry.officialSite,
                citations = citations
            )
        }.sortedBy { it.difficultyScore }

        val crit = Criteria(visaType = normalizedType, nationality = nat)
        val warnings = buildList {
            if (nat.isNullOrBlank()) add("Nationality unknown — visa-free/exemptions can't be fully assessed.")
            add("Local dataset is limited; use the official links to confirm.")
        }
        return ComparisonResult(criteria = crit, rows = rows, warnings = warnings, missing = missing, prompt = prompt)
    }

    // --------------- Internals ---------------
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

    private suspend fun resolveCountries(destinationsOrKeyword: String?): List<VisaFactsEntry> {
        val input = destinationsOrKeyword?.trim() ?: return emptyList()
        val tokens = splitDestinations(input)
        val resolved = mutableListOf<VisaFactsEntry>()
        val seen = HashSet<String>()
        for (tok in tokens) {
            val lower = tok.lowercase()
            // Group keywords
            val group = when {
                lower.contains("schengen") -> SCHENGEN_CODES
                else -> null
            }
            if (group != null) {
                group.forEach { code ->
                    val e = VisaFactsRag.findCountryByNameOrCode(code)
                    if (e != null && seen.add(e.code)) resolved += e
                }
                continue
            }
            val entry = VisaFactsRag.findCountryByNameOrCode(tok)
            if (entry != null && seen.add(entry.code)) resolved += entry
        }
        return resolved
    }

    private fun splitDestinations(s: String): List<String> {
        val cleaned = s.replace("&", ",")
            .replace(" vs ", ",", ignoreCase = true)
            .replace(" versus ", ",", ignoreCase = true)
            .replace(" and ", ",", ignoreCase = true)
        return cleaned.split(",", "/", "|").map { it.trim() }.filter { it.isNotEmpty() }
    }

    private data class ScoreDetails(
        val docStrictness: String?,
        val interviewLikely: Boolean?,
        val visaFreeHint: String?,
        val notes: String?
    )

    private fun scoreCountry(entry: VisaFactsEntry, visaType: String, nationality: String?): Pair<Int, ScoreDetails> {
        var score = 50 // baseline
        var docStrictness: String? = null
        var interviewLikely: Boolean? = null
        var visaFreeHint: String? = null
        val notes = mutableListOf<String>()

        // Visa-type difficulty baseline
        score += when (visaType) {
            "tourist" -> -10
            "study" -> +5
            "work" -> +15
            "immigration" -> +25
            else -> 0
        }

        // Document strictness heuristic from known regions
        val strictCountries = setOf("DE", "FR") // Schengen often strict on docs
        val interviewCountries = setOf("US") // interviews more common for US NIV
        val highCostCountries = setOf("US", "UK", "CA", "AU", "JP", "FR", "DE")

        if (entry.code in strictCountries) {
            docStrictness = "high"
            score += 8
            notes += "Strict documentation and appointment processes typical."
        } else if (entry.code in setOf("UK", "CA", "AU")) {
            docStrictness = "medium"
            score += 4
        } else {
            docStrictness = "medium"
        }

        if (entry.code in interviewCountries && visaType != "tourist") {
            interviewLikely = true
            score += 6
            notes += "Interview commonly required."
        } else if (entry.code == "US" && visaType == "tourist") {
            interviewLikely = true
            score += 3
        } else {
            interviewLikely = null
        }

        // Visa-free hint for tourist
        if (visaType == "tourist" && !entry.visaFreePolicy.isNullOrBlank()) {
            visaFreeHint = entry.visaFreePolicy
            // If nationality provided we could fine-tune; without it, slightly lower difficulty
            score -= 6
        }

        // Processing time hints
        entry.processingTime?.let {
            if (it.contains("days", true)) score -= 2
            if (it.contains("months", true)) score += 4
        }

        // Fees impact (if clearly high, nudge score up)
        val fee = parseAnyUsd(entry.fees)
        if (fee != null) {
            val bump = when {
                fee >= 400 -> 6
                fee >= 200 -> 3
                else -> 0
            }
            score += bump
        } else if (entry.code in highCostCountries) {
            score += 2
        }

        // Clamp and details
        val finalScore = score.coerceIn(1, 100)
        return finalScore to ScoreDetails(
            docStrictness = docStrictness,
            interviewLikely = interviewLikely,
            visaFreeHint = visaFreeHint,
            notes = if (notes.isEmpty()) null else notes.joinToString("; ")
        )
    }

    private fun parseAnyUsd(s: String?): Double? {
        if (s.isNullOrBlank()) return null
        val m = Regex("([0-9]{2,5})(?:\\.[0-9]{1,2})?").find(s)
        return m?.value?.toDoubleOrNull()
    }

    private fun round2(x: Double): Double = kotlin.math.round(x * 100.0) / 100.0

    private val SCHENGEN_CODES = listOf(
        // List broadly, filtered by dataset availability during resolution
        "DE", "FR", "ES", "IT", "NL", "BE", "PT", "GR", "AT", "PL", "CZ", "HU", "SK", "SI", "LV", "LT", "EE", "SE", "FI", "NO", "IS", "CH", "LU", "MT", "DK"
    )
}
