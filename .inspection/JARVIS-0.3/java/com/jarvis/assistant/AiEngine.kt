package com.jarvis.assistant

/** Common contract for all JARVIS language-model engines. */
interface AiEngine {
    val id: String
    val displayName: String
    suspend fun reply(history: List<Line>, userMessage: String): EngineReply
}

data class EngineReply(
    val text: String,
    val isConnected: Boolean,
    val detail: String? = null
)

/** Safe placeholder until a private backend is configured. Never stores an API key in the APK. */
class GroqEngine : AiEngine {
    override val id = "groq"
    override val displayName = "Groq (online)"

    override suspend fun reply(history: List<Line>, userMessage: String) = EngineReply(
        text = "Groq todavía no está conectado. Tu mensaje permanece guardado en la memoria local.",
        isConnected = false,
        detail = "Requiere un servicio intermediario privado para proteger la clave API."
    )
}

/** PocketPal/Qwen adapter placeholder. PocketPal has not exposed a confirmed local API endpoint. */
class QwenEngine : AiEngine {
    override val id = "qwen"
    override val displayName = "Qwen (offline)"

    override suspend fun reply(history: List<Line>, userMessage: String) = EngineReply(
        text = "Qwen todavía no está conectado a JARVIS. Tu mensaje permanece guardado en la memoria local.",
        isConnected = false,
        detail = "Primero necesitamos una interfaz local compatible desde PocketPal."
    )
}

/** One shared entry point. The selected engine can change without changing local chat storage. */
class AiEngineRouter(
    private val groq: AiEngine = GroqEngine(),
    private val qwen: AiEngine = QwenEngine()
) {
    fun engineFor(mode: AiMode): AiEngine = when (mode) {
        AiMode.ONLINE -> groq
        AiMode.OFFLINE -> qwen
    }
}

enum class AiMode { ONLINE, OFFLINE }
