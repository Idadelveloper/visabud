package online.visabud.app.visabud_multiplatform.ai

import com.cactus.CactusCompletionParams
import com.cactus.CactusInitParams
import com.cactus.CactusLM
import com.cactus.InferenceMode
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private class CactusAiChatClientIOS : AiChatClient {
    private val lm: CactusLM by lazy { CactusLM(enableToolFiltering = false) }
    private val initMutex = Mutex()
    private var initialized = false
    private val modelSlug = "local-qwen3-0.6"

    override suspend fun ensureReady(contextSize: Int): Unit = initMutex.withLock {
        if (initialized && lm.isLoaded()) return
        // Only download if not already present
        if (!isModelDownloaded()) {
            lm.downloadModel(modelSlug)
        }
        lm.initializeModel(
            CactusInitParams(
                model = modelSlug,
                contextSize = contextSize
            )
        )
        // Precompute RAG embeddings for visa facts using model's embedding endpoint
        try {
            VisaFactsRag.ensureLoaded { text ->
                val emb = lm.generateEmbedding(text)
                if (emb == null || !emb.success) emptyList() else emb.embeddings
            }
        } catch (_: Throwable) {
            // Non-fatal if RAG preload fails
        }
        initialized = true
    }

    override suspend fun send(messages: List<ChatMsg>, temperature: Double?): String {
        // Build system preamble from retrieved facts
        val userQuery = messages.lastOrNull { it.role == "user" }?.content
            ?: messages.lastOrNull()?.content
        val systemPreamble: String? = try {
            if (userQuery != null && VisaFactsRag.isInitialized()) {
                val hits = VisaFactsRag.retrieve(userQuery, embedder = { q ->
                    val emb = lm.generateEmbedding(q)
                    if (emb == null || !emb.success) emptyList() else emb.embeddings
                }, topK = 4)
                VisaFactsRag.buildSystemPreamble(hits)
            } else null
        } catch (_: Throwable) { null }

        val allMessages = buildList {
            if (systemPreamble != null) {
                add(com.cactus.ChatMessage(systemPreamble, "system"))
            }
            addAll(messages.map { com.cactus.ChatMessage(it.content, it.role) })
        }

        val result = lm.generateCompletion(
            messages = allMessages,
            params = CactusCompletionParams(
                model = modelSlug,
                temperature = temperature,
                mode = InferenceMode.LOCAL
            )
        )
        if (result == null || !result.success) {
            throw IllegalStateException(result?.response ?: "Cactus completion failed")
        }
        return result.response.orEmpty()
    }

    override suspend fun isModelDownloaded(): Boolean {
        return try {
            val models = lm.getModels()
            models.any { it.slug == modelSlug && it.isDownloaded }
        } catch (e: Exception) {
            false
        }
    }

    override fun unload() {
        lm.unload()
        initialized = false
    }
}

actual fun aiChatClient(): AiChatClient = CactusAiChatClientIOS()
