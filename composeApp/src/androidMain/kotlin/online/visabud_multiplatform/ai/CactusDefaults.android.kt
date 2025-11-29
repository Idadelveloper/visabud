package online.visabud.app.visabud_multiplatform.ai

import com.cactus.CactusLM
import com.cactus.CactusCompletionParams
import com.cactus.InferenceMode

// Android actuals: provide default embedder and LLM wrappers using the shared CactusLmHolder
actual suspend fun defaultEmbedderOrNull(): (suspend (String) -> List<Double>)? {
    // Ensure model and embeddings API are ready
    CactusLmHolder.ensureReady()
    return { text: String ->
        val emb = CactusLmHolder.withLm { it.generateEmbedding(text) }
        if (emb == null || !emb.success) emptyList() else emb.embeddings
    }
}

actual suspend fun defaultLlmFnOrNull(): (suspend (system: String, user: String) -> String)? {
    CactusLmHolder.ensureReady()
    return { system: String, user: String ->
        val msgs = listOf(
            com.cactus.ChatMessage(system, "system"),
            com.cactus.ChatMessage(user, "user")
        )
        val res = CactusLmHolder.withLm {
            it.generateCompletion(
                messages = msgs,
                params = CactusCompletionParams(
                    model = "local-qwen3-0.6",
                    temperature = 0.2,
                    topP = 0.9,
                    maxTokens = 600,
                    mode = InferenceMode.LOCAL,
                    stopSequences = listOf("<|im_end|>", "<end_of_turn>")
                )
            )
        }
        res?.response.orEmpty()
    }
}
