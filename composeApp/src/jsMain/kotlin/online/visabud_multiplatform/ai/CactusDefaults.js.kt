package online.visabud.app.visabud_multiplatform.ai

// JS actuals: return null defaults (no on-device LLM on web MVP)
actual suspend fun defaultEmbedderOrNull(): (suspend (String) -> List<Double>)? = null
actual suspend fun defaultLlmFnOrNull(): (suspend (system: String, user: String) -> String)? = null
