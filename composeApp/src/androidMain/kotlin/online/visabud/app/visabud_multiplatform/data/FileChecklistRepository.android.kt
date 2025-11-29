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
 * Android file-backed persistence for Checklists.
 * Stored under filesDir/visabud/checklists.json as a JSON array wrapper.
 */
class FileChecklistRepository : ChecklistRepository {
    private val mutex = Mutex()
    private val json = Json { prettyPrint = false; ignoreUnknownKeys = true; encodeDefaults = true }
    private fun baseDirOrNull(): File? = ToastPlatform.contextOrNull()?.filesDir?.let { File(it, "visabud").apply { mkdirs() } }
    private val file get() = baseDirOrNull()?.let { File(it, "checklists.json") }

    @Serializable
    private data class Wrap(val items: List<Checklist>)

    override suspend fun upsert(checklist: Checklist) { mutex.withLock {
        val list = list().toMutableList()
        val idx = list.indexOfFirst { it.id == checklist.id }
        val updated = checklist.copy(updatedAt = System.currentTimeMillis())
        if (idx >= 0) list[idx] = updated else list.add(updated)
        persist(list)
    } }

    override suspend fun get(id: String): Checklist? = mutex.withLock { list().firstOrNull { it.id == id } }

    override suspend fun list(): List<Checklist> = mutex.withLock {
        val f = file ?: return@withLock emptyList()
        if (!f.exists()) return@withLock emptyList()
        runCatching { json.decodeFromString<Wrap>(f.readText()).items }
            .getOrElse { emptyList() }
            .sortedByDescending { it.updatedAt }
    }

    override suspend fun remove(id: String) { mutex.withLock {
        val remaining = list().filterNot { it.id == id }
        persist(remaining)
    } }

    private fun persist(list: List<Checklist>) {
        val f = file ?: return
        f.writeText(json.encodeToString(Wrap(list)))
    }
}
