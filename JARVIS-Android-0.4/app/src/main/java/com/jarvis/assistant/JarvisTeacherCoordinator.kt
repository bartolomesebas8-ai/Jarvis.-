package com.jarvis.assistant

import android.content.Context
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import java.text.Normalizer
import java.util.Locale

/** Available external teacher identities. API credentials are supplied at runtime, never committed. */
enum class JarvisTeacherId(val label: String) {
    CHATGPT("ChatGPT"),
    CLAUDE("Claude"),
    GEMINI("Gemini")
}

enum class JarvisTeachingMode { LOCAL_ONLY, SINGLE_TEACHER, TEACHER_PANEL }

data class JarvisTeacherRequest(
    val question: String,
    val topic: String = "",
    val priorKnowledge: String = "",
    val conversation: List<JarvisLearningMessage> = emptyList()
)

data class JarvisTeacherAnswer(
    val teacher: JarvisTeacherId,
    val text: String,
    val model: String = "",
    val receivedAt: Long = System.currentTimeMillis()
)

data class JarvisTeacherPlan(
    val mode: JarvisTeachingMode,
    val selectedTeachers: List<JarvisTeacherId>,
    val reason: String
)

data class JarvisTeacherReview(
    val summary: String,
    val disagreements: List<String>,
    val unresolvedQuestions: List<String>,
    val recommendsSaving: Boolean
)

data class JarvisTeacherResult(
    val plan: JarvisTeacherPlan,
    val answers: List<JarvisTeacherAnswer>,
    val review: JarvisTeacherReview?,
    val canBeSavedAsVerified: Boolean,
    val message: String
)

/**
 * Adapter contract. Implementations should call official provider APIs over HTTPS,
 * apply bounded timeouts, and obtain credentials from JarvisTeacherKeyStore.
 */
interface JarvisTeacherProvider {
    val id: JarvisTeacherId
    val isConfigured: Boolean
    suspend fun answer(request: JarvisTeacherRequest): JarvisTeacherAnswer
    suspend fun review(request: JarvisTeacherRequest, answers: List<JarvisTeacherAnswer>): JarvisTeacherReview
}

/**
 * Chooses the smallest useful teacher group. "Complex" requests use a parallel panel;
 * routine requests stay local, which saves latency, quota and possible API costs.
 */
object JarvisTeacherRouter {
    fun plan(question: String, available: Set<JarvisTeacherId>): JarvisTeacherPlan {
        val q = normalize(question)
        val words = q.split(" ").filter { it.isNotBlank() }
        val complexSignals = listOf(
            "compara", "compara las", "evalua", "analiza profundamente",
            "arquitectura", "disena", "disena un sistema", "depura", "audita",
            "contradicciones", "varias fuentes", "plan completo", "razonamiento"
        )
        val isComplex = words.size >= 24 || complexSignals.any(q::contains) ||
            (listOf("y", "ademas", "tambien").count { " $it " in " $q " } >= 2 && words.size >= 14)
        val isMedium = words.size >= 9 || listOf(
            "explica", "programa", "codigo", "corrige", "resume", "investiga", "paso a paso"
        ).any(q::contains)

        if (available.isEmpty()) {
            return JarvisTeacherPlan(JarvisTeachingMode.LOCAL_ONLY, emptyList(),
                "No hay profesores externos configurados; JARVIS debe responder con sus recursos locales.")
        }
        if (!isMedium && !isComplex) {
            return JarvisTeacherPlan(JarvisTeachingMode.LOCAL_ONLY, emptyList(),
                "Consulta sencilla: evita llamadas externas y responde con el motor local.")
        }
        if (isComplex) {
            val selected = JarvisTeacherId.entries.filter { it in available }
            return JarvisTeacherPlan(JarvisTeachingMode.TEACHER_PANEL, selected,
                "Tarea compleja: consulta en paralelo a todos los profesores disponibles.")
        }

        val preferred = when {
            listOf("codigo", "programacion", "kotlin", "java", "python", "depura").any(q::contains) ->
                JarvisTeacherId.CHATGPT
            listOf("documento", "texto largo", "analiza", "redacta", "revision").any(q::contains) ->
                JarvisTeacherId.CLAUDE
            listOf("investiga", "actual", "fuentes", "web", "google").any(q::contains) ->
                JarvisTeacherId.GEMINI
            else -> JarvisTeacherId.CHATGPT
        }
        val selected = if (preferred in available) preferred else available.first()
        return JarvisTeacherPlan(JarvisTeachingMode.SINGLE_TEACHER, listOf(selected),
            "Tarea de dificultad intermedia: utiliza un profesor para reducir tiempo y consumo.")
    }

