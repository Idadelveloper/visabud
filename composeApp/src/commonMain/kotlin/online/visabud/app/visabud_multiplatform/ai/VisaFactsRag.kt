package online.visabud.app.visabud_multiplatform.ai

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.math.sqrt
import org.jetbrains.compose.resources.ExperimentalResourceApi
import visabud_multiplatform.composeapp.generated.resources.Res

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

    // For each fact we keep its embedding.
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

    /** Retrieve topK most similar facts for a free-form query */
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

    /** Convenience: embed a string and retrieve */
    suspend fun retrieve(query: String, embedder: suspend (String) -> List<Double>, topK: Int = 4): List<RetrievedFact> {
        val q = embedder(query)
        return retrieve(q, topK)
    }

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

    /** Build a system prompt string with citations for top retrieved facts */
    fun buildSystemPreamble(retrieved: List<RetrievedFact>): String {
        if (retrieved.isEmpty()) return "You are VisaBud, a helpful visa assistant. Cite official sites when relevant."
        val sb = StringBuilder()
        sb.appendLine("You are VisaBud, a helpful visa assistant.")
        sb.appendLine("Use the following verified visa facts when answering. Keep answers concise and cite the matching official site(s).\n")
        retrieved.forEachIndexed { i, r ->
            sb.appendLine("${i + 1}. [${r.country}] ${r.fact} (source: ${r.site})")
        }
        sb.appendLine("\nWhen unsure, say so and suggest checking the official links above.")
        return sb.toString()
    }
}
