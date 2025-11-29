package online.visabud.app.visabud_multiplatform.ai

// Common expect/actual abstraction so that Cactus SDK is only used on platforms that support it.
// This keeps commonMain free of platform-specific dependencies.

data class ChatMsg(val content: String, val role: String)

interface AiChatClient {
    suspend fun ensureReady(contextSize: Int = 2048)
    suspend fun send(messages: List<ChatMsg>, temperature: Double? = 0.3): String
    fun unload()
}

// Platform toast helper (no-op on non-Android platforms)
expect fun showToast(message: String)

expect fun aiChatClient(): AiChatClient
