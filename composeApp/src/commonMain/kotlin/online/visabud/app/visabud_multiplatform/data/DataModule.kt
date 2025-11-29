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
    val profiles: ProfileRepository by lazy { InMemoryProfileRepository() }
    val documents: DocumentRepository by lazy { InMemoryDocumentRepository() }
    val embeddings: EmbeddingRepository by lazy { InMemoryEmbeddingRepository(maxEntries = 500) }
    val roadmaps: RoadmapRepository by lazy { InMemoryRoadmapRepository() }
    val checklists: ChecklistRepository by lazy { InMemoryChecklistRepository() }
    val chats: ChatRepository by lazy { InMemoryChatRepository() }
}
