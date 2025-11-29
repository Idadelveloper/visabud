package online.visabud.app.visabud_multiplatform.ai

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import online.visabud.app.visabud_multiplatform.data.ChatMessageEntity
import online.visabud.app.visabud_multiplatform.data.UserProfile

/**
 * Profile Builder Tool
 *
 * Purpose:
 * - Extracts missing profile fields from ongoing chat text (fully local)
 * - Fields: nationality, passport validity/expiry, occupation, financial capacity, travel experience, purpose of travel
 * - Stores updates via UserProfileMemory (edge/local)
 * - Provides targeted follow-up questions when data is missing, e.g.:
 *   "To prepare your checklist for Germany, do you currently have a valid passport?"
 */
object ProfileBuilderTool {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Serializable
    data class ExtractionResult(
        val nationality: String? = null,
        val passportExpiry: String? = null, // ISO yyyy-MM-dd when available
        val passportValidMention: Boolean? = null, // true if user says passport is valid/current
        val occupation: String? = null,
        val finances: String? = null, // low|medium|high
        val travelExperience: String? = null, // e.g., "visited UK, FR", or "first-time traveler"
        val purposes: List<String> = emptyList(), // study|work|tourist|immigration
        val foundFields: List<String> = emptyList(),
        val missingFields: List<String> = emptyList(),
        val notes: List<String> = emptyList()
    ) {
        fun toJson(): String = json.encodeToString(this)
        fun isEmpty(): Boolean =
            nationality == null && passportExpiry == null && passportValidMention == null &&
            occupation == null && finances == null && travelExperience == null && purposes.isEmpty()
    }

    // ---- Public API ----

    /** Extract from a single chat message */
    fun extractFromMessage(text: String): ExtractionResult {
        val t = text.trim()
        if (t.isEmpty()) return ExtractionResult()
        val found = mutableSetOf<String>()
        val notes = mutableListOf<String>()

        // Nationality / country lines
        val nationality = detectNationality(t)?.also { found += "nationality" }

        // Passport expiry and validity mentions
        val exp = detectPassportExpiry(t)?.also { found += "passportExpiry" }
        val valid = detectPassportValidityMention(t)?.also { found += "passportValidMention" }

        // Occupation/work status
        val occupation = detectOccupation(t)?.also { found += "occupation" }

        // Finances bucket
        val finances = detectFinances(t)?.also { found += "finances" }

        // Travel experience
        val travel = detectTravelExperience(t)?.also { found += "travelExperience" }

        // Purpose of travel
        val purposes = detectPurposes(t).also { if (it.isNotEmpty()) found += "purposes" }

        // Missing is computed later in context of current profile; here we return found only
        return ExtractionResult(
            nationality = nationality,
            passportExpiry = exp,
            passportValidMention = valid,
            occupation = occupation,
            finances = finances,
            travelExperience = travel,
            purposes = purposes,
            foundFields = found.toList(),
            missingFields = emptyList(),
            notes = notes
        )
    }

    /** Extract by scanning all user/assistant messages (user messages prioritized). */
    fun extractFromChat(messages: List<ChatMessageEntity>): ExtractionResult {
        var acc = ExtractionResult()
        for (m in messages) {
            // Prefer user messages, but scan all
            val weight = if (m.role == "user") 2 else 1
            val r = extractFromMessage(m.content)
            acc = merge(acc, r, preferNew = weight >= 2)
        }
        return acc
    }

    /** Merge two extraction results with simple precedence rules. */
    private fun merge(a: ExtractionResult, b: ExtractionResult, preferNew: Boolean = true): ExtractionResult {
        fun <T> pick(x: T?, y: T?): T? = if (preferNew) y ?: x else x ?: y
        return ExtractionResult(
            nationality = pick(a.nationality, b.nationality),
            passportExpiry = pick(a.passportExpiry, b.passportExpiry),
            passportValidMention = pick(a.passportValidMention, b.passportValidMention),
            occupation = pick(a.occupation, b.occupation),
            finances = pick(a.finances, b.finances),
            travelExperience = pick(a.travelExperience, b.travelExperience),
            purposes = (a.purposes + b.purposes).distinct(),
            foundFields = (a.foundFields + b.foundFields).distinct(),
            missingFields = emptyList(),
            notes = (a.notes + b.notes).distinct()
        )
    }

