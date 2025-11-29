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
    private val lm: CactusLM by lazy { CactusLM(enableToolFiltering = false) }
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

    fun instance(): CactusLM = lm

    fun isLoaded(): Boolean = lm.isLoaded()

    /** Intentionally do not unload during app lifetime to avoid native races. */
    fun doNotUnload() { /* no-op by design */ }
}
