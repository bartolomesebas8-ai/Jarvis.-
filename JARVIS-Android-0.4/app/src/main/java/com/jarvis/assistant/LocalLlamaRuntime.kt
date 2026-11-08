package com.jarvis.assistant

import android.content.Context
import android.net.Uri
import com.arm.aichat.AiChat
import com.arm.aichat.InferenceEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File

/**
 * Owns the local llama.cpp model lifecycle for JARVIS.
 * The selected SAF document is copied atomically into app-private storage because
 * the native inference API requires a filesystem path.
 */
object LocalLlamaRuntime {
    @Volatile private var activeEngine: InferenceEngine? = null
    @Volatile private var activeModelPath: String? = null
    private val engineMutex = Mutex()

    suspend fun load(
        context: Context,
        modelUri: String,
        displayName: String,
        systemPrompt: String
    ): String = engineMutex.withLock {
        withContext(Dispatchers.IO) {
            val appContext = context.applicationContext
            val modelsDir = File(appContext.filesDir, "local_models").apply {
                if (!exists() && !mkdirs()) throw IllegalStateException("No se pudo crear la carpeta privada del modelo.")
            }
            val safeName = displayName.substringAfterLast('/').replace(Regex("[^A-Za-z0-9._-]"), "_")
            require(safeName.endsWith(".gguf", ignoreCase = true)) {
                "El archivo seleccionado no tiene extensión .gguf."
            }

            val modelFile = File(modelsDir, safeName)
            if (!modelFile.exists() || modelFile.length() == 0L) {
                val source = Uri.parse(modelUri)
                val descriptorSize = runCatching {
                    appContext.contentResolver.openAssetFileDescriptor(source, "r")?.use { it.length }
                }.getOrNull() ?: -1L
                val requiredSpace = if (descriptorSize > 0L) descriptorSize + 64L * 1024L * 1024L
                    else 256L * 1024L * 1024L
                val availableSpace = modelsDir.usableSpace
                require(availableSpace >= requiredSpace) {
                    "No hay suficiente espacio libre para copiar el modelo. Libera espacio e inténtalo de nuevo."
                }

                val temporaryFile = File(modelsDir, "$safeName.copying")
                temporaryFile.delete()
                try {
                    val input = appContext.contentResolver.openInputStream(source)
                        ?: throw IllegalStateException("Android no pudo abrir el archivo GGUF seleccionado.")
                    input.use { stream ->
                        temporaryFile.outputStream().buffered().use { output ->
                            stream.copyTo(output, 1024 * 1024)
                        }
                    }
                    require(temporaryFile.length() > 1024L * 1024L) {
                        "El archivo GGUF parece incompleto o demasiado pequeño."
                    }
                    if (modelFile.exists() && !modelFile.delete()) {
                        throw IllegalStateException("No se pudo reemplazar la copia anterior del modelo.")
                    }
                    if (!temporaryFile.renameTo(modelFile)) {
                        throw IllegalStateException("No se pudo finalizar la copia del modelo.")
                    }
                } catch (error: Exception) {
                    temporaryFile.delete()
                    throw error
                }
            }
            require(modelFile.isFile && modelFile.canRead() && modelFile.length() > 1024L * 1024L) {
                "La copia local del modelo no es válida o no se puede leer."
            }

            val engine = AiChat.getInferenceEngine(appContext)
            val initialState = withTimeout(180_000L) {
                engine.state.first {
                    it is InferenceEngine.State.Initialized ||
                        it is InferenceEngine.State.ModelReady ||
                        it is InferenceEngine.State.Error
                }
            }
            if (initialState is InferenceEngine.State.Error ||
                initialState is InferenceEngine.State.ModelReady
            ) {
                engine.cleanUp()
            }

            engine.loadModel(modelFile.absolutePath)
            try {
                engine.setSystemPrompt(systemPrompt)
            } catch (error: Exception) {
                runCatching { engine.cleanUp() }
                throw error
            }

            activeEngine = engine
            activeModelPath = modelFile.absolutePath
            "Modelo GGUF cargado en JARVIS: ${modelFile.name} (${modelFile.length() / (1024 * 1024)} MB)."
        }
    }

    suspend fun generate(prompt: String, maxTokens: Int = 384): String =
        engineMutex.withLock {
            withContext(Dispatchers.IO) {
                require(prompt.isNotBlank()) { "No hay texto para enviar al modelo." }
                val engine = activeEngine
                    ?: throw IllegalStateException("Primero carga un modelo GGUF desde Ajustes.")
                check(engine.state.value is InferenceEngine.State.ModelReady) {
                    "El motor local todavía está ocupado o no está listo. Espera un momento e inténtalo de nuevo."
                }
                engine.sendUserPrompt(prompt, maxTokens.coerceIn(32, 1024))
                    .toList()
                    .joinToString("")
                    .trim()
                    .ifBlank { "El modelo no generó texto. Intenta formular la pregunta de otra manera." }
            }
        }

    fun isLoaded(): Boolean =
        activeEngine?.state?.value is InferenceEngine.State.ModelReady && activeModelPath != null

    fun loadedPath(): String? = activeModelPath
}
