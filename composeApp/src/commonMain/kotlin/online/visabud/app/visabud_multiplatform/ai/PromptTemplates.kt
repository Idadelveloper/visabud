package online.visabud.app.visabud_multiplatform.ai

/**
 * Prompt templates for structured tasks required by the hackathon brief.
 * These produce clear system instructions so the local model returns strict JSON.
 */
object PromptTemplates {
    private const val dateChecked = "2025-11-29"

    fun roadmapSystem(verifiedFacts: List<VisaFactsRag.RetrievedFact>): String {
        val factsBlock = if (verifiedFacts.isEmpty()) "(No local verified facts found for this query.)" else buildString {
            appendLine("Verified Facts (curated, authoritative):")
            verifiedFacts.forEachIndexed { idx, f ->
                appendLine("- [${f.country}] ${f.fact}")
            }
            appendLine()
            appendLine("Citations:")
            verifiedFacts.map { it.site }.distinct().forEach { site ->
                appendLine("- Source: ${site} (last checked ${dateChecked})")
            }
        }
        return buildString {
            appendLine("You are VisaBud. Produce up to 3 alternate visa roadmaps for the user's goal.")
            appendLine("Use only the user's profile JSON and the verified facts below. Be concise and realistic.")
            appendLine("Output strictly valid JSON with this schema:")
            appendLine("[")
            appendLine("  {\"routeName\": string, \"steps\": [")
            appendLine("    {\"title\": string, \"description\": string, \"estimatedMonths\": number, \"requiredDocs\": [string]}" )
            appendLine("  ], \"confidence\": number (0-100), \"citations\": [string] }")
            appendLine("]")
            appendLine()
            appendLine("Rules:")
            appendLine("- Return JSON only. No markdown, no commentary.")
            appendLine("- Include \"citations\" using official sources from the verified facts below.")
            appendLine("- If insufficient info, still propose prudent generic routes with lower confidence.")
            appendLine()
            appendLine(factsBlock)
        }
    }

    fun documentReviewSystem(): String = buildString {
        appendLine("You are VisaBud. Based on the target visa (targetVisa) and the following parsed document fields (documentJson),")
        appendLine("evaluate document completeness and list missing items, flags, and suggestions.")
        appendLine("Output strictly this JSON schema:")
        appendLine("{\"status\": \"OK|MISSING|ERROR\", \"issues\": [{\"field\": string, \"problem\": string, \"severity\": \"low|medium|high\"}], \"suggestions\": [string]}")
        appendLine("Rules: Return JSON only. No markdown, no commentary.")
    }

    fun interviewPracticeSystem(): String = buildString {
        appendLine("You are an immigration interviewer. For the given visa type, do the following:")
        appendLine("1) Ask 6 targeted interview questions.")
        appendLine("2) After each short answer from the user, you will simulate brief follow-up probes (assume plausible short answers).")
        appendLine("3) Provide feedback with scores on clarity, relevance, and completeness.")
        appendLine("Output a final JSON only with this schema:")
        appendLine("{\"visaType\": string, \"questions\": [ {\"q\": string, \"expectedGoodAnswer\": string, \"followUps\": [string] } ], \"feedback\": { \"clarity\": number (0-100), \"relevance\": number (0-100), \"completeness\": number (0-100) }, \"suggestions\": [string], \"overallScore\": number (0-100) }")
        appendLine("Rules: Return JSON only. No markdown, no commentary.")
    }
}
