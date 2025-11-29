package online.visabud.app.visabud_multiplatform.ai

import com.cactus.CactusCompletionParams
import com.cactus.CactusInitParams
import com.cactus.CactusLM
import com.cactus.InferenceMode
import com.cactus.models.ToolParameter
import com.cactus.models.createTool
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
        // Precompute & persist RAG embeddings for visa facts using model's embedding endpoint
        try {
            VisaFactsRag.ensurePersisted { text ->
                val emb = lm.generateEmbedding(text)
                if (emb == null || !emb.success) emptyList() else emb.embeddings
            }
        } catch (_: Throwable) {
            // Non-fatal if RAG preload fails
        }
        initialized = true
    }

    override suspend fun send(messages: List<ChatMsg>, temperature: Double?): String {
        val latestUser = messages.lastOrNull { it.role == "user" }?.content?.trim() ?: ""
        val lower = latestUser.lowercase()
        val isRoadmap = lower.startsWith("roadmap_json:")
        val isDocReview = lower.startsWith("doc_review:")
        val isInterview = lower.startsWith("interview_practice:")

        if (isRoadmap || isDocReview || isInterview) {
            val payload = latestUser.substringAfter(":").trim()
            val sysMsg: String
            val userMsg = payload.ifBlank { "{}" }
            val params: CactusCompletionParams
            val msgList: List<com.cactus.ChatMessage>

            if (isRoadmap) {
                val facts = try {
                    VisaFactsRag.retrieveFromRepo(payload.ifBlank { latestUser }, embedder = { q ->
                        val emb = lm.generateEmbedding(q)
                        if (emb == null || !emb.success) emptyList() else emb.embeddings
                    }, topK = 6)
                } catch (_: Throwable) { emptyList() }
                sysMsg = PromptTemplates.roadmapSystem(facts)
                msgList = listOf(
                    com.cactus.ChatMessage(sysMsg, "system"),
                    com.cactus.ChatMessage(userMsg, "user")
                )
                params = CactusCompletionParams(
                    model = modelSlug,
                    temperature = 0.2,
                    topP = 0.9,
                    maxTokens = 800,
                    mode = InferenceMode.LOCAL,
                    tools = emptyList()
                )
            } else if (isDocReview) {
                sysMsg = PromptTemplates.documentReviewSystem()
                msgList = listOf(
                    com.cactus.ChatMessage(sysMsg, "system"),
                    com.cactus.ChatMessage(userMsg, "user")
                )
                params = CactusCompletionParams(
                    model = modelSlug,
                    temperature = 0.2,
                    topP = 0.9,
                    maxTokens = 500,
                    mode = InferenceMode.LOCAL,
                    tools = emptyList()
                )
            } else {
                sysMsg = PromptTemplates.interviewPracticeSystem()
                msgList = listOf(
                    com.cactus.ChatMessage(sysMsg, "system"),
                    com.cactus.ChatMessage(userMsg, "user")
                )
                params = CactusCompletionParams(
                    model = modelSlug,
                    temperature = 0.2,
                    topP = 0.9,
                    maxTokens = 600,
                    mode = InferenceMode.LOCAL,
                    tools = emptyList()
                )
            }

            val result = lm.generateCompletion(messages = msgList, params = params)
            if (result == null || !result.success) {
                throw IllegalStateException(result?.response ?: "Cactus completion failed")
            }
            return result.response.orEmpty().trim()
        }

        // Default chat with RAG
        val userQuery = messages.lastOrNull { it.role == "user" }?.content
            ?: messages.lastOrNull()?.content

        var retrievedFacts: List<VisaFactsRag.RetrievedFact> = emptyList()
        val systemPreamble: String? = try {
            if (userQuery != null) {
                retrievedFacts = VisaFactsRag.retrieveFromRepo(userQuery, embedder = { q ->
                    val emb = lm.generateEmbedding(q)
                    if (emb == null || !emb.success) emptyList() else emb.embeddings
                }, topK = 6)
                VisaFactsRag.buildSystemPreamble(retrievedFacts)
            } else null
        } catch (_: Throwable) { null }

        val allMessages = buildList {
            if (systemPreamble != null) {
                add(com.cactus.ChatMessage(systemPreamble, "system"))
            }
            addAll(messages.map { com.cactus.ChatMessage(it.content, it.role) })
        }

        val tools = listOf(
            createTool(
                name = "produce_roadmap",
                description = "Produce a JSON roadmap for achieving a specific visa or immigration outcome.",
                parameters = mapOf(
                    "origin_country" to ToolParameter(type = "string", description = "User's current or passport country", required = false),
                    "target_country" to ToolParameter(type = "string", description = "Destination country", required = true),
                    "purpose" to ToolParameter(type = "string", description = "Purpose e.g., student, work, visit", required = true),
                    "steps" to ToolParameter(type = "array", description = "Optional custom steps array if applicable", required = false)
                )
            )
        )

        val result = lm.generateCompletion(
            messages = allMessages,
            params = CactusCompletionParams(
                model = modelSlug,
                temperature = temperature,
                mode = InferenceMode.LOCAL,
                tools = tools
            )
        )
        if (result == null || !result.success) {
            throw IllegalStateException(result?.response ?: "Cactus completion failed")
        }
        val base = result.response.orEmpty()
        val sources = VisaFactsRag.buildSourcesBlock(retrievedFacts)
        val toolJson = result.toolCalls?.joinToString("\n") { tc ->
            "Tool: ${tc.name}\nArgs: ${tc.arguments}"
        }.orEmpty()
        val withSources = if (sources.isBlank()) base else base + "\n\nSources:\n" + sources
        return if (toolJson.isBlank()) withSources else withSources + "\n\nRoadmap (JSON):\n" + toolJson
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
