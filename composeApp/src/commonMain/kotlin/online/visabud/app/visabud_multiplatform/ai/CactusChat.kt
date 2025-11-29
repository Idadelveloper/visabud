package online.visabud.app.visabud_multiplatform.ai

// Common expect/actual abstraction so that Cactus SDK is only used on platforms that support it.
// This keeps commonMain free of platform-specific dependencies.

data class ChatMsg(val content: String, val role: String)

interface AiChatClient {
    suspend fun ensureReady(contextSize: Int = 2048)
    suspend fun send(messages: List<ChatMsg>, temperature: Double? = 0.3): String
    /** Streaming completions: onToken is called for each token as it arrives. Returns the final full text. */
    suspend fun sendStreaming(
        messages: List<ChatMsg>,
        temperature: Double? = 0.3,
        onToken: (String) -> Unit
    ): String
    suspend fun isModelDownloaded(): Boolean
    fun unload()
}

// Platform toast helper (no-op on non-Android platforms)
expect fun showToast(message: String)

expect fun aiChatClient(): AiChatClient

// Default on-device LLM hooks (expect/actual). On Android, backed by CactusLM; on other platforms, may return null.
expect suspend fun defaultEmbedderOrNull(): (suspend (String) -> List<Double>)?
expect suspend fun defaultLlmFnOrNull(): (suspend (system: String, user: String) -> String)?

// ---- Speech-to-Text (STT) abstraction ----
interface SttClient {
    suspend fun ensureReady(model: String = "whisper-tiny")
    suspend fun isModelDownloaded(model: String = "whisper-tiny"): Boolean
    suspend fun transcribe(filePath: String): String?
    fun unload()
}

expect fun sttClient(): SttClient

// ---- Audio recording abstraction ----
interface AudioRecorder {
    suspend fun start(): Boolean
    suspend fun stop(): String? // returns file path or null on failure
    fun isRecording(): Boolean
}

expect fun audioRecorder(platform: Any? = null): AudioRecorder
