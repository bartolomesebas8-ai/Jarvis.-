package com.jarvis.assistant

import android.content.Context
import android.net.Uri
import com.arm.aichat.AiChat
import com.arm.aichat.InferenceEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File

/**
 * Owns the local llama.cpp model lifecycle for JARVIS.
 * The selected SAF document is copied into app-private storage because the native
 * inference API requires a filesystem path.
 */
object LocalLlamaRuntime {
    @Volatile private var activeEngine: InferenceEngine? = null
    @Volatile private var activeModelPath: String? = null

    suspend fun load(
        context: Context,
        modelUri: String,
        displayName: String,
        systemPrompt: String
    ): String = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val modelsDir = File(appContext.filesDir, "local_models").apply { if (!exists()) mkdirs() }
        val safeName = displayName.substringAfterLast('/').replace(Regex("[^A-Za-z0-9._-]"), "_")
        require(safeName.endsWith(".gguf", ignoreCase = true)) { "El archivo seleccionado no tiene extensión .gguf." }
        val modelFile = File(modelsDir, safeName)
        if (!modelFile.exists() || modelFile.length() == 0L) {
            val source = Uri.parse(modelUri)
            val input = appContext.contentResolver.openInputStream(source)
                ?: throw IllegalStateException("Android no pudo abrir el archivo GGUF seleccionado.")
            try {
                input.use { stream ->
                    modelFile.outputStream().buffered().use { output -> stream.copyTo(output, 1024 * 1024) }
                }
            } catch (error: Exception) {
                modelFile.delete()
                throw error
            }
        }
        require(modelFile.length() > 1024L * 1024L) { "El archivo GGUF parece incompleto o demasiado pequeño." }

        val engine = AiChat.getInferenceEngine(appContext)
        val initialState = withTimeout(180_000L) {
            engine.state.first { it is InferenceEngine.State.Initialized || it is InferenceEngine.State.Error }
        }
        if (initialState is InferenceEngine.State.Error) {
            throw IllegalStateException("No se pudo iniciar llama.cpp: ${initialState.exception.message}")
        }
        if (engine.state.value is InferenceEngine.State.ModelReady || engine.state.value is InferenceEngine.State.Error) {
            engine.cleanUp()
        }
        engine.loadModel(modelFile.absolutePath)
        engine.setSystemPrompt(systemPrompt)
        activeEngine = engine
        activeModelPath = modelFile.absolutePath
        "Qwen/modelo GGUF cargado en JARVIS: ${modelFile.name} (${modelFile.length() / (1024 * 1024)} MB)."
    }

    suspend fun generate(prompt: String, maxTokens: Int = 384): String = withContext(Dispatchers.IO) {
        val engine = activeEngine ?: throw IllegalStateException("Primero carga un modelo GGUF desde Ajustes.")
        engine.sendUserPrompt(prompt, maxTokens).toList().joinToString("").trim()
            .ifBlank { "El modelo no generó texto. Intenta formular la pregunta de otra manera." }
    }

    fun isLoaded(): Boolean = activeEngine != null && activeModelPath != null

    fun loadedPath(): String? = activeModelPath
}