    private fun normalize(value: String): String = Normalizer.normalize(
        value.lowercase(Locale.ROOT), Normalizer.Form.NFD
    ).replace(Regex("\\p{M}+"), "")
        .replace(Regex("[^\\p{L}\\p{N} ]+"), " ")
        .replace(Regex("\\s+"), " ").trim()
}

/** Coordinates providers and records lessons only after an explicit approval step. */
class JarvisTeacherCoordinator(
    private val providers: List<JarvisTeacherProvider>,
    private val requestTimeoutMillis: Long = 45_000L
) {
    suspend fun consult(request: JarvisTeacherRequest): JarvisTeacherResult {
        require(request.question.isNotBlank()) { "La pregunta para los profesores está vacía." }
        val ready = providers.filter { it.isConfigured }.associateBy { it.id }
        val plan = JarvisTeacherRouter.plan(request.question, ready.keys)
        if (plan.mode == JarvisTeachingMode.LOCAL_ONLY) {
            return JarvisTeacherResult(plan, emptyList(), null, false, plan.reason)
        }

        val answers = coroutineScope {
            plan.selectedTeachers.mapNotNull { id ->
                val provider = ready[id] ?: return@mapNotNull null
                async {
                    withTimeoutOrNull(requestTimeoutMillis) {
                        runCatching { provider.answer(request) }.getOrNull()
                    }
                }
            }.awaitAll().filterNotNull()
        }

        if (answers.isEmpty()) {
            return JarvisTeacherResult(plan, emptyList(), null, false,
                "Ningún profesor respondió. JARVIS debe continuar con sus recursos locales.")
        }

        // A second-stage reviewer identifies disagreements; teacher agreement alone is not proof.
        val reviewProvider = plan.selectedTeachers.firstNotNullOfOrNull { ready[it] }
        val review = if (answers.size >= 2 && reviewProvider != null) {
            withTimeoutOrNull(requestTimeoutMillis) {
                runCatching { reviewProvider.review(request, answers) }.getOrNull()
            }
        } else null

        val verified = review != null && review.disagreements.isEmpty() &&
            review.unresolvedQuestions.isEmpty() && review.recommendsSaving && answers.size >= 2
        val message = when {
            answers.size < plan.selectedTeachers.size ->
                "Respuesta parcial: uno o más profesores no respondieron. Revisa antes de guardar."
            review == null && answers.size >= 2 ->
                "Hay varias respuestas, pero no se pudo completar la revisión. No se marcarán como verificadas."
            review != null && !verified ->
                "Los profesores requieren revisión adicional. No se guardará como conocimiento verificado."
            verified ->
                "Revisión cruzada completada. El contenido puede proponerse para guardar; la aprobación sigue siendo explícita."
            else ->
                "Respuesta de un profesor recibida. Puede servir como orientación, pero no está verificada."
        }
        return JarvisTeacherResult(plan, answers, review, verified, message)
    }

    /** Must be called only after the app/user approves the reviewed lesson. */
    fun saveApprovedLesson(
        context: Context,
        title: String,
        topic: String,
        result: JarvisTeacherResult,
        approvedContent: String
    ): JarvisLearningRecord {
        require(approvedContent.isNotBlank()) { "No hay una lección aprobada para guardar." }
        require(result.answers.isNotEmpty()) { "No hay respuestas de profesores para guardar." }
        val sourceNames = result.answers.joinToString(", ") { it.teacher.label }
        val provenance = buildString {
            appendLine("Profesores consultados: $sourceNames")
            appendLine("Revisión cruzada: " + if (result.canBeSavedAsVerified) "sin discrepancias detectadas" else "no verificada")
            result.review?.disagreements?.forEach { appendLine("Discrepancia: $it") }
            appendLine()
            append(approvedContent.trim())
        }
        return JarvisLearningEngine.addKnowledge(
            context = context,
            title = title,
            topic = topic,
            content = provenance,
            sourceType = if (result.canBeSavedAsVerified) "teacher_reviewed" else "teacher_unverified",
            confidence = if (result.canBeSavedAsVerified) 0.82 else 0.55,
            verifiedAt = if (result.canBeSavedAsVerified) System.currentTimeMillis() else 0L
        )
    }
}
