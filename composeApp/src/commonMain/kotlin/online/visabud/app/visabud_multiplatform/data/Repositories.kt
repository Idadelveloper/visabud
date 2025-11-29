package online.visabud.app.visabud_multiplatform.data

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Repository interfaces (platform-agnostic). Implementations can be Room, SQLDelight, or in-memory. */

interface ProfileRepository {
    suspend fun getProfile(): UserProfile?
    suspend fun upsertProfile(profile: UserProfile)
}

interface DocumentRepository {
    suspend fun add(doc: DocumentMeta)
    suspend fun get(id: String): DocumentMeta?
    suspend fun list(): List<DocumentMeta>
    suspend fun remove(id: String)
}

interface EmbeddingRepository {
    /** Adds or replaces an embedding; repository should enforce a cap (e.g., 500 rows) */
    suspend fun upsert(item: EmbeddingItem)
    suspend fun get(id: String): EmbeddingItem?
    suspend fun list(limit: Int? = null): List<EmbeddingItem>
    suspend fun clear()
    /** Optional vector search stub; real DB can implement ANN search later */
    suspend fun simpleCosineSearch(query: FloatArray, topK: Int = 5): List<EmbeddingItem>
}

interface RoadmapRepository {
    suspend fun upsert(roadmap: Roadmap)
    suspend fun get(id: String): Roadmap?
    suspend fun list(): List<Roadmap>
    suspend fun remove(id: String)
}

interface ChatRepository {
    suspend fun addMessage(message: ChatMessageEntity)
    suspend fun listMessages(threadId: String = "default"): List<ChatMessageEntity>
    suspend fun clearThread(threadId: String = "default")
}

/** Default in-memory implementations to keep app functional without a DB. */
class InMemoryProfileRepository : ProfileRepository {
    private val mutex = Mutex()
    private var profile: UserProfile? = null
    override suspend fun getProfile(): UserProfile? = mutex.withLock { profile }
    override suspend fun upsertProfile(profile: UserProfile) { mutex.withLock { this.profile = profile } }
}

class InMemoryDocumentRepository : DocumentRepository {
    private val mutex = Mutex()
    private val map = LinkedHashMap<String, DocumentMeta>()
    override suspend fun add(doc: DocumentMeta) { mutex.withLock { map[doc.id] = doc } }
    override suspend fun get(id: String): DocumentMeta? = mutex.withLock { map[id] }
    override suspend fun list(): List<DocumentMeta> = mutex.withLock { map.values.toList() }
    override suspend fun remove(id: String) { mutex.withLock { map.remove(id) } }
}

class InMemoryEmbeddingRepository(private val maxEntries: Int = 500) : EmbeddingRepository {
    private val mutex = Mutex()
    private val map = LinkedHashMap<String, EmbeddingItem>()

    override suspend fun upsert(item: EmbeddingItem) {
        mutex.withLock {
            map[item.id] = item
            // Enforce cap by dropping oldest items (by createdAt) when size exceeds max
            if (map.size > maxEntries) {
                val overflow = map.size - maxEntries
                val oldest = map.values.sortedBy { it.createdAt }.take(overflow).map { it.id }.toSet()
                oldest.forEach { map.remove(it) }
            }
        }
    }

    override suspend fun get(id: String): EmbeddingItem? = mutex.withLock { map[id] }

    override suspend fun list(limit: Int?): List<EmbeddingItem> = mutex.withLock {
        val items = map.values.sortedByDescending { it.createdAt }
        if (limit != null) items.take(limit) else items
    }

    override suspend fun clear() { mutex.withLock { map.clear() } }

    override suspend fun simpleCosineSearch(query: FloatArray, topK: Int): List<EmbeddingItem> = mutex.withLock {
        if (map.isEmpty()) return@withLock emptyList()
        val qn = normalize(query)
        map.values
            .map { it to cosine(qn, bytesToFloatArray(it.vector)) }
            .sortedByDescending { it.second }
            .take(topK)
            .map { it.first }
    }

    private fun bytesToFloatArray(bytes: ByteArray): FloatArray {
        // Expect native-endian 32-bit floats packed; if not, treat as empty
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
        val out = FloatArray(v.size)
        for (i in v.indices) out[i] = v[i] / n
        return out
    }

    private fun cosine(a: FloatArray, b: FloatArray): Float {
        if (a.isEmpty() || b.isEmpty() || a.size != b.size) return Float.NaN
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

class InMemoryRoadmapRepository : RoadmapRepository {
    private val mutex = Mutex()
    private val map = LinkedHashMap<String, Roadmap>()
    override suspend fun upsert(roadmap: Roadmap) { mutex.withLock { map[roadmap.id] = roadmap } }
    override suspend fun get(id: String): Roadmap? = mutex.withLock { map[id] }
    override suspend fun list(): List<Roadmap> = mutex.withLock { map.values.toList() }
    override suspend fun remove(id: String) { mutex.withLock { map.remove(id) } }
}

class InMemoryChatRepository : ChatRepository {
    private val mutex = Mutex()
    private val messagesByThread = HashMap<String, MutableList<ChatMessageEntity>>()

    override suspend fun addMessage(message: ChatMessageEntity) {
        mutex.withLock {
            val list = messagesByThread.getOrPut(message.threadId) { mutableListOf() }
            list.add(message)
        }
    }

    override suspend fun listMessages(threadId: String): List<ChatMessageEntity> = mutex.withLock {
        messagesByThread[threadId]?.sortedBy { it.timestamp }?.toList() ?: emptyList()
    }

    override suspend fun clearThread(threadId: String) {
        mutex.withLock { messagesByThread.remove(threadId) }
    }
}
