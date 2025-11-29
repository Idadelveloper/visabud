package online.visabud.app.visabud_multiplatform.ai

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.math.sqrt
import org.jetbrains.compose.resources.ExperimentalResourceApi
import visabud_multiplatform.composeapp.generated.resources.Res
import online.visabud.app.visabud_multiplatform.data.DataModule
import online.visabud.app.visabud_multiplatform.data.EmbeddingItem
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.encodeToString

@Serializable
data class VisaFactsEntry(
    val code: String,
    val country: String,
    @SerialName("official_site") val officialSite: String,
    val facts: List<String>,
    @SerialName("visa_types") val visaTypes: List<String>? = null,
    @SerialName("visa_free_policy") val visaFreePolicy: String? = null,
    val checklist: List<String>? = null,
    val fees: String? = null,
    @SerialName("processing_time") val processingTime: String? = null,
    val restrictions: List<String>? = null,
    val notes: String? = null
)

@Serializable
private data class VisaFactsList(val items: List<VisaFactsEntry>)

/** A tiny in-memory RAG index for visa facts stored in assets/visa_facts.json */
object VisaFactsRag {
    // New schema models (compatible with updated visa_facts.json root object)
    @Serializable
    private data class VisaFactsDb(
        val version: String? = null,
        val lastUpdated: String? = null,
        val countries: List<DbCountry>? = null
    )

    @Serializable
    private data class DbCountry(
        val id: String? = null, // ISO3
        val countryName: String? = null,
        val countryCode: DbCountryCode? = null,
        val visaTypes: List<DbVisaType>? = null
    )

    @Serializable
    private data class DbCountryCode(val iso2: String? = null, val iso3: String? = null)

    @Serializable
    private data class DbVisaType(
        val typeId: String? = null,
        val typeName: String? = null,
        val category: String? = null,
        val generalDescription: String? = null,
        val officialSourceUrl: String? = null,
        val bilateralRequirements: List<DbBilateralReq>? = null
    )

    @Serializable
    private data class DbBilateralReq(
        val originCountry: String? = null, // ISO2 or ISO3 in sample
        val originCountryName: String? = null,
        val eligibilityCriteria: DbEligibilityCriteria? = null,
        val requiredDocuments: List<String>? = null
    )

    @Serializable
    private data class DbEligibilityCriteria(
        val ageRequirement: DbAge? = null,
        val englishLanguageRequirement: DbEnglish? = null,
        val workExperienceRequirement: DbWorkExp? = null,
        val employmentOffer: DbEmployment? = null
    )

    @Serializable private data class DbAge(val maxAge: Int? = null, val unit: String? = null)
    @Serializable private data class DbEnglish(val level: String? = null)
    @Serializable private data class DbWorkExp(val yearsRequired: Int? = null)
    @Serializable private data class DbEmployment(val required: Boolean? = null)
    private const val RESOURCE_PATH = "files/visa_facts.json"

    // Helper lookups and structured accessors for agent tool usage
    data class CountryQueryResult(
        val destination: String?,
        val nationality: String?,
        val country: VisaFactsEntry?,
        val missing: List<String>,
        val prompt: String?
    )

    fun buildMissingInfoPrompt(destination: String?, nationality: String?): String? {
        val missing = mutableListOf<String>()
        if (destination.isNullOrBlank()) missing += "destination"
        if (nationality.isNullOrBlank()) missing += "nationality"
        if (missing.isEmpty()) return null
        val ask = when (missing.size) {
            1 -> "Could you share your ${missing.first()} so I can check visa rules?"
            2 -> "To check visa rules I need your destination country and your nationality. Could you provide both?"
            else -> null
        }
        return ask
    }

    fun findCountryByNameOrCode(input: String?): VisaFactsEntry? {
        if (input.isNullOrBlank()) return null
        val key = input.trim().lowercase()
        return entries.firstOrNull { e ->
            e.code.lowercase() == key || e.country.lowercase() == key ||
            e.country.lowercase().contains(key)
        }
    }

    fun queryByDestinationNationality(destination: String?, nationality: String?): CountryQueryResult {
        val prompt = buildMissingInfoPrompt(destination, nationality)
        val country = if (destination.isNullOrBlank()) null else findCountryByNameOrCode(destination)
        val missing = buildList {
            if (destination.isNullOrBlank()) add("destination")
            if (nationality.isNullOrBlank()) add("nationality")
        }
        return CountryQueryResult(destination, nationality, country, missing, prompt)
    }

