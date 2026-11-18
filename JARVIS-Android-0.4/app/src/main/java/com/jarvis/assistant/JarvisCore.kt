package com.jarvis.assistant

import android.content.Context
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * First independent JARVIS reasoning layer.
 *
 * This is a deterministic intent + retrieval engine, not a trained language model.
 * It can answer from the app's own library and saved knowledge without downloading
 * any external model. Free-form generation is a later milestone.
 */
object JarvisCore {
    const val VERSION = "0.1"

    fun respond(
        context: Context,
        message: String,
        recentConversation: String = "",
        webPages: List<JarvisResearchPage> = emptyList()
    ): String {
        val query = message.trim()
        if (query.isBlank()) return "Te escucho. Escribe qué necesitas y lo revisamos."

        val normalized = query.lowercase(Locale.ROOT)
            .replace(Regex("[¿?¡!,.;:]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

        if (Regex("^(hola|holaa+|buenos dias|buenas tardes|buenas noches|hey|que tal)( jarvis)?$").matches(normalized)) {
            return "Hola. Soy JARVIS. Mi núcleo propio está activo y puedo consultar la memoria, los documentos y los conocimientos guardados. ¿Qué hacemos?"
        }
        if (normalized.contains("como te llamas") || normalized == "quien eres") {
            return "Soy JARVIS, el asistente de este dispositivo. Estoy funcionando con mi núcleo propio de conversación, memoria y recuperación de información."
        }
        if (normalized.contains("que puedes hacer") || normalized.contains("tus funciones") || normalized == "ayuda") {
            return "Puedo consultar la Biblioteca y los conocimientos guardados, buscar información en fuentes web disponibles, conservar aprendizajes y ayudarte con las herramientas integradas. La generación libre de respuestas todavía está en desarrollo."
        }
        if (normalized.contains("que hora es") || normalized.contains("fecha de hoy") || normalized.contains("que dia es")) {
            val now = LocalDateTime.now()
            return "Según la hora de este dispositivo, son las " +
                now.format(DateTimeFormatter.ofPattern("HH:mm", Locale("es", "PE"))) +
                " del " + now.format(DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale("es", "PE"))) + "."
        }
        if (normalized in setOf("gracias", "muchas gracias", "ok gracias", "vale gracias")) {
            return "De nada. Seguiré organizando y recuperando la información que guardemos en JARVIS."
        }

        val profileAnswer = runCatching { KnowledgeLibrary.answerQuestion(context, query) }.getOrNull()
        if (!profileAnswer.isNullOrBlank()) {
            return "Encontré esto en la información guardada en JARVIS: $profileAnswer"
        }

        if (webPages.isNotEmpty()) {
            val sources = webPages.take(4).joinToString("\n\n") { page ->
                "• ${page.title}\n${page.extractedText.take(1100)}\nFuente: ${page.url}"
            }
            return "Encontré información en las fuentes web disponibles. Este es un resumen de los resultados; todavía no equivale a una respuesta generada por un modelo de lenguaje:\n\n$sources"
        }

        val learned = runCatching { JarvisLearningEngine.search(context, query, 4) }.getOrDefault(emptyList())
        if (learned.isNotEmpty()) {
            return "Consulté los conocimientos guardados y encontré información relacionada:\n\n" +
                learned.joinToString("\n\n") { match ->
                    "• ${match.record.title}\n${match.excerpt}" +
                        if (match.record.sourceUrl.isNotBlank()) "\nFuente: ${match.record.sourceUrl}" else ""
                }
        }

        val docs = runCatching { KnowledgeLibrary.search(context, query, 4) }.getOrDefault(emptyList())
        if (docs.isNotEmpty()) {
            return "Encontré fragmentos relacionados en tu Biblioteca:\n\n" +
                docs.joinToString("\n\n") { "• ${it.document.name}\n${it.excerpt}" }
        }

        val continuity = recentConversation.lineSequence().filter { it.isNotBlank() }.takeLast(2).joinToString(" ")
        return buildString {
            append("Todavía no tengo una respuesta fiable para eso. Mi núcleo propio ya puede reconocer consultas, recuperar memoria y documentos, y organizar investigaciones, pero aún no tiene un modelo generativo que permita conversar libremente sobre cualquier tema.")
            if (continuity.isNotBlank()) append("\n\nTengo presente el contexto reciente: ").append(continuity.take(240))
            append("\n\nPuedes pedirme que busque el tema en Internet o guardar información para consultarla después.")
        }
    }
}
