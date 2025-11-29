package online.visabud.app.visabud_multiplatform.ai

actual fun showToast(message: String) {
    // No-op on WASM; could print to console
    println("[Toast] $message")
}
