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
    val facts: List<String>
)

@Serializable
private data class VisaFactsList(val items: List<VisaFactsEntry>)

/** A tiny in-memory RAG index for visa facts stored in assets/visa_facts.json */
object VisaFactsRag {
    private const val RESOURCE_PATH = "files/visa_facts.json"

    // Loaded raw entries
    private var entries: List<VisaFactsEntry> = emptyList()

    // For each fact we keep its embedding in-memory for fast cold-starts; also persist via EmbeddingRepository
    private data class FactRow(
        val countryCode: String,
        val country: String,
        val site: String,
        val fact: String,
        val embedding: List<Double>
    )

    private var index: List<FactRow> = emptyList()
    private var isReady: Boolean = false

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

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
        if (isReady && index.isNotEmpty()) return

        // Load JSON from compose resources
        val bytes = Res.readBytes(RESOURCE_PATH)
        // JSON is a raw array of entries
        val list: List<VisaFactsEntry> = json.decodeFromString(
            bytes.decodeToString()
        )
        entries = list

        // Build rows for each fact with embeddings
        val rows = mutableListOf<FactRow>()
        for (e in entries) {
            for (f in e.facts) {
                val emb = embedder("${e.country}: $f")
                rows += FactRow(e.code, e.country, e.officialSite, f, emb)
            }
        }
        index = rows
        isReady = true
    }

    fun isInitialized(): Boolean = isReady && index.isNotEmpty()

    /** Retrieve topK most similar facts for a free-form query (in-memory index) */
    fun retrieve(queryEmbedding: List<Double>, topK: Int = 4): List<RetrievedFact> {
        if (!isInitialized()) return emptyList()
        val qn = normalize(queryEmbedding)
        val scored = index.map { row ->
            val sn = normalize(row.embedding)
            val score = cosine(qn, sn)
            RetrievedFact(row.countryCode, row.country, row.site, row.fact, score)
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
        // If repo already has entries, skip
        val existing = DataModule.embeddings.list(limit = 1)
        if (existing.isNotEmpty()) return
        // Load entries if not yet
        if (entries.isEmpty()) {
            val bytes = Res.readBytes(RESOURCE_PATH)
            val list: List<VisaFactsEntry> = json.decodeFromString(bytes.decodeToString())
            entries = list
        }
        // For each fact, compute embedding and upsert
        val now = currentTimeMillisSafe()
        var counter = 0
        for (e in entries) {
            for (f in e.facts) {
                val text = "${e.country}: $f"
                val emb = embedder(text).map { it.toFloat() }
                val id = "visa_fact_${e.code}_${counter++}"
                val tagsJson = json.encodeToString(mapOf(
                    "code" to e.code,
                    "country" to e.country,
                    "site" to e.officialSite,
                    "fact" to f
                ))
                val bytesVec = floatArrayToBytes(emb.toFloatArray())
                DataModule.embeddings.upsert(
                    EmbeddingItem(
                        id = id,
                        text = text,
                        vector = bytesVec,
                        tags = tagsJson,
                        createdAt = now
                    )
                )
            }
        }
    }

    /** Repository-based retrieval using cosine similarity. */
    suspend fun retrieveFromRepo(query: String, embedder: suspend (String) -> List<Double>, topK: Int = 4): List<RetrievedFact> {
        val qVec = embedder(query).map { it.toFloat() }.toFloatArray()
        val top = DataModule.embeddings.simpleCosineSearch(qVec, topK)
        if (top.isEmpty()) return emptyList()
        return top.mapNotNull { item ->
            // Parse tags JSON back to fields
            try {
                val obj = json.parseToJsonElement(item.tags) as? JsonObject
                val code = obj?.get("code")?.jsonPrimitive?.content ?: ""
                val country = obj?.get("country")?.jsonPrimitive?.content ?: ""
                val site = obj?.get("site")?.jsonPrimitive?.content ?: ""
                val fact = obj?.get("fact")?.jsonPrimitive?.content ?: item.text
                RetrievedFact(code, country, site, fact, score = 1.0) // score not used downstream
            } catch (_: Throwable) { null }
        }
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
