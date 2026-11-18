package com.jarvis.assistant

import android.content.Context
import android.content.Intent

/**
 * Zero-cost, user-controlled handoff to an installed assistant such as ChatGPT.
 *
 * Android does not expose another assistant's private conversation/API to JARVIS.
 * Therefore this bridge shares a prepared prompt and requires the user to return
 * and explicitly submit the teacher's answer. It never pretends to be automatic.
 */
object JarvisTeacherBridge {
    fun open(context: Context, question: String): Boolean {
        val cleanQuestion = question.trim().take(12_000)
        require(cleanQuestion.isNotBlank()) { "La pregunta está vacía." }

        val prompt = """
            Actúa como profesor y asesor técnico de JARVIS 3.13.
            Responde en español, con explicaciones claras y pasos comprobables.
            Distingue hechos, hipótesis y recomendaciones. No inventes pruebas ni resultados.
            Si la pregunta trata de programación, revisa los riesgos y conserva las funciones existentes.
            Si utilizas información actual, incluye enlaces a fuentes fiables.
            No afirmes que puedes acceder al teléfono, al repositorio ni a archivos que no se hayan compartido.

            CONSULTA:
            $cleanQuestion
        """.trimIndent()

        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, prompt)
        }
        val chooser = Intent.createChooser(sendIntent, "Consultar al profesor de JARVIS")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        return runCatching {
            context.applicationContext.startActivity(chooser)
            true
        }.getOrDefault(false)
    }
}
