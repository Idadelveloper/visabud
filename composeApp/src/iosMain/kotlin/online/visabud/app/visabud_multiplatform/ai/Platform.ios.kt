package online.visabud.app.visabud_multiplatform.ai

import platform.Foundation.NSLog

actual fun showToast(message: String) {
    // No native toast in common iOS KMP target; we can log instead.
    NSLog("[Toast] %s", message)
}
