package com.jarvis.assistant

import android.content.Context

/**
 * Android-native integration boundary inspired by OpenJarvis.
 *
 * This deliberately reuses JARVIS' existing llama.cpp runtime, memory,
 * learning store and web research provider. It does not embed the upstream
 * Python runtime or claim autonomous model-weight training.
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

    /** Engine primitive: all local inference passes through one stable boundary. */
    suspend fun generate(prompt: String, maxTokens: Int = 384): String {
        require(prompt.isNotBlank()) { "El mensaje para el motor está vacío." }
        return LocalLlamaRuntime.generate(prompt, maxTokens)
    }

    /** Reports the state of the Android components mapped to OpenJarvis primitives. */
    fun health(context: Context): OpenJarvisAndroidHealth {
        val appContext = context.applicationContext
        val learning = JarvisLearningEngine.stats(appContext)
        val documents = runCatching { KnowledgeLibrary.list(appContext).size }.getOrDefault(0)

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
