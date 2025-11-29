package online.visabud.app.visabud_multiplatform.ai

private class IosSttClient : SttClient {
    override suspend fun ensureReady(model: String) { /* no-op stub */ }
    override suspend fun isModelDownloaded(model: String): Boolean = false
    override suspend fun transcribe(filePath: String): String? = null
    override fun unload() { /* no-op */ }
}

actual fun sttClient(): SttClient = IosSttClient()
