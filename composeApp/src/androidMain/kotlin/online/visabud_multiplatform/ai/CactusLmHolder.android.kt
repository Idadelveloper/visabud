package online.visabud.app.visabud_multiplatform.ai

import com.cactus.CactusInitParams
import com.cactus.CactusLM
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Single shared CactusLM instance for the Android process to avoid concurrent
 * create/destroy of the native context across different features (chat, docs, RAG).
 */
object CactusLmHolder {
    // ---- Tool filtering configuration (can be adjusted before first use) ----
    @Volatile var toolFilterStrategy: com.cactus.services.ToolFilterStrategy = com.cactus.services.ToolFilterStrategy.SEMANTIC
    @Volatile var toolFilterMaxTools: Int = 3
    @Volatile var toolFilterSimilarityThreshold: Double = 0.5

    /**
     * Optionally adjust tool filtering parameters BEFORE the LLM instance is first created.
     * If called after the instance has been created, the change will not take effect until app restart.
     */
    fun configureToolFiltering(strategy: com.cactus.services.ToolFilterStrategy = toolFilterStrategy,
                               maxTools: Int = toolFilterMaxTools,
                               similarityThreshold: Double = toolFilterSimilarityThreshold) {
        if (_lmCreated) return // no-op if already created
        toolFilterStrategy = strategy
        toolFilterMaxTools = maxTools
        toolFilterSimilarityThreshold = similarityThreshold
    }

    @Volatile private var _lmCreated: Boolean = false
    private val lm: CactusLM by lazy {
        _lmCreated = true
        // Enable tool filtering per Cactus SDK; prefer SEMANTIC for better accuracy with many tools
        CactusLM(
            enableToolFiltering = true,
            toolFilterConfig = com.cactus.services.ToolFilterConfig(
                strategy = toolFilterStrategy,
                maxTools = toolFilterMaxTools,
                similarityThreshold = toolFilterSimilarityThreshold
            )
        )
    }
    private val initMutex = Mutex()
    private val opMutex = Mutex()
    @Volatile private var initialized = false
    @Volatile private var modelSlug: String = "local-qwen3-0.6"

    suspend fun ensureReady(contextSize: Int = 2048, model: String = "local-qwen3-0.6") = initMutex.withLock {
        if (initialized && lm.isLoaded()) return
        modelSlug = model
        // Download only if necessary
        try {
            if (!isModelDownloaded(modelSlug)) {
                withLm { it.downloadModel(modelSlug) }
            }
        } catch (_: Throwable) {
            // proceed to init; SDK may use cached model
        }
        withLm { it.initializeModel(CactusInitParams(model = modelSlug, contextSize = contextSize)) }
        initialized = true
    }

    suspend fun <T> withLm(action: suspend (CactusLM) -> T): T = opMutex.withLock { action(lm) }

    suspend fun isModelDownloaded(slug: String = modelSlug): Boolean {
        return try {
            withLm { it.getModels() }.any { it.slug == slug && it.isDownloaded }
        } catch (_: Throwable) { false }
    }

    // Convenience helpers used by Android-specific code
    suspend fun generateCompletion(system: String, user: String): String? {
        ensureReady()
        val msgs = listOf(
            com.cactus.ChatMessage(system, "system"),
            com.cactus.ChatMessage(user, "user")
        )
        val res = withLm { it.generateCompletion(msgs) }
        return res?.response
    }

    suspend fun generateEmbedding(text: String): List<Double>? {
        ensureReady()
        val res = withLm { it.generateEmbedding(text) }
        return if (res != null && res.success) res.embeddings else null
    }

    fun instance(): CactusLM = lm

    fun isLoaded(): Boolean = lm.isLoaded()

    /** Intentionally do not unload during app lifetime to avoid native races. */
    fun doNotUnload() { /* no-op by design */ }
}
