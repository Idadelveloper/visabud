package online.visabud.app.visabud_multiplatform.ai
import com.cactus.CactusLM
import com.cactus.models.ToolParameter
import com.cactus.models.createTool

/**
 * Android-specific implementation of the default LLM hooks.
 * This connects the common ChatAgent to the Cactus SDK.
 */
actual suspend fun defaultLlmFnOrNull(): (suspend (system: String, user: String) -> String)? {
    return { system, user ->
        CactusLmHolder.generateCompletion(system, user)
            ?: "Sorry, I couldn't generate a response."
    }
}

actual suspend fun defaultEmbedderOrNull(): (suspend (String) -> List<Double>)? {
    return { text ->
        CactusLmHolder.generateEmbedding(text)
            ?: emptyList()
    }
}

// AI Chat Client implementation for Android
actual fun aiChatClient(): AiChatClient = object : AiChatClient {
    private val lm = CactusLM()

    // Define the tools in a format Cactus understands
    private val tools = listOf(
        createTool(
            name = "update_user_profile",
            description = "Update user profile information like nationality, destination, or purpose of travel.",
            parameters = mapOf(
                "nationality" to ToolParameter(type = "string", description = "User's nationality"),
                "destination" to ToolParameter(type = "string", description = "User's travel destination"),
                "purpose" to ToolParameter(type = "string", description = "Purpose of the user's travel (e.g., tourism, work, study)")
            )
        ),
        createTool(
            name = "get_visa_facts",
            description = "Get visa facts and requirements for a specific country.",
            parameters = mapOf(
                "country" to ToolParameter(type = "string", description = "The country to get visa facts for", required = true)
            )
        )
        // Add other tools from ChatAgent here
    )

    override suspend fun ensureReady(contextSize: Int) {
        if (!lm.isLoaded()) {
            val modelName = "qwen3-0.6" // Example model
            if (lm.getModels().firstOrNull { it.slug == modelName }?.isDownloaded != true) {
                lm.downloadModel(modelName)
            }
            lm.initializeModel(com.cactus.CactusInitParams(model = modelName, contextSize = contextSize))
        }
    }

    override suspend fun send(messages: List<ChatMsg>, temperature: Double?): String {
        val cactusMessages = messages.map { com.cactus.ChatMessage(it.content, it.role) }
        val params = com.cactus.CactusCompletionParams(temperature = temperature, tools = tools)
        val result = lm.generateCompletion(cactusMessages, params)
        return result?.response ?: "Error: Could not generate a response."
    }

    override suspend fun sendStreaming(
        messages: List<ChatMsg>,
        temperature: Double?,
        onToken: (String) -> Unit
    ): String {
        val cactusMessages = messages.map { com.cactus.ChatMessage(it.content, it.role) }
        val params = com.cactus.CactusCompletionParams(temperature = temperature, tools = tools)
        val result = lm.generateCompletion(
            cactusMessages,
            params,
            onToken = { token, _ -> onToken(token) }
        )
        return result?.response ?: "Error: Could not generate a streaming response."
    }

    override suspend fun isModelDownloaded(): Boolean {
        return lm.getModels().firstOrNull { it.slug == "qwen3-0.6" }?.isDownloaded ?: false
    }

    override fun unload() {
        lm.unload()
    }
}
