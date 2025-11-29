package online.visabud.app.visabud_multiplatform.platform

import online.visabud.app.visabud_multiplatform.ai.ToastPlatform
import java.io.File

actual fun saveTextFile(defaultName: String, contents: String): Boolean {
    val ctx = ToastPlatform.contextOrNull() ?: return false
    return try {
        val base = File(ctx.filesDir, "visabud/exports").apply { mkdirs() }
        val safe = defaultName.replace(Regex("[^A-Za-z0-9_. -]"), "").ifBlank { "export.txt" }
        val file = File(base, safe)
        file.writeText(contents)
        online.visabud.app.visabud_multiplatform.ai.showToast("Saved: ${file.name}")
        true
    } catch (_: Throwable) {
        false
    }
}
