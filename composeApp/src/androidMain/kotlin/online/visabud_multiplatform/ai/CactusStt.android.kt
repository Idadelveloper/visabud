package online.visabud.app.visabud_multiplatform.ai

import com.cactus.CactusInitParams
import com.cactus.CactusSTT
import com.cactus.CactusTranscriptionParams

private class AndroidSttClient : SttClient {
    private val stt = CactusSTT()
    private var ready = false
    private var currentModel: String = "whisper-tiny"

    override suspend fun ensureReady(model: String) {
        currentModel = model
        try {
            val downloaded = runCatching { stt.isModelDownloaded(model) }.getOrNull() ?: false
            if (!downloaded) {
                runCatching { stt.downloadModel(model) }.getOrNull()
            }
        } catch (_: Throwable) { /* ignore */ }
        // Initialize
        stt.initializeModel(CactusInitParams(model = model))
        ready = true
    }

    override suspend fun isModelDownloaded(model: String): Boolean {
        return runCatching { stt.isModelDownloaded(model) }.getOrNull() ?: false
    }

    override suspend fun transcribe(filePath: String): String? {
        if (!ready) ensureReady(currentModel)
        val res = stt.transcribe(
            filePath = filePath,
            params = CactusTranscriptionParams(
                model = currentModel,
                maxTokens = 1024,
                stopSequences = listOf("<|im_end|>", "<end_of_turn>")
            )
        )
        return if (res != null && res.success) res.text?.trim() else null
    }

    override fun unload() {
        // No-op; CactusSTT does not require explicit unload for MVP
    }
}

actual fun sttClient(): SttClient = AndroidSttClient()
