package online.visabud.app.visabud_multiplatform.ai

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import online.visabud.app.visabud_multiplatform.data.ChatMessageEntity
import online.visabud.app.visabud_multiplatform.data.DataModule
import online.visabud.app.visabud_multiplatform.data.UserProfile

/**
 * Q&A / Chat Module (LLM + memory + tool-calling orchestration)
 *
 * Front-layer agent that:
 * - Accepts natural language queries
 * - Updates/reads user memory (ProfileBuilderTool + UserProfileMemory)
 * - Determines intent and missing info
 * - Calls appropriate local tools (Checklist, Roadmap, Costs, Embassy Locator, Visa Comparison, VisaFactsRag)
 * - Synthesizes a safe answer with citations and uncertainty handling
 *
 * Privacy: Entirely local. No network calls here.
 */
object ChatAgent {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = false }

    // --------- Public DTOs ---------
    @Serializable
    data class AgentReply(
        val replyText: String,
        val prompt: String? = null,          // If something is missing, ask the user this
        val toolUsed: String? = null,        // checklist|roadmap|cost|embassy|compare|facts|chat
        val jsonPayload: String? = null,     // tool's JSON result when applicable
        val citations: List<String> = emptyList(),
        val warnings: List<String> = emptyList()
    ) {
        fun toJson(): String = json.encodeToString(this)
    }

    data class Options(
        val threadId: String = "default",
        val embedder: (suspend (String) -> List<Double>)? = null,
        val llmFn: (suspend (system: String, user: String) -> String)? = null,
    )

    // --------- Entry point ---------
    suspend fun handleUserMessage(text: String, options: Options = Options()): AgentReply {
        val thread = options.threadId
        val now = now()
        val userMsg = ChatMessageEntity(id = randomId(), threadId = thread, role = "user", content = text, timestamp = now)
        try { DataModule.chats.addMessage(userMsg) } catch (_: Throwable) {}

        // Friendly small-talk: greet naturally and skip prompts/tools
        if (isGreeting(text)) {
            val greeting = friendlyGreeting()
            val assistantMsg = ChatMessageEntity(id = randomId(), threadId = thread, role = "assistant", content = greeting, timestamp = now + 1)
            try { DataModule.chats.addMessage(assistantMsg) } catch (_: Throwable) {}
            return AgentReply(replyText = greeting, toolUsed = "chat")
        }

        // 1) Auto-fill memory from chat (always run first to update profile locally)
        val history = try { DataModule.chats.listMessages(thread) } catch (_: Throwable) { listOf(userMsg) }
        val autoInitial = ProfileBuilderTool.autoFillFromChat(history, destination = null, neededFor = null)
        var profile = autoInitial.profile

        // 2) Detect intent and extract context for targeted profile questions
        val intent = detectIntent(text)
        val destinationHint = extractDestination(text)
        val neededFor = when (intent.kind) {
            IntentKind.CHECKLIST -> "checklist"
            IntentKind.ROADMAP -> "roadmap"
            IntentKind.COST -> "cost estimate"
            IntentKind.EMBASSY -> "embassy locator"
            IntentKind.COMPARE -> "comparison"
            IntentKind.VISA_TYPE -> "visa type suggestion"
            IntentKind.ELIGIBILITY -> "eligibility check"
            IntentKind.FACTS -> "visa facts"
            IntentKind.GENERIC -> null
        }
        // Run the profile builder again with contextual hints to surface any missing info question early
        val autoContext = ProfileBuilderTool.autoFillFromChat(history, destination = destinationHint, neededFor = neededFor)
        profile = autoContext.profile
        // If a targeted prompt is needed, ask it before running any tool
        if (!autoContext.prompt.isNullOrBlank()) {
            val p = softenPrompt(autoContext.prompt!!)
            val assistant = ChatMessageEntity(id = randomId(), threadId = thread, role = "assistant", content = p, timestamp = now + 1)
            try { DataModule.chats.addMessage(assistant) } catch (_: Throwable) {}
            return AgentReply(replyText = p, prompt = p, toolUsed = null, warnings = genericWarnings())
        }

        // 3) Route to tool, enforcing missing info prompts first in each tool as well (belt-and-braces)
        val reply = when (intent.kind) {
            IntentKind.CHECKLIST -> handleChecklist(profile, text)
            IntentKind.ROADMAP -> handleRoadmap(profile, text, options)
            IntentKind.COST -> handleCost(profile, text)
            IntentKind.EMBASSY -> handleEmbassy(profile, text)
            IntentKind.COMPARE -> handleCompare(profile, text)
            IntentKind.VISA_TYPE -> handleVisaType(profile, text)
            IntentKind.ELIGIBILITY -> handleEligibility(profile, text)
            IntentKind.FACTS -> handleFacts(profile, text)
            IntentKind.GENERIC -> handleGeneric(profile, text, options)
        }

        // Persist assistant message
        val assistant = ChatMessageEntity(id = randomId(), threadId = thread, role = "assistant", content = reply.replyText, timestamp = now + 1)
        try { DataModule.chats.addMessage(assistant) } catch (_: Throwable) {}
        return reply
    }

    // --------- Handlers ---------
    private fun extractDestination(text: String): String? {
        // naive extraction: look for common country codes/names present in VisaFactsRag dataset
        val candidates = listOf("United States","US","USA","United Kingdom","UK","Canada","CA","Australia","AU","Germany","DE","France","FR","Japan","JP","India","IN","United Arab Emirates","UAE","AE","South Africa","ZA")
        val low = text.lowercase()
        return candidates.firstOrNull { low.contains(it.lowercase()) }
    }

    private fun extractVisaType(text: String): String? {
        val low = text.lowercase()
        return when {
            low.contains("tour") || low.contains("visit") -> "tourist"
            low.contains("study") || low.contains("student") -> "study"
            low.contains("work") || low.contains("job") -> "work"
            low.contains("immig") || low.contains("residen") || low.contains("green card") -> "immigration"
            else -> null
        }
    }

    private suspend fun handleChecklist(profile: UserProfile, text: String): AgentReply {
        val destination = extractDestination(text)
        val visaType = extractVisaType(text)
        val prompt = VisaChecklistTool.buildMissingInfoPrompt(profile, destination, visaType)
        if (prompt != null) {
            return AgentReply(replyText = prompt, prompt = prompt, toolUsed = "checklist", warnings = genericWarnings())
        }
        val ck = VisaChecklistTool.generate(profile, destination, visaType)
        val human = VisaChecklistTool.buildHumanReadable(ck) + appendDisclaimer()
        return AgentReply(replyText = human, toolUsed = "checklist", jsonPayload = VisaChecklistTool.toJson(ck), citations = listOfNotNull(ck.officialLink).filter { it.isNotBlank() }, warnings = genericWarnings())
    }

    private suspend fun handleRoadmap(profile: UserProfile, text: String, options: Options): AgentReply {
        val destination = extractDestination(text)
        val goal = extractVisaType(text)
        val prompt = RoadmapGenerator.buildMissingInfoPrompt(profile, destination, goal)
        if (prompt != null) {
            return AgentReply(replyText = prompt, prompt = prompt, toolUsed = "roadmap", warnings = genericWarnings())
        }
        val paths = RoadmapGenerator.generate(
            profile = profile,
            destination = destination!!,
            goal = goal!!,
            embedder = options.embedder,
            llmFn = options.llmFn,
            options = RoadmapGenerator.GenerateOptions(useRepoRetrieval = true, topKFacts = 4)
        )
        val human = RoadmapGenerator.buildHumanReadable(paths) + appendDisclaimer()
        // Citations are embedded per path; flatten for reply
        val cites = paths.flatMap { it.citations }.distinct()
        return AgentReply(replyText = human, toolUsed = "roadmap", jsonPayload = Json.encodeToString(paths), citations = cites, warnings = genericWarnings())
    }

    private suspend fun handleCost(profile: UserProfile, text: String): AgentReply {
        val destination = extractDestination(text)
        val goal = extractVisaType(text)
        val duration = extractDurationMonths(text)
        val prompt = CostEstimator.buildMissingInfoPrompt(profile, destination, goal, duration)
        if (prompt != null) return AgentReply(replyText = prompt, prompt = prompt, toolUsed = "cost", warnings = genericWarnings())

        // Try database-backed fees calculation when user mentions a specific visa type ID (e.g., AUS-186, H-1B, UK-Student)
        val visaTypeId = extractVisaTypeId(text)
        val dbEstimate = try {
            if (!visaTypeId.isNullOrBlank()) CostEstimator.estimateUsingFeesDb(profile, destination, visaTypeId) else null
        } catch (_: Throwable) { null }
        if (dbEstimate != null) {
            val humanDb = CostEstimator.buildHumanReadable(dbEstimate) + appendDisclaimer()
            return AgentReply(replyText = humanDb, toolUsed = "cost", jsonPayload = CostEstimator.toJson(dbEstimate), citations = dbEstimate.citations, warnings = genericWarnings())
        }

        // Fallback to heuristic estimator
        val est = CostEstimator.estimate(profile, destination, goal, duration)
        val human = CostEstimator.buildHumanReadable(est) + appendDisclaimer()
        return AgentReply(replyText = human, toolUsed = "cost", jsonPayload = CostEstimator.toJson(est), citations = est.citations, warnings = genericWarnings())
    }

    private fun extractVisaTypeId(text: String): String? {
        val low = text.lowercase()
        // Common patterns: AUS-186, H-1B, F-1, UK-Student, 186 visa
        Regex("[A-Z]{2,3}-\\d{2,3}").find(text)?.let { return it.value }
        Regex("[A-Z]-\\dB").find(text)?.let { return it.value.uppercase() } // H-1B like
        Regex("\\bH-?1B\\b", RegexOption.IGNORE_CASE).find(text)?.let { return "H-1B" }
        // UK Student or similar
        if (low.contains("skilled worker")) return "UK-SW"
        if (low.contains("student")) return "UK-Student"
        // numeric id with country e.g., "186 visa in Australia"
        val num = Regex("\\b(\\d{3})\\b").find(text)?.groupValues?.getOrNull(1)
        if (num != null && (low.contains("australia") || low.contains("au "))) return "AUS-$num"
        return null
    }

    private suspend fun handleEmbassy(profile: UserProfile, text: String): AgentReply {
        val destination = extractDestination(text)
        val city = extractCity(text)
        val prompt = EmbassyLocator.buildMissingInfoPrompt(destination, city, profile)
        if (prompt != null) return AgentReply(replyText = prompt, prompt = prompt, toolUsed = "embassy", warnings = genericWarnings())
        val res = EmbassyLocator.queryNearest(destination, city, profile)
        val human = EmbassyLocator.buildHumanReadable(res) + appendDisclaimer()
        return AgentReply(replyText = human, toolUsed = "embassy", warnings = genericWarnings())
    }

    private suspend fun handleCompare(profile: UserProfile, text: String): AgentReply {
        val visaType = extractVisaType(text) ?: "tourist"
        val destinations = extractComparisonTargets(text)
        val prompt = VisaComparisonTool.buildMissingInfoPrompt(profile, destinations, visaType)
        if (prompt != null) return AgentReply(replyText = prompt, prompt = prompt, toolUsed = "compare", warnings = genericWarnings())
        val result = VisaComparisonTool.compare(profile, destinations, visaType)
        val human = VisaComparisonTool.buildHumanReadable(result) + appendDisclaimer()
        return AgentReply(replyText = human, toolUsed = "compare", jsonPayload = VisaComparisonTool.toJson(result), citations = result.rows.flatMap { it.citations }.distinct(), warnings = (result.warnings + genericWarnings()).distinct())
    }

    private suspend fun handleFacts(profile: UserProfile, text: String): AgentReply {
        // Interpret as country facts/visa requirements
        val destination = extractDestination(text)
        val nat = profile.nationality
        val query = VisaFactsRag.queryByDestinationNationality(destination, nat)
        if (query.prompt != null) {
            return AgentReply(replyText = query.prompt, prompt = query.prompt, toolUsed = "facts", warnings = genericWarnings())
        }
        val entry = query.country
        if (entry == null) {
            val msg = "I couldn't find local facts for that country. Please check the official immigration site for up-to-date rules."
            return AgentReply(replyText = msg + appendDisclaimer(), toolUsed = "facts", warnings = genericWarnings())
        }
        val summary = VisaFactsRag.buildCountrySummary(entry, nat)
        return AgentReply(replyText = summary + appendDisclaimer(), toolUsed = "facts", citations = listOf(entry.officialSite), warnings = genericWarnings())
    }

    private suspend fun handleGeneric(profile: UserProfile, text: String, options: Options): AgentReply {
        // Retrieve verified facts relevant to the question and ask LLM (if available) to synthesize.
        val embedder = options.embedder
        return if (embedder != null) {
            try {
                VisaFactsRag.ensurePersisted(embedder)
                val destination = extractDestination(text)
                val goal = extractVisaType(text)
                val filters = VisaFactsRag.buildFilters(profile, destination, goal)
                val facts = VisaFactsRag.retrieveHybrid(text, embedder, filters, topK = 6)
                val sys = VisaFactsRag.buildSystemPreamble(facts)
                val user = buildString {
                    appendLine("User question: ${text}")
                    appendLine("Profile (for personalization): ${UserProfileMemory.buildProfileJson(profile)}")
                }
                val llm = options.llmFn
                val body: String = if (llm != null) {
                    val out = llm.invoke(sys, user)
                    out.ifBlank { heuristicGenericSynthesis(text, facts) }
                } else {
                    heuristicGenericSynthesis(text, facts)
                }
                val sources = VisaFactsRag.buildSourcesBlock(facts)
                val reply = buildString {
                    appendLine(body)
                    if (sources.isNotBlank()) {
                        appendLine()
                        appendLine("Sources:")
                        appendLine(sources)
                    }
                    append(appendDisclaimer())
                }
                AgentReply(replyText = reply, toolUsed = "chat", citations = facts.map { it.site }.distinct(), warnings = genericWarnings())
            } catch (_: Throwable) {
                AgentReply(replyText = fallbackNoData(), toolUsed = "chat", warnings = genericWarnings())
            }
        } else {
            // No embedder available; minimal safe answer
            AgentReply(replyText = fallbackNoData(), toolUsed = "chat", warnings = genericWarnings())
        }
    }

    // ----- Eligibility handler -----
    private suspend fun handleEligibility(profile: UserProfile, text: String): AgentReply {
        val destination = extractDestination(text)
        val visaType = extractVisaType(text)
        val prompt = EligibilityTool.buildMissingInfoPrompt(profile, destination, visaType)
        if (prompt != null) return AgentReply(replyText = prompt, prompt = prompt, toolUsed = "eligibility", warnings = genericWarnings())
        val a = EligibilityTool.assess(profile, destination, visaType)
        val human = EligibilityTool.buildHumanReadable(a) + appendDisclaimer()
        val cites = if (a.citations.isNotEmpty()) a.citations else listOfNotNull(a.officialLink)
        return AgentReply(replyText = human, toolUsed = "eligibility", jsonPayload = EligibilityTool.toJson(a), citations = cites, warnings = genericWarnings())
    }

    // --------- Intent detection ---------
    private enum class IntentKind { CHECKLIST, ROADMAP, COST, EMBASSY, COMPARE, VISA_TYPE, ELIGIBILITY, FACTS, GENERIC }
    private data class Intent(val kind: IntentKind)

    private fun detectIntent(text: String): Intent {
        val low = text.lowercase()
        return when {
            listOf("checklist", "list documents", "what do i need", "requirements").any { low.contains(it) } -> Intent(IntentKind.CHECKLIST)
            listOf("roadmap", "path", "how can i get", "options", "plan").any { low.contains(it) } -> Intent(IntentKind.ROADMAP)
            listOf("cost", "fee", "fees", "estimate", "how much").any { low.contains(it) } -> Intent(IntentKind.COST)
            listOf("embassy", "consulate", "where can i apply", "nearest", "closest").any { low.contains(it) } -> Intent(IntentKind.EMBASSY)
            listOf("which visa", "what visa", "visa type", "category", "conference").any { low.contains(it) } -> Intent(IntentKind.VISA_TYPE)
            listOf("compare", "easier", "vs ", "versus", "schengen").any { low.contains(it) } -> Intent(IntentKind.COMPARE)
            listOf("eligible", "eligibility", "qualify", "qualified", "meet requirements", "am i eligible", "do i qualify").any { low.contains(it) } -> Intent(IntentKind.ELIGIBILITY)
            listOf("visa requirement", "need a visa", "do i need a visa", "visa-free", "requirements for").any { low.contains(it) } -> Intent(IntentKind.FACTS)
            else -> Intent(IntentKind.GENERIC)
        }
    }

    // --------- Utilities ---------
    private fun extractDurationMonths(text: String): Int? {
        // detect patterns like "6 months", "for 12 months"
        val m = Regex("(\\d{1,2})\\s*(?:month|months|mo)", RegexOption.IGNORE_CASE).find(text)
        return m?.groupValues?.getOrNull(1)?.toIntOrNull()
    }

    private fun extractCity(text: String): String? {
        // naive: pick known cities from EmbassyLocator gazetteer keys
        val keys = listOf("London","Manchester","New York","Washington","Washington DC","Toronto","Mumbai","Delhi","Tokyo","Berlin","Paris","Dubai","Johannesburg")
        val low = text.lowercase()
        return keys.firstOrNull { low.contains(it.lowercase()) }
    }

    private fun extractComparisonTargets(text: String): String {
        // Return raw tokens like "UK vs US" or list "Germany/France"
        // We'll let VisaComparisonTool handle parsing/expansion.
        return text
    }

    private fun heuristicGenericSynthesis(query: String, facts: List<VisaFactsRag.RetrievedFact>): String {
        if (facts.isEmpty()) return "I couldn't retrieve verified local facts for your question. Please share your destination and nationality, or check the official immigration site of your destination."
        val bullets = facts.joinToString("\n") { "- [${it.country}] ${it.fact}" }
        return "Here are verified points relevant to your question:\n$bullets\n\nIf you share your nationality and destination, I can tailor the guidance."
    }

    private fun fallbackNoData(): String =
        "I couldn’t retrieve enough local information to answer confidently. If you share your destination and nationality, I can check visa rules. Otherwise please refer to the official immigration website of your destination for the latest guidance." + appendDisclaimer()

    private fun appendDisclaimer(): String = "\n\nNote: I use local facts and your on-device profile. Policies change—always verify on official sites."

    private fun genericWarnings(): List<String> = listOf("When information is missing, I will ask you before proceeding.")

    private fun now(): Long = 0L
    private fun randomId(): String = "msg_" + kotlin.random.Random.nextLong().toString(16)

    // ---- Conversation tone helpers ----
    private fun isGreeting(text: String): Boolean {
        val t = text.trim().lowercase()
        if (t.isEmpty()) return false
        val greetings = listOf("hi", "hello", "hey", "good morning", "good afternoon", "good evening")
        // Only treat as greeting if the message is short and matches a greeting phrase
        return greetings.any { g -> t == g || t.startsWith("$g!") || t == "$g." }
    }

    private fun friendlyGreeting(): String =
        "Hi! I’m VisaBud. I can help with visa options, requirements, costs, and embassies. What would you like to do today?"

    private fun softenPrompt(p: String): String {
        // Lightly rephrase robotic prompts into a friendly tone.
        var s = p
        s = s.replace("To ", "To help you better, ")
        s = s.replace("please share", "could you share")
        return s
    }

    // ----- Visa Type handler and helpers -----
    private suspend fun handleVisaType(profile: UserProfile, text: String): AgentReply {
        val destination = extractDestination(text)
        val purpose = extractPurpose(text) ?: extractVisaType(text)
        val durationDays = extractDurationDays(text)
        val paid = detectPaidWork(text)
        val prompt = VisaTypeTool.buildMissingInfoPrompt(profile, destination, purpose, durationDays, paid)
        if (prompt != null) return AgentReply(replyText = prompt, prompt = prompt, toolUsed = "visa_type", warnings = genericWarnings())
        val res = VisaTypeTool.recommend(profile, destination, purpose, durationDays, paid)
        val human = VisaTypeTool.buildHumanReadable(res) + appendDisclaimer()
        val cites = listOfNotNull(res.officialLink).filter { it.isNotBlank() }
        return AgentReply(replyText = human, toolUsed = "visa_type", jsonPayload = VisaTypeTool.toJson(res), citations = cites, warnings = genericWarnings())
    }

    private fun extractPurpose(text: String): String? {
        val low = text.lowercase()
        return when {
            low.contains("conference") || low.contains("seminar") || low.contains("workshop") -> "conference"
            low.contains("business meeting") || low.contains("business trip") -> "business"
            else -> null
        }
    }

    private fun extractDurationDays(text: String): Int? {
        val low = text.lowercase()
        // e.g., "3 days", "10 day"
        Regex("(\\d{1,3})\\s*(day|days)").find(low)?.let { return it.groupValues[1].toIntOrNull() }
        // e.g., "1 week", "2 weeks"
        Regex("(\\d{1,2})\\s*(week|weeks)").find(low)?.let { return (it.groupValues[1].toIntOrNull() ?: 0) * 7 }
        // Fallback: if months mentioned, approximate 30 per month
        Regex("(\\d{1,2})\\s*(month|months)").find(low)?.let { return (it.groupValues[1].toIntOrNull() ?: 0) * 30 }
        return null
    }

    private fun detectPaidWork(text: String): Boolean? {
        val low = text.lowercase()
        return when {
            listOf("paid", "salary", "employment", "job offer", "work contract").any { low.contains(it) } -> true
            listOf("no pay", "unpaid", "volunteer", "no work").any { low.contains(it) } -> false
            else -> null
        }
    }
}
