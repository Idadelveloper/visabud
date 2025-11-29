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
    val lastSeen: Long = 0L
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
data class ChatMessageEntity(
    val id: String,
    val threadId: String = "default",
    val role: String, // user|assistant|system
    val content: String,
    val timestamp: Long
)
