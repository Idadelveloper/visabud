package online.visabud.app.visabud_multiplatform.ai

// WASM stub to satisfy KMP when Cactus SDK isn't available.
private class StubAiChatClient : AiChatClient {
    override suspend fun ensureReady(contextSize: Int) {}
    override suspend fun send(messages: List<ChatMsg>, temperature: Double?): String =
        "[Stub] Local model is not available on Web/WASM."
    override fun unload() {}
}

actual fun aiChatClient(): AiChatClient = StubAiChatClient()
