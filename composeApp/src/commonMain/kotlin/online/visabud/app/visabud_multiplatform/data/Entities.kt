package online.visabud.app.visabud_multiplatform.data

import kotlinx.serialization.Serializable

/**
 * Storage entities (platform-agnostic) matching the issue description.
 * These are pure data classes intended to be used with Room/SQLDelight or
 * a custom persistence layer. For now, we will ship in-memory repositories
 * and keep these models stable for later DB integration.
 */

@Serializable
data class UserProfile(
    val id: String = "local_user",
    val name: String? = null,
    val dob: String? = null,
    val countryOfResidence: String? = null,
    val nationality: String? = null, // added per requirement
    val workStatus: String? = null, // e.g., "employed", "student"
    val currentVisa: String? = null,
    val education: String? = null,
    val workYears: Int? = null,
    val languages: String? = null, // comma-separated for MVP
    val finances: String? = null, // low|med|high
    val passportExpiry: String? = null, // ISO-8601 date string (YYYY-MM-DD)
    val travelHistory: List<TravelEvent> = emptyList(),
    val preferences: UserPreferences? = null,
    val selectedVisaGoals: List<String> = emptyList(),
    val savedDocs: List<String> = emptyList(),
    val lastSeen: Long = 0L
)

@Serializable
data class TravelEvent(
    val countryCode: String, // ISO alpha-2
    val entryDate: String? = null, // YYYY-MM-DD
    val exitDate: String? = null,  // YYYY-MM-DD
    val purpose: String? = null // tourism, study, work, etc.
)

@Serializable
data class UserPreferences(
    val preferredDestinations: List<String> = emptyList(),
    val budgetLevel: String? = null, // low|medium|high
    val riskTolerance: String? = null, // low|medium|high
    val wantsInterviewPrep: Boolean = false,
    val notificationsEnabled: Boolean = true
)

@Serializable
data class DocumentMeta(
    val id: String,
    val type: String, // passport, bank_statement, etc.
    val filename: String,
    val parsedFieldsJson: String, // expiry date etc.
    val uploadedAt: Long,
    val encryptedPath: String
)

@Serializable
data class EmbeddingItem(
    val id: String,
    val text: String,
    /**
     * Store float[] as bytes. Upstream encrypt-at-rest recommended (SQLCipher) or
     * field-level encryption before persisting.
     */
    val vector: ByteArray,
    val tags: String = "",
    val createdAt: Long
)

@Serializable
data class Roadmap(
    val id: String,
    val title: String,
    val description: String? = null,
    // JSON-encoded steps tree to keep the model flexible across DBs
    val stepsJson: String,
    val createdAt: Long,
    val updatedAt: Long
)

@Serializable
data class Checklist(
    val id: String,
    val title: String,
    val destination: String,
    val visaType: String,
    val humanText: String,
    val jsonPayload: String,
    val createdAt: Long,
    val updatedAt: Long
)

@Serializable
data class ChatMessageEntity(
    val id: String,
    val threadId: String = "default",
    val role: String, // user|assistant|system
    val content: String,
    val timestamp: Long
)
