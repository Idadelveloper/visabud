package online.visabud.app.visabud_multiplatform.doc

import com.cactus.CactusCompletionParams
import com.cactus.CactusInitParams
import com.cactus.CactusLM
import com.cactus.ChatMessage
import com.cactus.InferenceMode
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import online.visabud.app.visabud_multiplatform.ai.PromptTemplates
import online.visabud.app.visabud_multiplatform.ai.VisaFactsRag

private class AndroidDocumentPipeline : DocumentPipeline {
    private val lm get() = online.visabud.app.visabud_multiplatform.ai.CactusLmHolder.instance()
    private val initMutex = Mutex()
    private var initialized = false
    private var modelSlug: String = "local-qwen3-0.6"

    private suspend fun ensureReady(contextSize: Int = 2048) = initMutex.withLock {
        if (initialized && online.visabud.app.visabud_multiplatform.ai.CactusLmHolder.isLoaded()) return
        // Prefer a local vision-capable model if available (one-time check)
        try {
            val models = online.visabud.app.visabud_multiplatform.ai.CactusLmHolder.withLm { it.getModels() }
            val vision = models.firstOrNull { it.supports_vision && it.slug.startsWith("local-") }
            if (vision != null) modelSlug = vision.slug
        } catch (_: Throwable) {}
        // Ensure the shared LM is initialized for the chosen model
        online.visabud.app.visabud_multiplatform.ai.CactusLmHolder.ensureReady(contextSize = contextSize, model = modelSlug)
        // Ensure RAG store populated (non-fatal)
        try {
            VisaFactsRag.ensurePersisted { text ->
                val emb = online.visabud.app.visabud_multiplatform.ai.CactusLmHolder.withLm { it.generateEmbedding(text) }
                if (emb == null || !emb.success) emptyList() else emb.embeddings
            }
        } catch (_: Throwable) { }
        initialized = true
    }

    override suspend fun extractFields(imagePath: String): Map<String, String> {
        ensureReady()
        // First attempt: vision extraction if model supports images
        val useImages = try {
            val models = online.visabud.app.visabud_multiplatform.ai.CactusLmHolder.withLm { it.getModels() }
            models.firstOrNull { it.slug == modelSlug }?.supports_vision == true
        } catch (_: Throwable) { false }

        val sys = "You are a vision OCR assistant. Extract structured fields from the provided document image. Return JSON only with keys: passportNumber, expiryDate, name, dob. If uncertain, leave values empty."
        val user = if (useImages) ChatMessage("Extract fields from this passport document image.", "user", images = listOf(imagePath)) else ChatMessage("No image support available. If possible, infer nothing and return empty fields.", "user")

        val result = online.visabud.app.visabud_multiplatform.ai.CactusLmHolder.withLm { it.generateCompletion(
            messages = listOf(ChatMessage(sys, "system"), user),
            params = CactusCompletionParams(model = modelSlug, temperature = 0.0, maxTokens = 200, mode = InferenceMode.LOCAL)
        ) }
        val json = result?.response?.trim().orEmpty()
        // Very lightweight JSON parse into Map<String,String>; tolerate non-JSON by returning empty map
        return try {
            parseSimpleJsonObject(json)
        } catch (_: Throwable) {
            emptyMap()
        }
    }

    override suspend fun reviewDocument(parsedFields: Map<String, String>, targetVisa: String): String {
        ensureReady()
        // Retrieve facts by targetVisa/country term
        val facts = try {
            VisaFactsRag.retrieveFromRepo(targetVisa, embedder = { q ->
                val emb = lm.generateEmbedding(q)
                if (emb == null || !emb.success) emptyList() else emb.embeddings
            }, topK = 6)
        } catch (_: Throwable) { emptyList() }

        val sys = PromptTemplates.documentReviewSystem() + "\n" + (VisaFactsRag.buildSystemPreamble(facts) ?: "")
        val payload = "{" +
                "\"targetVisa\": \"" + targetVisa.replace("\"", "'") + "\"," +
                "\"documentJson\": " + mapToJson(parsedFields) +
                "}"
        val result = lm.generateCompletion(
            messages = listOf(ChatMessage(sys, "system"), ChatMessage(payload, "user")),
            params = CactusCompletionParams(model = modelSlug, temperature = 0.1, maxTokens = 500, mode = InferenceMode.LOCAL)
        )
        return result?.response?.trim().orEmpty()
    }
}

actual fun documentPipeline(): DocumentPipeline = AndroidDocumentPipeline()

// Minimal permissive JSON object parser for a flat string map {"k":"v",...}
private fun parseSimpleJsonObject(json: String): Map<String, String> {
    val trimmed = json.trim().removePrefix("```").removeSuffix("```").trim()
    if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) return emptyMap()
    val body = trimmed.substring(1, trimmed.length - 1)
    val map = linkedMapOf<String, String>()
    body.split(Regex(",(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)")).forEach { pair ->
        val parts = pair.split(":", limit = 2)
        if (parts.size == 2) {
            val k = parts[0].trim().trim('"')
            val v = parts[1].trim().trim(',').trim().trim('"')
            map[k] = v
        }
    }
    return map
}
