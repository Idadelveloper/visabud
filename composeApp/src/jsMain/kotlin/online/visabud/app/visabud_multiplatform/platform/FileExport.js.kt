package online.visabud.app.visabud_multiplatform.platform

actual fun saveTextFile(defaultName: String, contents: String): Boolean {
    // Not supported in web MVP; could trigger download via JS later.
    return false
}
