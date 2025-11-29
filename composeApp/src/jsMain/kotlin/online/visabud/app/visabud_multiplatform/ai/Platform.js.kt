package online.visabud.app.visabud_multiplatform.ai

actual fun showToast(message: String) {
    // No-op on JS; could console.log if desired
    println("[Toast] $message")
}
