package online.visabud.app.visabud_multiplatform.ai

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import online.visabud.app.visabud_multiplatform.data.DataModule
import online.visabud.app.visabud_multiplatform.data.TravelEvent
import online.visabud.app.visabud_multiplatform.data.UserPreferences
import online.visabud.app.visabud_multiplatform.data.UserProfile

/**
 * User Profile Memory tool
 * Purpose: Stores user's persistent info and provides helper APIs for the agent
 * to read/update and build concise JSON to personalize answers.
 *
 * Storage is backed by ProfileRepository from DataModule (in-memory by default),
 * which can later be replaced by a secure persistent implementation per platform.
 */
object UserProfileMemory {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    // --- Basic accessors ---
    suspend fun getOrCreate(): UserProfile {
        val existing = DataModule.profiles.getProfile()
        return existing ?: UserProfile().also { DataModule.profiles.upsertProfile(it) }
    }

    suspend fun upsert(profile: UserProfile) {
        DataModule.profiles.upsertProfile(profile.copy(lastSeen = now()))
    }

    // --- Partial update merging ---
    data class PartialProfileUpdate(
        val name: String? = null,
        val dob: String? = null,
        val countryOfResidence: String? = null,
        val nationality: String? = null,
        val workStatus: String? = null,
        val currentVisa: String? = null,
        val education: String? = null,
        val workYears: Int? = null,
        val languages: String? = null,
        val finances: String? = null,
        val passportExpiry: String? = null,
        val preferences: UserPreferences? = null,
        val selectedVisaGoals: List<String>? = null
    )

    suspend fun apply(update: PartialProfileUpdate) : UserProfile {
        val base = getOrCreate()
        val merged = base.copy(
            name = update.name ?: base.name,
            dob = update.dob ?: base.dob,
            countryOfResidence = update.countryOfResidence ?: base.countryOfResidence,
            nationality = update.nationality ?: base.nationality,
            workStatus = update.workStatus ?: base.workStatus,
            currentVisa = update.currentVisa ?: base.currentVisa,
            education = update.education ?: base.education,
            workYears = update.workYears ?: base.workYears,
            languages = update.languages ?: base.languages,
            finances = update.finances ?: base.finances,
            passportExpiry = update.passportExpiry ?: base.passportExpiry,
            preferences = update.preferences ?: base.preferences,
            selectedVisaGoals = update.selectedVisaGoals ?: base.selectedVisaGoals,
            lastSeen = now()
        )
        upsert(merged)
        return merged
    }

    // --- Convenience setters used by agent tools ---
    suspend fun setNationality(nationality: String?): UserProfile = apply(PartialProfileUpdate(nationality = nationality))
    suspend fun setPassportExpiry(dateIso: String?): UserProfile = apply(PartialProfileUpdate(passportExpiry = dateIso))
    suspend fun setEducation(education: String?): UserProfile = apply(PartialProfileUpdate(education = education))
    suspend fun setWorkYears(years: Int?): UserProfile = apply(PartialProfileUpdate(workYears = years))
    suspend fun setFinances(level: String?): UserProfile = apply(PartialProfileUpdate(finances = level))
    suspend fun setCurrentVisa(visa: String?): UserProfile = apply(PartialProfileUpdate(currentVisa = visa))
    suspend fun setWorkStatus(status: String?): UserProfile = apply(PartialProfileUpdate(workStatus = status))
    suspend fun setCountryOfResidence(codeOrName: String?): UserProfile = apply(PartialProfileUpdate(countryOfResidence = codeOrName))
    suspend fun setLanguages(csv: String?): UserProfile = apply(PartialProfileUpdate(languages = csv))

    suspend fun setPreferences(prefs: UserPreferences?): UserProfile = apply(PartialProfileUpdate(preferences = prefs))
    suspend fun setVisaGoals(goals: List<String>): UserProfile = apply(PartialProfileUpdate(selectedVisaGoals = goals))

    suspend fun addTravelEvent(event: TravelEvent): UserProfile {
        val base = getOrCreate()
        val merged = base.copy(travelHistory = base.travelHistory + event, lastSeen = now())
        upsert(merged)
        return merged
    }

    suspend fun addSavedDoc(docId: String): UserProfile {
        val base = getOrCreate()
        if (base.savedDocs.contains(docId)) return base
        val merged = base.copy(savedDocs = base.savedDocs + docId, lastSeen = now())
        upsert(merged)
        return merged
    }

    // --- Agent helpers ---
    fun buildProfileJson(profile: UserProfile): String = json.encodeToString(profile)

    fun buildSimpleSummary(profile: UserProfile): String = buildString {
        appendLine("User Profile Summary")
        profile.name?.let { appendLine("- Name: $it") }
        profile.nationality?.let { appendLine("- Nationality: $it") }
        profile.countryOfResidence?.let { appendLine("- Residence: $it") }
        profile.passportExpiry?.let { appendLine("- Passport expiry: $it") }
        profile.education?.let { appendLine("- Education: $it") }
        profile.workYears?.let { appendLine("- Work experience: ${it}y") }
        profile.finances?.let { appendLine("- Finances: $it") }
        if (profile.selectedVisaGoals.isNotEmpty()) appendLine("- Goals: ${profile.selectedVisaGoals.joinToString()}")
        if (profile.preferences != null) appendLine("- Prefers: ${profile.preferences.preferredDestinations.joinToString().ifBlank { "(none)" }}")
        if (profile.travelHistory.isNotEmpty()) appendLine("- Travel events: ${profile.travelHistory.size}")
        if (profile.savedDocs.isNotEmpty()) appendLine("- Saved docs: ${profile.savedDocs.size}")
    }.trim()

    fun buildMissingInfoPromptForAssessment(profile: UserProfile): String? {
        val missing = mutableListOf<String>()
        if (profile.nationality.isNullOrBlank()) missing += "nationality"
        if (profile.passportExpiry.isNullOrBlank()) missing += "passport expiry"
        if (profile.education.isNullOrBlank()) missing += "education"
        if (profile.workYears == null) missing += "years of work experience"
        // finances are optional, but helpful
        if (profile.finances.isNullOrBlank()) missing += "financial status (optional)"
        if (missing.isEmpty()) return null
        return "To assess eligibility better, could you provide your ${missing.joinToString(", ")} ?"
    }

    fun buildMissingInfoPromptForRoadmap(profile: UserProfile): String? {
        val missing = mutableListOf<String>()
        if (profile.selectedVisaGoals.isEmpty()) missing += "visa goal (e.g., study, work, visit)"
        if (profile.nationality.isNullOrBlank()) missing += "nationality"
        if (profile.countryOfResidence.isNullOrBlank()) missing += "country of residence"
        if (missing.isEmpty()) return null
        return "To draft a personalized roadmap, please share your ${missing.joinToString(", ")}."
    }

    fun isSufficientForEligibilityAssessment(profile: UserProfile): Boolean {
        return !profile.nationality.isNullOrBlank() &&
                !profile.education.isNullOrBlank() &&
                profile.workYears != null
    }

    private fun now(): Long = 0L
}
