package online.visabud.app.visabud_multiplatform.ai

// JS/WASM stub to satisfy KMP when Cactus SDK isn't available.
private class StubAiChatClient : AiChatClient {
    override suspend fun ensureReady(contextSize: Int) {}
    override suspend fun send(messages: List<ChatMsg>, temperature: Double?): String =
        "[Stub] Local model is not available on Web."
    override suspend fun sendStreaming(
        messages: List<ChatMsg>,
        temperature: Double?,
        onToken: (String) -> Unit
    ): String {
        val text = send(messages, temperature)
        onToken(text)
        return text
    }
    override suspend fun isModelDownloaded(): Boolean = false
    override fun unload() {}
}

actual fun aiChatClient(): AiChatClient = StubAiChatClient()
