package online.visabud.app.visabud_multiplatform.doc

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Platform-agnostic document pipeline facade. Android/iOS provide actual implementations.
 */
interface DocumentPipeline {
    /** Extracts key fields from a document image (path or URI string). */
    suspend fun extractFields(imagePath: String): Map<String, String>

    /**
     * Runs a review using the local LLM, given parsed fields and target visa.
     * Returns JSON string per PromptTemplates.documentReviewSystem schema.
     */
    suspend fun reviewDocument(parsedFields: Map<String, String>, targetVisa: String): String
}

/** Expect an actual provider on each platform. */
expect fun documentPipeline(): DocumentPipeline

/** Helper to encode map to JSON consistently. */
internal fun mapToJson(map: Map<String, String>): String = Json { ignoreUnknownKeys = true }.encodeToString(map)

