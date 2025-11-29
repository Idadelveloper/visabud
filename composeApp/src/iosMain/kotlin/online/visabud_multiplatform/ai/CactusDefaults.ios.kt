package online.visabud.app.visabud_multiplatform.ai

// iOS actuals: return null (no default on-device model wiring in MVP)
actual suspend fun defaultEmbedderOrNull(): (suspend (String) -> List<Double>)? = null
actual suspend fun defaultLlmFnOrNull(): (suspend (system: String, user: String) -> String)? = null
