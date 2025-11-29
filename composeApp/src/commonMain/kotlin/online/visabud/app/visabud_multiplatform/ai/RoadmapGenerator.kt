package online.visabud.app.visabud_multiplatform.ai

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import online.visabud.app.visabud_multiplatform.data.DataModule
import online.visabud.app.visabud_multiplatform.data.Roadmap
import online.visabud.app.visabud_multiplatform.data.UserProfile

/**
 * Visa Roadmap Generator (agent tool)
 *
 * Purpose:
 * - Given user profile + destination + visa goal, produce 1–3 plausible paths with steps
 * - Uses local verified facts (VisaFactsRag) and an injected LLM for reasoning
 * - Falls back to heuristic templates when LLM is unavailable or returns invalid JSON
 * - Can optionally persist generated roadmaps locally via DataModule.roadmaps
 */
object RoadmapGenerator {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = false; isLenient = true }

    // -------- Public DTOs --------
    @Serializable
    data class RoadmapStepDTO(
        val title: String,
        val description: String,
        @SerialName("estimatedMonths") val estimatedMonths: Int = 0,
        val requiredDocs: List<String> = emptyList()
    )

    @Serializable
    data class RoadmapPathDTO(
        @SerialName("routeName") val routeName: String,
        val steps: List<RoadmapStepDTO>,
        val confidence: Int = 60,
        val citations: List<String> = emptyList()
    )

    data class GenerateOptions(
        val topKFacts: Int = 4,
        val useRepoRetrieval: Boolean = true,
        val clampConfidence: Boolean = true,
        val persist: Boolean = false,
        val roadmapTitle: String? = null,
        val roadmapId: String = "roadmap_${'$'}{System.currentTimeMillis()}"
    )

    // -------- Agent helpers --------
    fun buildMissingInfoPrompt(profile: UserProfile?, destination: String?, goal: String?): String? {
        val missing = mutableListOf<String>()
        if (destination.isNullOrBlank()) missing += "destination country"
        if (goal.isNullOrBlank()) missing += "visa goal (study, work, immigration, tourist)"
        val p = profile
        if (p == null) {
            missing += "basic profile (nationality, education/work years)"
        } else {
            if (p.nationality.isNullOrBlank()) missing += "nationality"
            if (p.education.isNullOrBlank() && p.workYears == null) missing += "education or work years"
        }
        if (missing.isEmpty()) return null
        return "To generate a useful roadmap, please share your ${missing.joinToString(", ")}."
    }

    /**
     * Generate visa roadmap paths.
     * @param embedder Optional embedder to retrieve facts from local repo (can return empty list).
     * @param llmFn Optional local LLM call: given system and user prompts, returns raw JSON string.
     */
    suspend fun generate(
        profile: UserProfile,
        destination: String,
        goal: String,
        embedder: (suspend (String) -> List<Double>)? = null,
        llmFn: (suspend (systemPrompt: String, userPrompt: String) -> String)? = null,
        options: GenerateOptions = GenerateOptions()
    ): List<RoadmapPathDTO> {
        // Retrieve verified facts (if embedder available)
        val facts: List<VisaFactsRag.RetrievedFact> = try {
            if (options.useRepoRetrieval && embedder != null) {
                VisaFactsRag.ensurePersisted(embedder)
                VisaFactsRag.retrieveFromRepo(
                    query = "$destination $goal visa steps requirements",
                    embedder = embedder,
                    topK = options.topKFacts
                )
            } else emptyList()
        } catch (_: Throwable) { emptyList() }

        // Try LLM path first if provided
        val llmPaths: List<RoadmapPathDTO>? = try {
            if (llmFn != null) {
                val sys = PromptTemplates.roadmapSystem(facts)
                val user = buildString {
                    appendLine("Context:")
                    appendLine("destination: $destination")
                    appendLine("goal: $goal")
                    appendLine("profileJson: ${UserProfileMemory.buildProfileJson(profile)}")
                }
                val raw = llmFn.invoke(sys, user)
                if (!raw.isNullOrBlank()) parsePaths(raw) else null
            } else null
        } catch (_: Throwable) { null }

        val paths = (llmPaths ?: heuristicPaths(profile, destination, goal, facts)).map { clamp(it, options) }

        if (options.persist) {
            // Persist a single Roadmap entity with the entire paths JSON for now
            val stepsJson = json.encodeToString(paths)
            val title = options.roadmapTitle ?: "$destination ${goal.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }} options"
            val entity = Roadmap(
                id = options.roadmapId,
                title = title,
                description = "Auto-generated roadmap for $destination ($goal)",
                stepsJson = stepsJson,
                createdAt = now(),
                updatedAt = now()
            )
            try { DataModule.roadmaps.upsert(entity) } catch (_: Throwable) {}
        }

        return paths
    }

    fun buildHumanReadable(paths: List<RoadmapPathDTO>): String {
        if (paths.isEmpty()) return "No roadmap could be generated with the current info."
        val sb = StringBuilder()
        paths.forEachIndexed { idx, p ->
            sb.appendLine("${idx + 1}. ${p.routeName} — confidence ${p.confidence}%")
            p.steps.forEachIndexed { i, s ->
                sb.appendLine("   ${i + 1}) ${s.title} (${s.estimatedMonths} mo)")
                sb.appendLine("      ${s.description}")
                if (s.requiredDocs.isNotEmpty()) sb.appendLine("      Docs: ${s.requiredDocs.joinToString()}")
            }
            if (p.citations.isNotEmpty()) {
                sb.appendLine("   Sources:")
                p.citations.distinct().forEach { sb.appendLine("   - $it") }
            }
            sb.appendLine()
        }
        return sb.toString().trim()
    }

    // -------- Internal helpers --------
    private fun parsePaths(raw: String): List<RoadmapPathDTO>? {
        // Accepts either a JSON array or a stringified array
        val text = raw.trim()
        return try {
            json.decodeFromString(text)
        } catch (_: Throwable) {
            // Some models wrap JSON in markdown; attempt to strip code fences
            val cleaned = text.removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
            try { json.decodeFromString(cleaned) } catch (_: Throwable) { null }
        }
    }

    private fun clamp(p: RoadmapPathDTO, options: GenerateOptions): RoadmapPathDTO {
        if (!options.clampConfidence) return p
        val c = p.confidence.coerceIn(1, 100)
        val steps = p.steps.map { s ->
            s.copy(
                estimatedMonths = s.estimatedMonths.coerceIn(0, 120)
            )
        }
        return p.copy(confidence = c, steps = steps)
    }

    private fun heuristicPaths(
        profile: UserProfile,
        destination: String,
        goal: String,
        facts: List<VisaFactsRag.RetrievedFact>
    ): List<RoadmapPathDTO> {
        val cites = facts.map { it.site }.distinct()
        val nat = profile.nationality ?: "your nationality"
        val edu = profile.education ?: "your education"
        val workY = profile.workYears ?: 0

        fun commonPrepDocs(): List<String> = buildList {
            add("Valid passport")
            add("Proof of funds")
            add("Photos per spec")
            add("Application form")
        }

        fun study(): RoadmapPathDTO = RoadmapPathDTO(
            routeName = "$destination Study → Graduate → Work options",
            confidence = 70,
            citations = cites,
            steps = listOf(
                RoadmapStepDTO("Research programs & eligibility", "Match programs and language requirements. Confirm visa category for $destination.", 1, listOf("Transcripts", "Language test (if required)")),
                RoadmapStepDTO("Secure admission & financials", "Obtain an offer (CAS/I-20 equivalent) and arrange funds/blocked account if needed.", 2, listOf("Offer letter", "Financial proof")),
                RoadmapStepDTO("Apply for student visa", "Complete online form, pay fees, schedule biometrics/interview as required.", 1, commonPrepDocs()),
                RoadmapStepDTO("Travel & enroll", "Enter $destination and complete enrollment/biometrics within deadline.", 1, listOf("Enrollment letter")),
                RoadmapStepDTO("Post-study pathway", "Explore graduate job search/work permit options leading to residency if eligible.", 6, listOf("Degree certificate", "Employment contract (if any)"))
            )
        )

        fun work(): RoadmapPathDTO = RoadmapPathDTO(
            routeName = "$destination Skilled Work (sponsored) → Temporary Residence → PR",
            confidence = 60,
            citations = cites,
            steps = listOf(
                RoadmapStepDTO("Assess skills & occupation", "Check if your role is eligible. For $nat with $workY years experience and $edu, verify any licensing.", 1, listOf("CV/Resume", "Credentials evaluation")),
                RoadmapStepDTO("Secure employer sponsorship", "Search and obtain a job offer; employer completes sponsorship/LC/LMIA equivalent.", 3, listOf("Job offer", "Employer documents")),
                RoadmapStepDTO("Apply for work visa", "Submit application, biometrics, and pay fees. Maintain ties and compliance.", 1, commonPrepDocs()),
                RoadmapStepDTO("Arrive & maintain status", "Activate visa, complete local registrations, and follow work conditions.", 1, listOf("Employment contract")),
                RoadmapStepDTO("Transition to residency", "After required period/criteria, apply for permanent residence category.", 12, listOf("Tax records", "Police certs"))
            )
        )

        fun immigration(): RoadmapPathDTO = RoadmapPathDTO(
            routeName = "$destination Permanent Residency (general pathway)",
            confidence = 55,
            citations = cites,
            steps = listOf(
                RoadmapStepDTO("Choose eligible stream", "Evaluate family, employment, points, or investment routes based on your profile.", 1, listOf("Identity documents", "Civil status docs")),
                RoadmapStepDTO("Collect evidence & forms", "Gather education, work, language tests, and background checks per stream.", 2, listOf("Education assessment", "Language test", "Police clearance")),
                RoadmapStepDTO("Submit application", "Complete online forms, pay fees, attend biometrics/interview if required.", 1, commonPrepDocs()),
                RoadmapStepDTO("Processing & decisions", "Track status; respond to additional document requests.", 6, emptyList()),
                RoadmapStepDTO("Landing & settlement", "Complete arrival formalities; maintain residency obligations.", 1, listOf("Medical (if required)", "Fees receipts"))
            )
        )

        fun tourist(): RoadmapPathDTO = RoadmapPathDTO(
            routeName = "$destination Visitor/Tourist",
            confidence = 75,
            citations = cites,
            steps = listOf(
                RoadmapStepDTO("Check visa need/exemptions", "Confirm if your nationality ($nat) needs a visa or can use visa-free/e-authorization.", 0, listOf("Passport")),
                RoadmapStepDTO("Prepare documents", "Funds, itinerary, accommodation, insurance per $destination consular list.", 1, commonPrepDocs() + "Travel itinerary"),
                RoadmapStepDTO("Apply & biometrics", "Submit application online or at center; attend biometrics/interview if needed.", 1, listOf("Application confirmation", "Fee receipt")),
                RoadmapStepDTO("Travel & compliance", "Respect stay limits and conditions; keep return ticket and insurance.", 0, listOf("Return ticket", "Insurance"))
            )
        )

        return when (goal.trim().lowercase()) {
            "study", "student" -> listOf(study())
            "work", "job" -> listOf(work())
            "immigration", "pr", "residency" -> listOf(immigration())
            "tourist", "visit", "visitor" -> listOf(tourist())
            else -> listOf(
                tourist().copy(routeName = "$destination Visitor (generic)"),
                work().copy(routeName = "$destination Work (generic)", confidence = 50)
            )
        }
    }

    private fun now(): Long = 0L
}
