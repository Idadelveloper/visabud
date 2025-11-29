package online.visabud.app.visabud_multiplatform.ai

import android.content.Context
import android.widget.Toast

object ToastPlatform {
    lateinit var appContext: Context
    fun contextOrNull(): Context? = if (this::appContext.isInitialized) appContext else null
}

actual fun showToast(message: String) {
    ToastPlatform.contextOrNull()?.let { ctx ->
        Toast.makeText(ctx, message, Toast.LENGTH_SHORT).show()
    }
}