    /**
     * Given current profile and an extraction, compute which fields are still missing
     * and build a courteous single question tailored to the context (e.g., destination).
     */
    fun buildMissingInfoPrompt(
        profile: UserProfile?,
        extraction: ExtractionResult,
        neededFor: String? = null, // e.g., "checklist", "roadmap", "cost estimate"
        destination: String? = null
    ): String? {
        val missing = mutableListOf<String>()
        val p = profile
        if (p?.nationality.isNullOrBlank() && extraction.nationality.isNullOrBlank()) missing += "nationality"
        if (p?.passportExpiry.isNullOrBlank() && extraction.passportExpiry.isNullOrBlank()) missing += "passport validity"
        if (p?.workStatus.isNullOrBlank() && extraction.occupation.isNullOrBlank()) missing += "occupation"
        if (p?.finances.isNullOrBlank() && extraction.finances.isNullOrBlank()) missing += "financial capacity"
        // travel experience is optional
        if (p?.selectedVisaGoals.isNullOrEmpty() && extraction.purposes.isEmpty()) missing += "purpose of travel"
        if (missing.isEmpty()) return null

        val context = when {
            !destination.isNullOrBlank() && !neededFor.isNullOrBlank() -> "To prepare your $neededFor for $destination,"
            !destination.isNullOrBlank() -> "For $destination,"
            !neededFor.isNullOrBlank() -> "To proceed with your $neededFor,"
            else -> "To personalize my advice,"
        }
        // If only passport validity is missing and destination provided, use the exact phrasing example
        if (missing.size == 1 && missing.first() == "passport validity" && !destination.isNullOrBlank()) {
            return "To prepare your checklist for $destination, do you currently have a valid passport?"
        }
        val list = humanJoin(missing)
        return "$context could you share your $list?"
    }

    /** Apply extracted fields into the on-device profile memory. */
    suspend fun applyToProfile(profile: UserProfile?, extraction: ExtractionResult): UserProfile {
        val base = profile ?: UserProfileMemory.getOrCreate()
        val goals = if (extraction.purposes.isNotEmpty()) extraction.purposes else base.selectedVisaGoals
        val newWorkStatus = extraction.occupation ?: base.workStatus
        val updated = UserProfileMemory.apply(
            UserProfileMemory.PartialProfileUpdate(
                nationality = extraction.nationality ?: base.nationality,
                passportExpiry = extraction.passportExpiry ?: base.passportExpiry,
                workStatus = newWorkStatus,
                finances = extraction.finances ?: base.finances,
                selectedVisaGoals = if (goals.isEmpty()) null else goals
            )
        )
        return updated
    }

    /** Convenience: scan chat, update profile, and produce a follow-up question if needed. */
    suspend fun autoFillFromChat(
        messages: List<ChatMessageEntity>,
        destination: String? = null,
        neededFor: String? = null
    ): AutoFillResult {
        val profile = UserProfileMemory.getOrCreate()
        val extracted = extractFromChat(messages)
        val updated = applyToProfile(profile, extracted)
        val prompt = buildMissingInfoPrompt(updated, extracted, neededFor, destination)
        val stillMissing = computeMissing(updated, extracted)
        return AutoFillResult(updated, extracted, prompt, stillMissing)
    }

    @Serializable
    data class AutoFillResult(
        val profile: UserProfile,
        val extracted: ExtractionResult,
        val prompt: String?,
        val missing: List<String>
    )

    fun toJson(result: AutoFillResult): String = json.encodeToString(result)

    // ---- Heuristics ----

    private fun detectNationality(t: String): String? {
        // Examples: "I'm Indian", "My nationality is Nigerian", "I hold a UK passport"
        val patterns = listOf(
            Regex("(?i)(?:i am|i'm|my nationality is|nationality:)\\s*([A-Za-z]{3,})"),
            Regex("(?i)(?:passport|citizenship)\\s*(?:is|:)?\\s*([A-Za-z]{3,})"),
            Regex("(?i)from\\s+([A-Za-z]{3,})\\b.*passport")
        )
        for (r in patterns) {
            val m = r.find(t)
            val g = m?.groupValues?.getOrNull(1)
            if (!g.isNullOrBlank()) return normalizeCountry(g)
        }
        return null
    }

    private fun detectPassportExpiry(t: String): String? {
        // Look for explicit dates near "passport"/"expiry"
        val near = Regex("(?i)(passport).{0,30}(\n|\r| |:)*(expires?|expiry|expiration|valid (?:until|till|to)).{0,15}([0-9A-Za-z ./-]{6,20})")
        val m = near.find(t)
        val raw = m?.groupValues?.getOrNull(3)
        return normalizeDate(raw)
    }

    private fun detectPassportValidityMention(t: String): Boolean? {
        val positive = listOf("valid passport", "passport is valid", "current passport", "have a passport")
        val negative = listOf("expired passport", "passport expired", "no passport", "don't have a passport")
        val low = t.lowercase()
        return when {
            negative.any { low.contains(it) } -> false
            positive.any { low.contains(it) } -> true
            else -> null
        }
    }

    private fun detectOccupation(t: String): String? {
        val low = t.lowercase()
        val common = listOf("student", "engineer", "developer", "nurse", "teacher", "manager", "designer", "unemployed", "self-employed", "freelancer")
        for (w in common) if (low.contains(w)) return w
        val r = Regex("(?i)(?:i work as a|my occupation is|job:|occupation:)\\s*([A-Za-z /-]{3,40})")
        val m = r.find(t)
        val g = m?.groupValues?.getOrNull(1)?.trim()
        return g?.ifBlank { null }
    }