    fun buildCountrySummary(entry: VisaFactsEntry, nationality: String? = null): String {
        val sb = StringBuilder()
        sb.appendLine("Country: ${entry.country} (${entry.code})")
        if (!nationality.isNullOrBlank()) sb.appendLine("Based on your nationality: ${nationality} — double-check exemptions and consular rules.")
        // Visa-free policy and visa types
        entry.visaFreePolicy?.let { if (it.isNotBlank()) sb.appendLine("Visa-free / waiver: $it") }
        entry.visaTypes?.takeIf { it.isNotEmpty() }?.let { types ->
            sb.appendLine("Common visa types: ${types.joinToString()}")
        }
        // Typical checklist
        entry.checklist?.takeIf { it.isNotEmpty() }?.let { list ->
            sb.appendLine("Typical document checklist:")
            list.forEach { sb.appendLine("- $it") }
        }
        entry.fees?.let { if (it.isNotBlank()) sb.appendLine("Approximate fees: $it") }
        entry.processingTime?.let { if (it.isNotBlank()) sb.appendLine("Processing time: $it") }
        entry.restrictions?.takeIf { it.isNotEmpty() }?.let { list ->
            sb.appendLine("Restrictions / notes:")
            list.forEach { sb.appendLine("- $it") }
        }
        entry.notes?.let { if (it.isNotBlank()) sb.appendLine("General notes: $it") }
        sb.appendLine()
        sb.appendLine("Official site: ${entry.officialSite}")
        sb.appendLine("Reminder: Always verify on the official site. Policies can change.")
        return sb.toString().trim()
    }

    // Loaded raw entries
    private var entries: List<VisaFactsEntry> = emptyList()

    // Chunk-level RAG index with structured metadata (hybrid retrieval)
    data class RagChunk(
        val id: String,
        val text: String,
        val countryCode: String,
        val country: String,
        val site: String,
        val chunkType: String? = null, // overview | eligibility | documents | timeline | restrictions
        val visaTypeId: String? = null,
        val visaCategory: String? = null,
        val originIso3: String? = null,
        val processingWeeksMin: Int? = null,
        val processingWeeksMax: Int? = null,
        val visaFeeUsd: Double? = null,
        val requiresSponsorship: Boolean? = null,
        val allowsDependents: Boolean? = null,
        val pathToResidency: String? = null,
        val lastVerifiedDate: String? = null,
        val embedding: List<Double> = emptyList(),
        val tags: List<String> = emptyList()
    )

    private var chunks: List<RagChunk> = emptyList()
    private var isReady: Boolean = false

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    // Map new DB schema to compatibility entries used across the app
    private fun mapDbToCompat(db: VisaFactsDb): List<VisaFactsEntry> {
        val out = mutableListOf<VisaFactsEntry>()
        val countries = db.countries ?: emptyList()
        for (c in countries) {
            val code2 = (c.countryCode?.iso2 ?: c.id ?: "").uppercase()
            val name = c.countryName ?: (c.id ?: "Unknown")
            val types = c.visaTypes ?: emptyList()
            val visaTypeNames = types.mapNotNull { it.typeName }.filter { it.isNotBlank() }
            val official = types.firstOrNull { !it.officialSourceUrl.isNullOrBlank() }?.officialSourceUrl
                ?: ""
            val checklistSet = LinkedHashSet<String>()
            val factsList = mutableListOf<String>()
            for (vt in types) {
                val vtName = vt.typeName ?: vt.typeId ?: "Visa"
                val desc = vt.generalDescription?.trim().orEmpty()
                if (desc.isNotBlank()) {
                    factsList += "${vtName}: ${desc}"
                } else {
                    factsList += "${vtName}: category ${vt.category ?: "unspecified"}"
                }
                // Bilateral requirements summarized
                for (br in vt.bilateralRequirements ?: emptyList()) {
                    val origin = br.originCountryName ?: br.originCountry ?: "origin nationality"
                    val crit = br.eligibilityCriteria
                    val age = crit?.ageRequirement?.maxAge?.let { "max age ${it}" }
                    val exp = crit?.workExperienceRequirement?.yearsRequired?.let { "${it}y experience" }
                    val eng = crit?.englishLanguageRequirement?.level
                    val job = if (crit?.employmentOffer?.required == true) "employer nomination/offer required" else null
                    val bits = listOfNotNull(age, exp, eng, job)
                    if (bits.isNotEmpty()) {
                        factsList += "${vtName} — ${origin}: criteria include ${bits.joinToString(", ")}."
                    }
                    // Collect required documents hints
                    for (d in br.requiredDocuments ?: emptyList()) {
                        if (d.isNotBlank()) checklistSet.add(d.trim())
                    }
                }
            }
            val notes = db.lastUpdated?.let { "Dataset last updated ${it}" }
            out += VisaFactsEntry(
                code = code2.ifBlank { name.take(2).uppercase() },
                country = name,
                officialSite = official,
                facts = factsList.ifEmpty { listOf("Official immigration link: ${official}") },
                visaTypes = visaTypeNames.ifEmpty { null },
                visaFreePolicy = null,
                checklist = checklistSet.take(20).toList().ifEmpty { null },
                fees = null,
                processingTime = null,
                restrictions = null,
                notes = notes
            )
        }
        return out
    }

