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
    private fun iosSafeParseFlatJson(json: String): Map<String, String> {
        val trimmed = json.trim().removePrefix("```").removeSuffix("```").trim()
        if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) return emptyMap()
        val body = trimmed.substring(1, trimmed.length - 1)
        if (body.isBlank()) return emptyMap()
        val parts = mutableListOf<String>()
        val sb = StringBuilder()
        var inQuotes = false
        for (c in body) {
            if (c == '"') { inQuotes = !inQuotes; sb.append(c) }
            else if (c == ',' && !inQuotes) { parts.add(sb.toString()); sb.clear() }
            else sb.append(c)
        }
        if (sb.isNotEmpty()) parts.add(sb.toString())
        val map = LinkedHashMap<String, String>()
        for (p in parts) {
            val kv = p.split(":", limit = 2)
            if (kv.size == 2) {
                val key = kv[0].trim().trim('"')
                var value = kv[1].trim().trim(',').trim()
                if (value.startsWith('"') && value.endsWith('"') && value.length >= 2) {
                    value = value.substring(1, value.length - 1)
                }
                map[key] = value
            }
        }
        return map
    }
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
                val facts = try {
                    VisaFactsRag.retrieveFromRepo(payload.ifBlank { latestUser }, embedder = { q ->
                        val emb = lm.generateEmbedding(q)
                        if (emb == null || !emb.success) emptyList() else emb.embeddings
                    }, topK = 6)
                } catch (_: Throwable) { emptyList() }
                sysMsg = PromptTemplates.documentReviewSystem() + "\n" + (VisaFactsRag.buildSystemPreamble(facts) ?: "")
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
            ),
            createTool(
                name = "review_document",
                description = "Review a user's uploaded document for a target visa and return structured JSON feedback.",
                parameters = mapOf(
                    "targetVisa" to ToolParameter(type = "string", description = "Target visa label (e.g., 'UK Visitor')", required = true),
                    "documentJson" to ToolParameter(type = "string", description = "Flat JSON of parsed fields {\"field\":\"value\"}", required = true),
                    "imagePath" to ToolParameter(type = "string", description = "Optional file path or URI of the document image", required = false)
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

        // Execute tool calls agentically (single-step)
        val toolOutputs = StringBuilder()
        result.toolCalls?.forEach { tc ->
            when (tc.name) {
                "produce_roadmap" -> {
                    toolOutputs.append("\n\nRoadmap (JSON):\n")
                    toolOutputs.append(tc.arguments.toString())
                }
                "review_document" -> {
                    try {
                        val targetVisaArg = tc.arguments["targetVisa"] ?: ""
                        val docJson = tc.arguments["documentJson"] ?: "{}"
                        val parsed = iosSafeParseFlatJson(docJson)
                        val review = online.visabud.app.visabud_multiplatform.doc.documentPipeline().reviewDocument(parsed, targetVisaArg)
                        toolOutputs.append("\n\nDocument Review (JSON):\n")
                        toolOutputs.append(review)
                    } catch (e: Throwable) {
                        toolOutputs.append("\n\n[review_document failed: ${e.message}]")
                    }
                }
            }
        }
        val withSources = if (sources.isBlank()) base else base + "\n\nSources:\n" + sources
        return withSources + toolOutputs.toString()
    }

    override suspend fun isModelDownloaded(): Boolean {
        return try {
            val models = lm.getModels()
            models.any { it.slug == modelSlug && it.isDownloaded }
        } catch (e: Exception) {
            false
        }
    }

    override suspend fun sendStreaming(
        messages: List<ChatMsg>,
        temperature: Double?,
        onToken: (String) -> Unit
    ): String {
        val latestUser = messages.lastOrNull { it.role == "user" }?.content?.trim() ?: ""
        val lower = latestUser.lowercase()
        val isStructured = lower.startsWith("roadmap_json:") || lower.startsWith("doc_review:") || lower.startsWith("interview_practice:")
        if (isStructured) {
            val full = send(messages, temperature)
            onToken(full)
            return full
        }
        var retrievedFacts: List<VisaFactsRag.RetrievedFact> = emptyList()
        val userQuery = latestUser.ifBlank { messages.lastOrNull()?.content ?: "" }
        val systemPreamble: String? = try {
            retrievedFacts = VisaFactsRag.retrieveFromRepo(userQuery, embedder = { q ->
                val emb = lm.generateEmbedding(q)
                if (emb == null || !emb.success) emptyList() else emb.embeddings
            }, topK = 6)
            VisaFactsRag.buildSystemPreamble(retrievedFacts)
        } catch (_: Throwable) { null }
        val allMessages = buildList {
            if (systemPreamble != null) add(com.cactus.ChatMessage(systemPreamble, "system"))
            addAll(messages.map { com.cactus.ChatMessage(it.content, it.role) })
        }
        val sb = StringBuilder()
        val result = lm.generateCompletion(
            messages = allMessages,
            params = CactusCompletionParams(
                model = modelSlug,
                temperature = temperature,
                mode = InferenceMode.LOCAL,
                tools = listOf(
                    createTool(
                        name = "produce_roadmap",
                        description = "Produce a JSON roadmap for achieving a specific visa or immigration outcome.",
                        parameters = mapOf(
                            "origin_country" to com.cactus.models.ToolParameter(type = "string", description = "User's current or passport country", required = false),
                            "target_country" to com.cactus.models.ToolParameter(type = "string", description = "Destination country", required = true),
                            "purpose" to com.cactus.models.ToolParameter(type = "string", description = "Purpose e.g., student, work, visit", required = true)
                        )
                    ),
                    createTool(
                        name = "review_document",
                        description = "Review a user's uploaded document for a target visa and return structured JSON feedback.",
                        parameters = mapOf(
                            "targetVisa" to com.cactus.models.ToolParameter(type = "string", description = "Target visa label (e.g., 'UK Visitor')", required = true),
                            "documentJson" to com.cactus.models.ToolParameter(type = "string", description = "Flat JSON of parsed fields {\"field\":\"value\"}", required = true)
                        )
                    )
                )
            ),
            onToken = { token, _ ->
                sb.append(token)
                onToken(token)
            }
        )
        val base = sb.toString().ifBlank { result?.response.orEmpty() }
        val sources = VisaFactsRag.buildSourcesBlock(retrievedFacts)
        val tail = StringBuilder()
        if (sources.isNotBlank()) tail.append("\n\nSources:\n").append(sources)
        result?.toolCalls?.forEach { tc ->
            when (tc.name) {
                "produce_roadmap" -> tail.append("\n\nRoadmap (JSON):\n").append(tc.arguments.toString())
                "review_document" -> {
                    try {
                        val targetVisaArg = tc.arguments["targetVisa"] ?: ""
                        val docJson = tc.arguments["documentJson"] ?: "{}"
                        val parsed = iosSafeParseFlatJson(docJson)
                        val review = online.visabud.app.visabud_multiplatform.doc.documentPipeline().reviewDocument(parsed, targetVisaArg)
                        tail.append("\n\nDocument Review (JSON):\n").append(review)
                    } catch (e: Throwable) {
                        tail.append("\n\n[review_document failed: ").append(e.message).append("]")
                    }
                }
            }
        }
        val finalText = base + tail.toString()
        if (tail.isNotEmpty()) onToken(tail.toString())
        return finalText
    }

    override fun unload() {
        lm.unload()
        initialized = false
    }
}

actual fun aiChatClient(): AiChatClient = CactusAiChatClientIOS()
