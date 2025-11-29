package online.visabud.app.visabud_multiplatform.ai

import com.cactus.CactusCompletionParams
import com.cactus.CactusInitParams
import com.cactus.CactusLM
import com.cactus.InferenceMode
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private class CactusAiChatClient : AiChatClient {
    private val lm: CactusLM by lazy { CactusLM(enableToolFiltering = false) }
    private val initMutex = Mutex()
    private var initialized = false
    private val modelSlug = "local-qwen3-0.6"

    override suspend fun ensureReady(contextSize: Int): Unit = initMutex.withLock {
        if (initialized && lm.isLoaded()) return
        lm.downloadModel(modelSlug)
        lm.initializeModel(
            CactusInitParams(
                model = modelSlug,
                contextSize = contextSize
            )
        )
        initialized = true
    }

    override suspend fun send(messages: List<ChatMsg>, temperature: Double?): String {
        val cactusMessages = messages.map { com.cactus.ChatMessage(it.content, it.role) }
        val result = lm.generateCompletion(
            messages = cactusMessages,
            params = CactusCompletionParams(
                model = modelSlug,
                temperature = temperature,
                mode = InferenceMode.LOCAL
            )
        )
        if (result == null || !result.success) {
            throw IllegalStateException(result?.response ?: "Cactus completion failed")
        }
        return result.response.orEmpty()
    }

    override fun unload() {
        lm.unload()
        initialized = false
    }
}

actual fun aiChatClient(): AiChatClient = CactusAiChatClient()
