package online.visabud.app.visabud_multiplatform.data

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import online.visabud.app.visabud_multiplatform.ai.ToastPlatform
import java.io.File

/**
 * Simple file-backed repository implementations for Android.
 * Data is stored under filesDir/visabud/ as JSON for MVP persistence.
 */

private val json = Json { prettyPrint = false; ignoreUnknownKeys = true; encodeDefaults = true }

private fun baseDirOrNull(): File? = ToastPlatform.contextOrNull()?.filesDir?.let { File(it, "visabud").apply { mkdirs() } }

class FileProfileRepository : ProfileRepository {
    private val mutex = Mutex()
    private val file get() = baseDirOrNull()?.let { File(it, "profile.json") }

    override suspend fun getProfile(): UserProfile? = mutex.withLock {
        val f = file ?: return@withLock null
        if (!f.exists()) return@withLock null
        runCatching { json.decodeFromString<UserProfile>(f.readText()) }.getOrNull()
    }

    override suspend fun upsertProfile(profile: UserProfile) { mutex.withLock {
        val f = file ?: return@withLock
        f.writeText(json.encodeToString(profile))
    } }
}

class FileChatRepository : ChatRepository {
    private val mutex = Mutex()
    private val file get() = baseDirOrNull()?.let { File(it, "chat_default.json") }

    @Serializable
    private data class ChatWrap(val messages: List<ChatMessageEntity>)

    override suspend fun addMessage(message: ChatMessageEntity) { mutex.withLock {
        val existing = listMessages(message.threadId).toMutableList()
        existing.add(message)
        persist(existing)
    } }

    override suspend fun listMessages(threadId: String): List<ChatMessageEntity> = mutex.withLock {
        val f = file ?: return@withLock emptyList()
        if (!f.exists()) return@withLock emptyList()
        runCatching { json.decodeFromString<ChatWrap>(f.readText()).messages }.getOrElse { emptyList() }
            .filter { it.threadId == threadId }
            .sortedBy { it.timestamp }
    }

    override suspend fun clearThread(threadId: String) { mutex.withLock {
        val remaining = listMessages().filterNot { it.threadId == threadId }
        persist(remaining)
    } }

    private fun persist(list: List<ChatMessageEntity>) {
        val f = file ?: return
        f.writeText(json.encodeToString(ChatWrap(list)))
    }
}

class FileEmbeddingRepository(private val maxEntries: Int = 500) : EmbeddingRepository {
    private val mutex = Mutex()
    private val file get() = baseDirOrNull()?.let { File(it, "embeddings.json") }

    @Serializable
    private data class EmbWrap(val items: List<EmbeddingItem>)

    override suspend fun upsert(item: EmbeddingItem) { mutex.withLock {
        val list = list(null).toMutableList()
        val idx = list.indexOfFirst { it.id == item.id }
        if (idx >= 0) list[idx] = item else list.add(item)
        // enforce cap by createdAt oldest
        val trimmed = list.sortedByDescending { it.createdAt }.take(maxEntries)
        persist(trimmed)
    } }

    override suspend fun get(id: String): EmbeddingItem? = mutex.withLock { list(null).firstOrNull { it.id == id } }

    override suspend fun list(limit: Int?): List<EmbeddingItem> = mutex.withLock {
        val f = file ?: return@withLock emptyList()
        if (!f.exists()) return@withLock emptyList()
        val items = runCatching { json.decodeFromString<EmbWrap>(f.readText()).items }
            .getOrElse { emptyList() }
            .sortedByDescending { it.createdAt }
        if (limit != null) items.take(limit) else items
    }

    override suspend fun clear() { mutex.withLock { persist(emptyList()) } }

    override suspend fun simpleCosineSearch(query: FloatArray, topK: Int): List<EmbeddingItem> {
        // Fallback to in-memory algorithm by loading all and computing cosine like InMemoryEmbeddingRepository
        val items = list(null)
        if (items.isEmpty()) return emptyList()
        val qn = normalize(query)
        return items
            .map { it to cosine(qn, bytesToFloatArray(it.vector)) }
            .sortedByDescending { it.second }
            .take(topK)
            .map { it.first }
    }

    private fun persist(list: List<EmbeddingItem>) {
        val f = file ?: return
        f.writeText(json.encodeToString(EmbWrap(list)))
    }

    private fun bytesToFloatArray(bytes: ByteArray): FloatArray {
        if (bytes.size % 4 != 0) return FloatArray(0)
        val out = FloatArray(bytes.size / 4)
        var i = 0
        var j = 0
        while (i < bytes.size) {
            val b0 = (bytes[i++].toInt() and 0xFF)
            val b1 = (bytes[i++].toInt() and 0xFF)
            val b2 = (bytes[i++].toInt() and 0xFF)
            val b3 = (bytes[i++].toInt() and 0xFF)
            val bits = (b3 shl 24) or (b2 shl 16) or (b1 shl 8) or b0
            out[j++] = Float.fromBits(bits)
        }
        return out
    }

    private fun normalize(v: FloatArray): FloatArray {
        var sum = 0.0
        for (x in v) sum += (x * x)
        val n = kotlin.math.sqrt(sum).toFloat()
        if (n == 0f) return v
        return FloatArray(v.size) { i -> v[i] / n }
    }
    private fun cosine(a: FloatArray, b: FloatArray): Float {
        if (a.isEmpty() || b.isEmpty() || a.size != b.size) return 0f
        var dot = 0.0f
        var an = 0.0f
        var bn = 0.0f
        for (i in a.indices) {
            val x = a[i]
            val y = b[i]
            dot += x * y
            an += x * x
            bn += y * y
        }
        val denom = (kotlin.math.sqrt(an.toDouble()).toFloat() * kotlin.math.sqrt(bn.toDouble()).toFloat())
        return if (denom == 0f) 0f else dot / denom
    }
}
