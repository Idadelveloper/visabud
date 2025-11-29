package online.visabud.app.visabud_multiplatform.data

/**
 * Simple service locator providing in-memory repositories by default.
 * Swap these with Room/SQLDelight implementations on platforms as needed.
 *
 * Security note:
 * - For production, use SQLCipher for full-DB encryption (Android) or
 *   platform secure storage. Alternatively, encrypt sensitive fields
 *   (e.g., DocumentMeta.parsedFieldsJson, encryptedPath, EmbeddingItem.vector)
 *   at the app layer before persisting.
 */
object DataModule {
    var profiles: ProfileRepository = InMemoryProfileRepository()
    var documents: DocumentRepository = InMemoryDocumentRepository()
    var embeddings: EmbeddingRepository = InMemoryEmbeddingRepository(maxEntries = 500)
    var roadmaps: RoadmapRepository = InMemoryRoadmapRepository()
    var chats: ChatRepository = InMemoryChatRepository()
}

// Allow platform to replace repositories before first use
expect fun ensureDataModuleInitialized()
