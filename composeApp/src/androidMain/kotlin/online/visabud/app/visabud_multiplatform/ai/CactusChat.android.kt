package online.visabud.app.visabud_multiplatform.ai

import com.cactus.CactusCompletionParams
import com.cactus.CactusInitParams
import com.cactus.CactusLM
import com.cactus.InferenceMode
import com.cactus.models.ToolParameter
import com.cactus.models.createTool
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private class CactusAiChatClient : AiChatClient {
    private fun safeParseFlatJson(json: String): Map<String, String> {
        val trimmed = json.trim().removePrefix("```").removeSuffix("```").trim()
        if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) return emptyMap()
        val body = trimmed.substring(1, trimmed.length - 1)
        if (body.isBlank()) return emptyMap()
        val result = LinkedHashMap<String, String>()
        val parts = splitTopLevelCommas(body)
        for (p in parts) {
            val kv = p.split(":", limit = 2)
            if (kv.size == 2) {
                val key = kv[0].trim().trim('"')
                var value = kv[1].trim().trim(',').trim()
                if (value.startsWith('"') && value.endsWith('"') && value.length >= 2) {
                    value = value.substring(1, value.length - 1)
                }
                result[key] = value
            }
        }
        return result
    }

    private fun splitTopLevelCommas(s: String): List<String> {
        val parts = mutableListOf<String>()
        val sb = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '"') {
                inQuotes = !inQuotes
                sb.append(c)
            } else if (c == ',' && !inQuotes) {
                parts.add(sb.toString())
                sb.clear()
            } else {
                sb.append(c)
            }
            i++
        }
        if (sb.isNotEmpty()) parts.add(sb.toString())
        return parts
    }
    private val lm get() = CactusLmHolder.instance()
    private val initMutex = Mutex()
    private var initialized = false
    private val modelSlug = "local-qwen3-0.6"

    private suspend fun <T> withLM(block: suspend (CactusLM) -> T): T = CactusLmHolder.withLm { lm -> block(lm) }

    override suspend fun ensureReady(contextSize: Int): Unit = initMutex.withLock {
        if (initialized && CactusLmHolder.isLoaded()) return
        CactusLmHolder.ensureReady(contextSize = contextSize, model = modelSlug)
        // Precompute and persist RAG embeddings for visa facts using model's embedding endpoint
        try {
            VisaFactsRag.ensurePersisted { text ->
                val emb = withLM { it.generateEmbedding(text) }
                if (emb == null || !emb.success) emptyList() else emb.embeddings
            }
        } catch (_: Throwable) {
            // Do not fail chat if RAG preload fails
        }
        initialized = true
    }

    override suspend fun send(messages: List<ChatMsg>, temperature: Double?): String {
        val latestUser = messages.lastOrNull { it.role == "user" }?.content?.trim() ?: ""
        val lower = latestUser.lowercase()
        val isRoadmap = lower.startsWith("roadmap_json:")
        val isDocReview = lower.startsWith("doc_review:")
        val isInterview = lower.startsWith("interview_practice:")

        // Structured flows
        if (isRoadmap || isDocReview || isInterview) {
            val payload = latestUser.substringAfter(":").trim()
            val sysMsg: String
            val userMsg = payload.ifBlank { "{}" }
            val params: CactusCompletionParams
            val msgList: List<com.cactus.ChatMessage>

            if (isRoadmap) {
                // Retrieve facts using payload as query to bias by target country/purpose
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
                    stopSequences = listOf("<|im_end|>", "<end_of_turn>"),
                    mode = InferenceMode.LOCAL,
                    tools = emptyList()
                )
            } else if (isDocReview) {
                // Retrieve facts to ground document review
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
                    stopSequences = listOf("<|im_end|>", "<end_of_turn>"),
                    mode = InferenceMode.LOCAL,
                    tools = emptyList()
                )
            } else { // Interview practice
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

            val result = withLM { it.generateCompletion(messages = msgList, params = params) }
            if (result == null || !result.success) {
                throw IllegalStateException(result?.response ?: "Cactus completion failed")
            }
            // For structured flows, return JSON as-is (no sources or tool output appended)
            return result.response.orEmpty().trim()
        }

        // Default chat: RAG-assisted free-form
        // Retrieve top facts based on the latest user message (or combined user turns)
        val userQuery = messages.lastOrNull { it.role == "user" }?.content
            ?: messages.lastOrNull()?.content

        var retrievedFacts: List<VisaFactsRag.RetrievedFact> = emptyList()
        val systemPreamble: String? = try {
            if (userQuery != null) {
                retrievedFacts = VisaFactsRag.retrieveFromRepo(userQuery, embedder = { q ->
                    val emb = withLM { it.generateEmbedding(q) }
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

        // Define function-calling tools for an agentic experience
        val tools = listOf(
            // Roadmap generator
            createTool(
                name = "generate_roadmap",
                description = "Generate a visa roadmap given destination and goal (study/work/immigration/tourist)",
                parameters = mapOf(
                    "destination" to ToolParameter(type = "string", description = "Destination country", required = true),
                    "goal" to ToolParameter(type = "string", description = "Purpose e.g., study, work, immigration, tourist", required = true)
                )
            ),
            // Visa checklist
            createTool(
                name = "get_visa_checklist",
                description = "Build a personalized visa checklist",
                parameters = mapOf(
                    "destination" to ToolParameter(type = "string", description = "Destination country", required = true),
                    "visaTypeOrGoal" to ToolParameter(type = "string", description = "Visa type or purpose", required = true)
                )
            ),
            // Cost estimator
            createTool(
                name = "estimate_costs",
                description = "Estimate visa costs and related expenses",
                parameters = mapOf(
                    "destination" to ToolParameter(type = "string", description = "Destination country", required = true),
                    "goal" to ToolParameter(type = "string", description = "Purpose e.g., study, work, tourist, immigration", required = false),
                    "durationMonths" to ToolParameter(type = "number", description = "Expected duration in months (for study/work)", required = false),
                    "visaTypeId" to ToolParameter(type = "string", description = "Specific visa type ID if known (e.g., AUS-186)", required = false),
                    "travelers" to ToolParameter(type = "number", description = "Number of travelers", required = false)
                )
            ),
            // Embassy locator
            createTool(
                name = "locate_embassy",
                description = "Find nearest embassy/consulate for the destination",
                parameters = mapOf(
                    "destination" to ToolParameter(type = "string", description = "Destination country (representing country)", required = true),
                    "userCity" to ToolParameter(type = "string", description = "User's city or location name", required = false)
                )
            ),
            // Visa comparison
            createTool(
                name = "compare_visas",
                description = "Compare visa difficulty across countries or a region keyword (e.g., Schengen)",
                parameters = mapOf(
                    "destinations" to ToolParameter(type = "string", description = "Country list or keyword like 'UK vs US' or 'Schengen'", required = true),
                    "visaType" to ToolParameter(type = "string", description = "Purpose e.g., tourist, study, work, immigration", required = true)
                )
            ),
            // Visa type recommendation
            createTool(
                name = "recommend_visa_type",
                description = "Recommend visa category given destination and purpose",
                parameters = mapOf(
                    "destination" to ToolParameter(type = "string", description = "Destination country", required = true),
                    "purpose" to ToolParameter(type = "string", description = "Purpose (e.g., conference, business, tourist, study, work)", required = true),
                    "durationDays" to ToolParameter(type = "number", description = "Approximate duration in days", required = false),
                    "paidWork" to ToolParameter(type = "boolean", description = "Whether paid work is involved", required = false)
                )
            ),
            // Eligibility assessment
            createTool(
                name = "assess_eligibility",
                description = "Assess likely eligibility for a visa goal in a destination",
                parameters = mapOf(
                    "destination" to ToolParameter(type = "string", description = "Destination country", required = true),
                    "visaTypeOrGoal" to ToolParameter(type = "string", description = "Visa type or purpose", required = true)
                )
            ),
            // Profile auto-fill from chat
            createTool(
                name = "update_profile_from_chat",
                description = "Extract user info from recent chat and persist to local profile",
                parameters = mapOf(
                    "recentMessage" to ToolParameter(type = "string", description = "Latest user text to extract info from", required = true)
                )
            ),
            // Document review (kept)
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

        val result = withLM { it.generateCompletion(
            messages = allMessages,
            params = CactusCompletionParams(
                model = modelSlug,
                temperature = temperature,
                stopSequences = listOf("<|im_end|>", "<end_of_turn>"),
                mode = InferenceMode.LOCAL,
                tools = tools
            )
        ) }
        if (result == null || !result.success) {
            throw IllegalStateException(result?.response ?: "Cactus completion failed")
        }
        val base = result.response.orEmpty()
        val sources = VisaFactsRag.buildSourcesBlock(retrievedFacts)

        // Execute tool calls agentically (single-step)
        val toolOutputs = StringBuilder()
        result.toolCalls?.forEach { tc ->
            when (tc.name) {
                // New agent tools
                "generate_roadmap" -> {
                    try {
                        val destination = (tc.arguments["destination"] ?: "").trim()
                        val goal = (tc.arguments["goal"] ?: "").trim()
                        val profile = online.visabud.app.visabud_multiplatform.ai.UserProfileMemory.getOrCreate()
                        val paths = online.visabud.app.visabud_multiplatform.ai.RoadmapGenerator.generate(
                            profile = profile,
                            destination = destination,
                            goal = goal,
                            embedder = { q ->
                                val emb = withLM { it.generateEmbedding(q) }
                                if (emb == null || !emb.success) emptyList() else emb.embeddings
                            },
                            llmFn = null,
                            options = online.visabud.app.visabud_multiplatform.ai.RoadmapGenerator.GenerateOptions(useRepoRetrieval = true, topKFacts = 4)
                        )
                        val human = online.visabud.app.visabud_multiplatform.ai.RoadmapGenerator.buildHumanReadable(paths)
                        toolOutputs.append("\n\nRoadmap:\n").append(human)
                    } catch (e: Throwable) {
                        toolOutputs.append("\n\n[generate_roadmap failed: ${e.message}]")
                    }
                }
                "get_visa_checklist" -> {
                    try {
                        val destination = (tc.arguments["destination"] ?: "").trim()
                        val visaType = (tc.arguments["visaTypeOrGoal"] ?: "").trim()
                        val profile = online.visabud.app.visabud_multiplatform.ai.UserProfileMemory.getOrCreate()
                        val ck = online.visabud.app.visabud_multiplatform.ai.VisaChecklistTool.generate(profile, destination, visaType)
                        val human = online.visabud.app.visabud_multiplatform.ai.VisaChecklistTool.buildHumanReadable(ck)
                        toolOutputs.append("\n\nChecklist:\n").append(human)
                    } catch (e: Throwable) {
                        toolOutputs.append("\n\n[get_visa_checklist failed: ${e.message}]")
                    }
                }
                "estimate_costs" -> {
                    try {
                        val destination = (tc.arguments["destination"] ?: "").trim()
                        val goal = tc.arguments["goal"]?.trim()
                        val durationMonths = tc.arguments["durationMonths"]?.toString()?.toDoubleOrNull()?.toInt()
                            ?: tc.arguments["durationMonths"]?.let { (it as? Number)?.toInt() }
                        val visaTypeId = tc.arguments["visaTypeId"]?.trim()
                        val travelers = tc.arguments["travelers"]?.toString()?.toIntOrNull()
                            ?: (tc.arguments["travelers"] as? Number)?.toInt() ?: 1
                        val profile = online.visabud.app.visabud_multiplatform.ai.UserProfileMemory.getOrCreate()
                        val dbEst = if (!visaTypeId.isNullOrBlank())
                            online.visabud.app.visabud_multiplatform.ai.CostEstimator.estimateUsingFeesDb(
                                profile, destination, visaTypeId,
                                online.visabud.app.visabud_multiplatform.ai.CostEstimator.Options(travelers = travelers)
                            ) else null
                        val est = dbEst ?: online.visabud.app.visabud_multiplatform.ai.CostEstimator.estimate(
                            profile, destination, goal, durationMonths,
                            online.visabud.app.visabud_multiplatform.ai.CostEstimator.Options(travelers = travelers)
                        )
                        val human = online.visabud.app.visabud_multiplatform.ai.CostEstimator.buildHumanReadable(est)
                        toolOutputs.append("\n\nCost Estimate:\n").append(human)
                    } catch (e: Throwable) {
                        toolOutputs.append("\n\n[estimate_costs failed: ${e.message}]")
                    }
                }
                
                "locate_embassy" -> {
                    try {
                        val destination = (tc.arguments["destination"] ?: "").trim()
                        val city = tc.arguments["userCity"]?.trim()
                        val profile = online.visabud.app.visabud_multiplatform.ai.UserProfileMemory.getOrCreate()
                        val res = online.visabud.app.visabud_multiplatform.ai.EmbassyLocator.queryNearest(destination, city, profile)
                        val human = online.visabud.app.visabud_multiplatform.ai.EmbassyLocator.buildHumanReadable(res)
                        toolOutputs.append("\n\nNearest Embassy:\n").append(human)
                    } catch (e: Throwable) {
                        toolOutputs.append("\n\n[locate_embassy failed: ${e.message}]")
                    }
                }

                "compare_visas" -> {
                    try {
                        val destinations = (tc.arguments["destinations"] ?: "").trim()
                        val visaType = (tc.arguments["visaType"] ?: "").trim()
                        val profile = online.visabud.app.visabud_multiplatform.ai.UserProfileMemory.getOrCreate()
                        val res = online.visabud.app.visabud_multiplatform.ai.VisaComparisonTool.compare(profile, destinations, visaType)
                        val human = online.visabud.app.visabud_multiplatform.ai.VisaComparisonTool.buildHumanReadable(res)
                        toolOutputs.append("\n\nComparison:\n").append(human)
                    } catch (e: Throwable) {
                        toolOutputs.append("\n\n[compare_visas failed: ${e.message}]")
                    }
                }

                "recommend_visa_type" -> {
                    try {
                        val destination = (tc.arguments["destination"] ?: "").trim()
                        val purpose = (tc.arguments["purpose"] ?: "").trim()
                        val durationDays = tc.arguments["durationDays"]?.toString()?.toIntOrNull()
                            ?: (tc.arguments["durationDays"] as? Number)?.toInt()
                        val paidWork = tc.arguments["paidWork"]?.toString()?.lowercase()?.let { it == "true" || it == "1" }
                            ?: (tc.arguments["paidWork"] as? Boolean)
                        val profile = online.visabud.app.visabud_multiplatform.ai.UserProfileMemory.getOrCreate()
                        val rec = online.visabud.app.visabud_multiplatform.ai.VisaTypeTool.recommend(profile, destination, purpose, durationDays, paidWork)
                        val human = online.visabud.app.visabud_multiplatform.ai.VisaTypeTool.buildHumanReadable(rec)
                        toolOutputs.append("\n\nVisa Recommendation:\n").append(human)
                    } catch (e: Throwable) {
                        toolOutputs.append("\n\n[recommend_visa_type failed: ${e.message}]")
                    }
                }

                "assess_eligibility" -> {
                    try {
                        val destination = (tc.arguments["destination"] ?: "").trim()
                        val visaTypeOrGoal = (tc.arguments["visaTypeOrGoal"] ?: "").trim()
                        val profile = online.visabud.app.visabud_multiplatform.ai.UserProfileMemory.getOrCreate()
                        val a = online.visabud.app.visabud_multiplatform.ai.EligibilityTool.assess(profile, destination, visaTypeOrGoal)
                        val human = online.visabud.app.visabud_multiplatform.ai.EligibilityTool.buildHumanReadable(a)
                        toolOutputs.append("\n\nEligibility:\n").append(human)
                    } catch (e: Throwable) {
                        toolOutputs.append("\n\n[assess_eligibility failed: ${e.message}]")
                    }
                }

                "update_profile_from_chat" -> {
                    try {
                        val recent = (tc.arguments["recentMessage"] ?: "").trim()
                        val threadMsgs = listOf(online.visabud.app.visabud_multiplatform.data.ChatMessageEntity(
                            id = "tmp", threadId = "tool", role = "user", content = recent, timestamp = 0L
                        ))
                        val resultAuto = online.visabud.app.visabud_multiplatform.ai.ProfileBuilderTool.autoFillFromChat(threadMsgs)
                        val summary = online.visabud.app.visabud_multiplatform.ai.UserProfileMemory.buildSimpleSummary(resultAuto.profile)
                        toolOutputs.append("\n\nProfile updated from chat.\n").append(summary)
                    } catch (e: Throwable) {
                        toolOutputs.append("\n\n[update_profile_from_chat failed: ${e.message}]")
                    }
                }

                // Document review (existing)
                "review_document" -> {
                    try {
                        val targetVisaArg = tc.arguments["targetVisa"] ?: ""
                        val docJson = tc.arguments["documentJson"] ?: "{}"
                        val parsed = safeParseFlatJson(docJson)
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
            val models = withLM { it.getModels() }
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
        // If structured command prefixes are used, fall back to non-streaming for strict JSON
        val latestUser = messages.lastOrNull { it.role == "user" }?.content?.trim() ?: ""
        val lower = latestUser.lowercase()
        val isStructured = lower.startsWith("roadmap_json:") || lower.startsWith("doc_review:") || lower.startsWith("interview_practice:")
        if (isStructured) {
            val full = send(messages, temperature)
            onToken(full)
            return full
        }

        // Default chat with RAG grounding + tools, streamed tokens
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
                            "origin_country" to ToolParameter(type = "string", description = "User's current or passport country", required = false),
                            "target_country" to ToolParameter(type = "string", description = "Destination country", required = true),
                            "purpose" to ToolParameter(type = "string", description = "Purpose e.g., student, work, visit", required = true)
                        )
                    ),
                    createTool(
                        name = "review_document",
                        description = "Review a user's uploaded document for a target visa and return structured JSON feedback.",
                        parameters = mapOf(
                            "targetVisa" to ToolParameter(type = "string", description = "Target visa label (e.g., 'UK Visitor')", required = true),
                            "documentJson" to ToolParameter(type = "string", description = "Flat JSON of parsed fields {\"field\":\"value\"}", required = true)
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
        // Emit sources and any tool outputs (if any) after stream ends
        val tail = StringBuilder()
        if (sources.isNotBlank()) {
            tail.append("\n\nSources:\n").append(sources)
        }
        result?.toolCalls?.forEach { tc ->
            when (tc.name) {
                "produce_roadmap" -> {
                    tail.append("\n\nRoadmap (JSON):\n").append(tc.arguments.toString())
                }
                "review_document" -> {
                    try {
                        val targetVisaArg = tc.arguments["targetVisa"] ?: ""
                        val docJson = tc.arguments["documentJson"] ?: "{}"
                        val parsed = safeParseFlatJson(docJson)
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
        // Shared LM managed by CactusLmHolder; avoid unloading to prevent native races
        initialized = false
    }
}

actual fun aiChatClient(): AiChatClient = CactusAiChatClient()
