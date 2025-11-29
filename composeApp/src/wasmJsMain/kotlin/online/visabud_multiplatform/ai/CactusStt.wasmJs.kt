package online.visabud.app.visabud_multiplatform.ai

private class WasmJsSttClient : SttClient {
    override suspend fun ensureReady(model: String) { /* not supported in wasm mvp */ }
    override suspend fun isModelDownloaded(model: String): Boolean = false
    override suspend fun transcribe(filePath: String): String? = null
    override fun unload() { }
}

actual fun sttClient(): SttClient = WasmJsSttClient()
