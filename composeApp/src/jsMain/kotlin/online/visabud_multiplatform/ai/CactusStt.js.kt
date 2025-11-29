package online.visabud.app.visabud_multiplatform.ai

private class JsSttClient : SttClient {
    override suspend fun ensureReady(model: String) { /* not supported on web mvp */ }
    override suspend fun isModelDownloaded(model: String): Boolean = false
    override suspend fun transcribe(filePath: String): String? = null
    override fun unload() { }
}

actual fun sttClient(): SttClient = JsSttClient()