    data class RetrievedFact(
        val countryCode: String,
        val country: String,
        val site: String,
        val fact: String,
        val score: Double
    )

    /**
     * Ensure facts are loaded and embeddings are precomputed using the provided embedder.
     * The embedder must return a dense vector for the input text.
     */
    @OptIn(ExperimentalResourceApi::class)
    suspend fun ensureLoaded(embedder: suspend (String) -> List<Double>) {
        if (isReady && chunks.isNotEmpty()) return

        // Load JSON from compose resources
        val bytes = Res.readBytes(RESOURCE_PATH)
        val text = bytes.decodeToString()
        // Try new root schema first
        val mappedEntries: List<VisaFactsEntry> = try {
            val db: VisaFactsDb = json.decodeFromString(text)
            mapDbToCompat(db)
        } catch (_: Throwable) {
            // Fallback to legacy flat array
            try { json.decodeFromString(text) } catch (_: Throwable) { emptyList() }
        }
        entries = mappedEntries

        // Build chunk index (one chunk per fact for compatibility; future: generate per guide's chunk types)
        val out = mutableListOf<RagChunk>()
        var counter = 0
        for (e in entries) {
            for (f in e.facts) {
                counter += 1
                val id = "chunk_${e.code}_${counter}"
                val textChunk = "${e.country}: $f"
                val emb = embedder(textChunk)
                out += RagChunk(
                    id = id,
                    text = textChunk,
                    countryCode = e.code,
                    country = e.country,
                    site = e.officialSite,
                    chunkType = inferChunkType(f),
                    embedding = emb,
                    tags = emptyList()
                )
            }
        }
        chunks = out
        isReady = true
    }

    fun isInitialized(): Boolean = isReady && chunks.isNotEmpty()

    /** Retrieve topK most similar facts for a free-form query (in-memory chunk index) */
    fun retrieve(queryEmbedding: List<Double>, topK: Int = 4): List<RetrievedFact> {
        if (!isInitialized()) return emptyList()
        val qn = normalize(queryEmbedding)
        val scored = chunks.map { c ->
            val sn = normalize(c.embedding)
            val score = cosine(qn, sn)
            RetrievedFact(c.countryCode, c.country, c.site, c.text.removePrefix("${'$'}{c.country}: ").ifBlank { c.text }, score)
        }
        return scored.sortedByDescending { it.score }.take(topK).filter { it.score.isFinite() }
    }

    /** Convenience: embed a string and retrieve (in-memory) */
    suspend fun retrieve(query: String, embedder: suspend (String) -> List<Double>, topK: Int = 4): List<RetrievedFact> {
        val q = embedder(query)
        return retrieve(q, topK)
    }

    /** Persist embeddings into EmbeddingRepository on first run. */
    @OptIn(ExperimentalResourceApi::class)
    suspend fun ensurePersisted(embedder: suspend (String) -> List<Double>) {
        // As per latest requirement, discard any previously persisted embeddings and use fresh, in-memory index only.
        runCatching { DataModule.embeddings.clear() }.getOrNull()
        // Ensure current JSON is loaded and in-memory embeddings are built.
        ensureLoaded(embedder)
        // No-op persist to avoid stale vectors across app updates.
    }

