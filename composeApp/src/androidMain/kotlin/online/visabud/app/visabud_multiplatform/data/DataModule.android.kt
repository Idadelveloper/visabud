package online.visabud.app.visabud_multiplatform.data

private var initialized = false

actual fun ensureDataModuleInitialized() {
    if (initialized) return
    initialized = true
    // Swap in persistent repositories on Android
    DataModule.profiles = FileProfileRepository()
    DataModule.chats = FileChatRepository()
    DataModule.embeddings = FileEmbeddingRepository(maxEntries = 500)
    DataModule.roadmaps = FileRoadmapRepository()
    // Keep documents in-memory for now; can be persisted later
}