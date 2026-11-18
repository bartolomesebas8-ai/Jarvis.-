package com.jarvis.assistant

import android.content.Context

/**
 * Stable public boundary for JARVIS' independent Android core.
 * The core uses deterministic intent handling and local retrieval; it does not
 * require a downloaded GGUF model or a native inference runtime.
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
    const val ARCHITECTURE_VERSION = "JARVIS Core 0.1"

    fun respond(
        context: Context,
        message: String,
        recentConversation: String = "",
        webPages: List<JarvisResearchPage> = emptyList()
    ): String = JarvisCore.respond(context, message, recentConversation, webPages)

    fun health(context: Context): OpenJarvisAndroidHealth {
        val appContext = context.applicationContext
        val learning = JarvisLearningEngine.stats(appContext)
        val documents = runCatching { KnowledgeLibrary.list(appContext).size }.getOrDefault(0)
        return OpenJarvisAndroidHealth(
            architecture = ARCHITECTURE_VERSION,
            localEngineReady = true,
            activeModelPath = null,
            learnedRecords = learning.records,
            learningSessions = learning.sessions,
            importedDocuments = documents,
            webResearchProvider = JarvisFreeWebResearchProvider.displayName
        )
    }
}