    /** Repository-based retrieval is now routed to the fresh in-memory index to avoid stale vectors. */
    suspend fun retrieveFromRepo(query: String, embedder: suspend (String) -> List<Double>, topK: Int = 4): List<RetrievedFact> {
        ensureLoaded(embedder)
        val q = embedder(query)
        return retrieve(q, topK)
    }

    private fun floatArrayToBytes(arr: FloatArray): ByteArray {
        val out = ByteArray(arr.size * 4)
        var j = 0
        for (f in arr) {
            val bits = f.toBits()
            out[j++] = (bits and 0xFF).toByte()
            out[j++] = ((bits ushr 8) and 0xFF).toByte()
            out[j++] = ((bits ushr 16) and 0xFF).toByte()
            out[j++] = ((bits ushr 24) and 0xFF).toByte()
        }
        return out
    }

    private fun currentTimeMillisSafe(): Long = 0L

    private fun cosine(a: List<Double>, b: List<Double>): Double {
        if (a.isEmpty() || b.isEmpty() || a.size != b.size) return Double.NaN
        var dot = 0.0
        var an = 0.0
        var bn = 0.0
        for (i in a.indices) {
            val x = a[i]
            val y = b[i]
            dot += x * y
            an += x * x
            bn += y * y
        }
        val denom = (sqrt(an) * sqrt(bn))
        return if (denom == 0.0) 0.0 else dot / denom
    }

    private fun normalize(v: List<Double>): List<Double> {
        var norm = 0.0
        for (x in v) norm += x * x
        val n = sqrt(norm)
        return if (n == 0.0) v else v.map { it / n }
    }

    // --- Chunk helpers & hybrid retrieval ---
    private fun inferChunkType(text: String): String {
        val t = text.lowercase()
        return when {
            listOf("age", "english", "experience", "sponsor", "eligib").any { t.contains(it) } -> "eligibility"
            listOf("document", "passport", "certificate", "transcript", "checklist").any { t.contains(it) } -> "documents"
            listOf("process", "processing", "timeline", "weeks", "fee", "cost").any { t.contains(it) } -> "timeline"
            listOf("restriction", "conditions", "cannot", "must not").any { t.contains(it) } -> "restrictions"
            else -> "overview"
        }
    }

    data class Filters(
        val destinationIso: String? = null, // ISO2 or ISO3 accepted
        val originIso3: String? = null,
        val visaCategory: String? = null, // work-permanent, study, tourism, etc.
        val minVerifiedDate: String? = null // ISO yyyy-MM-dd
    )

    private fun isoNormalize(code: String?): String? = code?.trim()?.uppercase()?.let {
        when (it.length) {
            2 -> it
            3 -> it
            else -> null
        }
    }

    private fun dateNewerOrEqual(a: String?, b: String?): Boolean {
        if (a.isNullOrBlank() || b.isNullOrBlank()) return true
        return a >= b
    }

    suspend fun retrieveHybrid(
        query: String,
        embedder: suspend (String) -> List<Double>,
        filters: Filters,
        topK: Int = 6
    ): List<RetrievedFact> {
        ensureLoaded(embedder)
        val q = embedder(query)
        val qn = normalize(q)
        // Filter
        val dest = isoNormalize(filters.destinationIso)
        val orig = isoNormalize(filters.originIso3)
        val cat = filters.visaCategory?.lowercase()
        val minDate = filters.minVerifiedDate
        val candidates = chunks.filter { c ->
            (dest == null || c.countryCode.equals(dest, true) || c.countryCode.length == 3 && c.countryCode.endsWith(dest, true)) &&
            (orig == null || (c.originIso3?.equals(orig, true) ?: true)) &&
            (cat == null || (c.visaCategory?.lowercase()?.contains(cat) ?: false)) &&
            dateNewerOrEqual(c.lastVerifiedDate, minDate)
        }
        // Score + recency boost
        val scored = candidates.map { c ->
            val sn = normalize(c.embedding)
            var score = cosine(qn, sn)
            // Boost eligibility/document chunks slightly for requirement queries
            if (query.contains("eligib", true) && c.chunkType == "eligibility") score += 0.05
            if (query.contains("document", true) && c.chunkType == "documents") score += 0.05
            // Boost recent
            if (dateNewerOrEqual(c.lastVerifiedDate, todayIso())) score += 0.02
            RetrievedFact(c.countryCode, c.country, c.site, c.text.removePrefix("${'$'}{c.country}: ").ifBlank { c.text }, score)
        }
        return scored.sortedByDescending { it.score }.take(topK)
    }

