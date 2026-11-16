package com.jarvis.assistant

import android.content.Context

/**
 * Android-native compatibility layer inspired by OpenJarvis' composable primitives.
 *
 * This is not the upstream Python OpenJarvis runtime. It maps the same architectural
 * boundaries onto JARVIS' existing Android-safe components so the app does not need
 * a desktop Python stack, Docker, or a background server.
 */
data class OpenJarvisAndroidHealth(
    val architecture: String,
    val localEngineReady: Boolean,
    val activeModelPath: String?,
    val learnedRecords: Int,
    val learningSessions: Int,
    val importedDocuments: Int,
    val webResearchProvider: String
)

object OpenJarvisAndroidKernel {
    const val ARCHITECTURE_VERSION = "OpenJarvis-inspired Android kernel 0.1"

    /**
     * Engine primitive: all inference stays behind the existing llama.cpp adapter.
     * This keeps model loading/lifecycle in LocalLlamaRuntime and avoids a second
     * competing inference engine.
     */
    suspend fun generate(prompt: String, maxTokens: Int = 384): String {
        require(prompt.isNotBlank()) { "El mensaje para el motor está vacío." }
        return LocalLlamaRuntime.generate(prompt, maxTokens)
    }

    /**
     * Intelligence + Tools/Memory + Learning health snapshot.
     * A ready model means the local runtime reports a loaded model; it does not
     * claim that the full OpenJarvis feature set or autonomous training is active.
     */
    fun health(context: Context): OpenJarvisAndroidHealth {
        val learning = JarvisLearningEngine.stats(context.applicationContext)
        val documents = runCatching {
            KnowledgeLibrary.list(context.applicationContext).size
        }.getOrDefault(0)

        return OpenJarvisAndroidHealth(
            architecture = ARCHITECTURE_VERSION,
            localEngineReady = LocalLlamaRuntime.isLoaded(),
            activeModelPath = LocalLlamaRuntime.loadedPath(),
            learnedRecords = learning.records,
            learningSessions = learning.sessions,
            importedDocuments = documents,
            webResearchProvider = JarvisFreeWebResearchProvider.displayName
        )
    }
}
