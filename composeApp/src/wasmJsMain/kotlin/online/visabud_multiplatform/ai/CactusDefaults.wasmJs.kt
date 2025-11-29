package online.visabud.app.visabud_multiplatform.ai

// WasmJs actuals: return null defaults
actual suspend fun defaultEmbedderOrNull(): (suspend (String) -> List<Double>)? = null
actual suspend fun defaultLlmFnOrNull(): (suspend (system: String, user: String) -> String)? = null