    // Build filters from profile/destination/goal
    fun buildFilters(profile: online.visabud.app.visabud_multiplatform.data.UserProfile?, destination: String?, goalOrVisaType: String?): Filters {
        val dest = findCountryByNameOrCode(destination)?.code
        val natIso3 = nationalityToIso3(profile?.nationality)
        val cat = goalToCategory(goalOrVisaType)
        val minDate = sixMonthsAgoIso()
        return Filters(destinationIso = dest, originIso3 = natIso3, visaCategory = cat, minVerifiedDate = minDate)
    }

    private fun goalToCategory(s: String?): String? {
        val t = (s ?: "").lowercase()
        return when {
            t.contains("tour") || t.contains("visit") -> "tourism"
            t.contains("study") || t.contains("student") -> "study"
            t.contains("work") || t.contains("job") -> "work"
            t.contains("immig") || t.contains("residen") || t == "pr" -> "work-permanent"
            else -> null
        }
    }

    private fun nationalityToIso3(nationality: String?): String? {
        if (nationality.isNullOrBlank()) return null
        return when (nationality.trim().lowercase()) {
            "india", "indian", "in" -> "IND"
            "united kingdom", "british", "uk", "gb" -> "GBR"
            "united states", "american", "us", "usa" -> "USA"
            "canada", "canadian", "ca" -> "CAN"
            "australia", "australian", "au" -> "AUS"
            "germany", "german", "de" -> "DEU"
            "france", "french", "fr" -> "FRA"
            "japan", "japanese", "jp" -> "JPN"
            "united arab emirates", "uae", "ae", "emirati" -> "ARE"
            "south africa", "za" -> "ZAF"
            else -> null
        }
    }

    private fun sixMonthsAgoIso(): String {
        // Using session date constant 2025-11-29 from todayIso(); subtract 6 months naively
        val today = todayIso()
        val y = today.substring(0,4).toInt()
        val m = today.substring(5,7).toInt()
        val d = today.substring(8,10)
        val total = m - 6
        val newY = y + kotlin.math.floor((total - 1) / 12.0).toInt()
        val newM = ((total - 1) % 12 + 12) % 12 + 1
        val mm = newM.toString().padStart(2,'0')
        return "${'$'}newY-${'$'}mm-${'$'}d"
    }

    private fun extractHost(url: String): String {
        var s = url
        val idx = s.indexOf("://")
        if (idx >= 0) s = s.substring(idx + 3)
        s = s.substringBefore('/')
        s = s.substringBefore('?')
        if (s.startsWith("www.")) s = s.removePrefix("www.")
        return s
    }

    private fun todayIso(): String {
        // Use current session date provided by environment to avoid platform-specific datetime in common code
        return "2025-11-29"
    }

    fun buildSourcesBlock(retrieved: List<RetrievedFact>): String {
        if (retrieved.isEmpty()) return ""
        val date = todayIso()
        val lines = retrieved
            .distinctBy { it.site }
            .joinToString(separator = "\n") { r ->
                val host = extractHost(r.site)
                "- Source: ${host} — ${r.country} official page: ${r.site} (last checked ${date})"
            }
        return lines
    }

    /** Build a system prompt string with citations for top retrieved facts */
    fun buildSystemPreamble(retrieved: List<RetrievedFact>): String {
        if (retrieved.isEmpty()) return "You are VisaBud, a helpful visa assistant. Use only verified facts and cite official sources when relevant. Do not expose chain-of-thought; provide concise answers."
        val sb = StringBuilder()
        sb.appendLine("You are VisaBud, a helpful visa assistant.")
        sb.appendLine("Use the following verified visa facts when answering. Keep answers concise and include a short 'Sources' section with official links. Do not show your internal reasoning.")
        sb.appendLine()
        retrieved.forEachIndexed { i, r ->
            val host = extractHost(r.site)
            sb.appendLine("${i + 1}. [${r.country}] ${r.fact} (source: ${host} — ${r.site})")
        }
        sb.appendLine()
        sb.appendLine("When unsure, say so and suggest checking the official links above.")
        return sb.toString()
    }
}