    private fun detectFinances(t: String): String? {
        val low = t.lowercase()
        return when {
            Regex("(?i)(limited funds|low budget|tight budget|broke|financial difficulty)").containsMatchIn(t) -> "low"
            Regex("(?i)(comfortable budget|sufficient funds|savings|can afford)").containsMatchIn(t) -> "medium"
            Regex("(?i)(high budget|ample funds|significant savings|well-funded)").containsMatchIn(t) -> "high"
            low.contains("bank balance") -> null // ambiguous without number
            else -> null
        }
    }

    private fun detectTravelExperience(t: String): String? {
        val low = t.lowercase()
        return when {
            Regex("(?i)(first time (?:travel|travelling|applying))").containsMatchIn(t) -> "first-time traveler"
            Regex("(?i)(visited|been to|traveled to|travelled to)\\s+([A-Za-z ,]{2,60})").find(t)?.let {
                val places = it.groupValues.getOrNull(2)?.trim()
                if (!places.isNullOrBlank()) "visited: $places" else null
            } != null -> Regex("(?i)(visited|been to|traveled to|travelled to)\\s+([A-Za-z ,]{2,60})").find(t)!!.let { "visited: ${it.groupValues[2].trim()}" }
            else -> null
        }
    }

    private fun detectPurposes(t: String): List<String> {
        val low = t.lowercase()
        val res = mutableListOf<String>()
        if (listOf("study", "student").any { low.contains(it) }) res += "study"
        if (listOf("work", "job").any { low.contains(it) }) res += "work"
        if (listOf("tourist", "visit", "vacation", "holiday").any { low.contains(it) }) res += "tourist"
        if (listOf("immigration", "permanent residency", "pr", "green card").any { low.contains(it) }) res += "immigration"
        return res.distinct()
    }

    private fun humanJoin(items: List<String>): String {
        if (items.isEmpty()) return ""
        if (items.size == 1) return items.first()
        return items.dropLast(1).joinToString(", ") + " and " + items.last()
    }

    private fun normalizeCountry(s: String): String {
        val key = s.trim().replace(Regex("[^A-Za-z]"), "").lowercase()
        val map = mapOf(
            "uk" to "United Kingdom", "gb" to "United Kingdom", "british" to "United Kingdom",
            "us" to "United States", "usa" to "United States", "american" to "United States",
            "uae" to "United Arab Emirates", "emirati" to "United Arab Emirates",
            "indian" to "India", "india" to "India",
            "nigerian" to "Nigeria", "nigeria" to "Nigeria",
            "german" to "Germany", "germany" to "Germany",
            "french" to "France", "france" to "France"
        )
        return map[key] ?: s.trim()
    }

    // Basic date normalization (ISO yyyy-MM-dd) used elsewhere in project
    private fun normalizeDate(s: String?): String? {
        if (s.isNullOrBlank()) return null
        val trimmed = s.trim()
        if (trimmed.matches(Regex("\\d{4}-\\d{2}-\\d{2}"))) return trimmed
        val m1 = Regex("(\\d{1,2})[/-](\\d{1,2})[/-](\\d{4})").find(trimmed)
        if (m1 != null) {
            val d = m1.groupValues[1].padStart(2, '0')
            val mo = m1.groupValues[2].padStart(2, '0')
            val y = m1.groupValues[3]
            // Heuristic: if day > 12 treat as DD/MM else MM/DD
            return if (d.toInt() > 12) "$y-$mo-$d" else "$y-$d-$mo"
        }
        val m2 = Regex("(\\d{1,2})\\s+(Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)[a-z]*\\s+(\\d{4})", RegexOption.IGNORE_CASE).find(trimmed)
        if (m2 != null) {
            val d = m2.groupValues[1].padStart(2, '0')
            val mo = monthToNum(m2.groupValues[2])
            val y = m2.groupValues[3]
            return "$y-$mo-$d"
        }
        return trimmed
    }

    private fun monthToNum(m: String): String = when (m.lowercase().take(3)) {
        "jan" -> "01"; "feb" -> "02"; "mar" -> "03"; "apr" -> "04"; "may" -> "05"; "jun" -> "06";
        "jul" -> "07"; "aug" -> "08"; "sep" -> "09"; "oct" -> "10"; "nov" -> "11"; "dec" -> "12";
        else -> "01"
    }

    private fun computeMissing(profile: UserProfile, extraction: ExtractionResult): List<String> {
        val missing = mutableListOf<String>()
        if (profile.nationality.isNullOrBlank() && extraction.nationality.isNullOrBlank()) missing += "nationality"
        if (profile.passportExpiry.isNullOrBlank() && extraction.passportExpiry.isNullOrBlank()) missing += "passport validity"
        if (profile.workStatus.isNullOrBlank() && extraction.occupation.isNullOrBlank()) missing += "occupation"
        if (profile.finances.isNullOrBlank() && extraction.finances.isNullOrBlank()) missing += "financial capacity"
        if (profile.selectedVisaGoals.isEmpty() && extraction.purposes.isEmpty()) missing += "purpose of travel"
        return missing
    }
}
