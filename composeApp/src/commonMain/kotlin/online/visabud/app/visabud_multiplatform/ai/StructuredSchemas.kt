package online.visabud.app.visabud_multiplatform.ai

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class RoadmapStep(
    val title: String,
    val description: String? = null,
    val estimatedMonths: Int? = null,
    val requiredDocs: List<String> = emptyList()
)

@Serializable
data class RoadmapJson(
    val routeName: String,
    val steps: List<RoadmapStep> = emptyList(),
    val confidence: Int? = null,
    val citations: List<String> = emptyList()
)

@Serializable
data class ReviewIssue(
    val field: String,
    val problem: String,
    val severity: String
)

@Serializable
data class DocumentReviewJson(
    val status: String,
    val issues: List<ReviewIssue> = emptyList(),
    val suggestions: List<String> = emptyList()
)

private val json = Json { ignoreUnknownKeys = true; isLenient = true }

fun extractFirstJsonObject(text: String): String? {
    val start = text.indexOf('{')
    if (start < 0) return null
    // naive brace matching
    var depth = 0
    for (i in start until text.length) {
        when (text[i]) {
            '{' -> depth++
            '}' -> {
                depth--
                if (depth == 0) return text.substring(start, i + 1)
            }
        }
    }
    return null
}

fun parseRoadmapOrNull(text: String): RoadmapJson? = try {
    val obj = extractFirstJsonObject(text) ?: return null
    json.decodeFromString(RoadmapJson.serializer(), obj)
} catch (_: Throwable) { null }

fun parseDocReviewOrNull(text: String): DocumentReviewJson? = try {
    val obj = extractFirstJsonObject(text) ?: return null
    json.decodeFromString(DocumentReviewJson.serializer(), obj)
} catch (_: Throwable) { null }
