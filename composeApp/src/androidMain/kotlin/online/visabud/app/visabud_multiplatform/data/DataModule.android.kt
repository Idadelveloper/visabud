package online.visabud.app.visabud_multiplatform.data

private var initialized = false

actual fun ensureDataModuleInitialized() {
    if (initialized) return
    initialized = true
    // Swap in persistent repositories on Android
    DataModule.profiles = FileProfileRepository()
    DataModule.chats = FileChatRepository()
    DataModule.embeddings = FileEmbeddingRepository(maxEntries = 500)
    // Keep documents/roadmaps in-memory for now; can be persisted later
}