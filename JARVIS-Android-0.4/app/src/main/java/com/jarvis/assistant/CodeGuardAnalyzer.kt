package com.jarvis.assistant

/**
 * Static, heuristic inspection only. This class NEVER evaluates or executes input.
 * A clean report is not proof that a program is safe.
 */
enum class CodeRisk { INFO, LOW, MEDIUM, HIGH, CRITICAL }

data class CodeFinding(
    val line: Int,
    val risk: CodeRisk,
    val ruleId: String,
    val title: String,
    val explanation: String,
    val evidence: String
)

data class CodeGuardReport(
    val language: String,
    val findings: List<CodeFinding>,
    val analyzedLines: Int,
    val blockedForExecution: Boolean,
    val summary: String
)

object CodeGuardAnalyzer {
    private const val MAX_SOURCE_CHARS = 200_000

    fun analyze(fileName: String, source: String): CodeGuardReport {
        require(source.length <= MAX_SOURCE_CHARS) {
            "El archivo supera el límite de análisis de 200 000 caracteres."
        }

        val language = detectLanguage(fileName, source)
        if (language == "Desconocido") {
            return CodeGuardReport(
                language, emptyList(), source.lines().size, true,
                "Lenguaje no compatible: el análisis es insuficiente y la ejecución debe permanecer bloqueada."
            )
        }

        val findings = mutableListOf<CodeFinding>()
        val lines = source.lines()
        lines.forEachIndexed { index, raw ->
            val line = raw.take(500)
            val normalized = line.lowercase()
            fun add(risk: CodeRisk, id: String, title: String, explanation: String) {
                findings += CodeFinding(index + 1, risk, id, title, explanation, line.trim().take(220))
            }

            if (Regex("\\b(eval|exec)\\s*\\(").containsMatchIn(normalized)) {
                add(CodeRisk.HIGH, "DYNAMIC_CODE", "Ejecución dinámica",
                    "La línea parece evaluar texto como código. Puede permitir ejecutar contenido no confiable.")
            }
            if (Regex("\\b(runtime\\.getruntime\\(\\)\\.exec|processbuilder)\\b").containsMatchIn(normalized)) {
                add(CodeRisk.HIGH, "SYSTEM_PROCESS", "Lanzamiento de procesos",
                    "La línea parece iniciar procesos del sistema. Requiere revisión estricta de argumentos y permisos.")
            }
            if (Regex("\\b(delete|unlink|rmtree|remove)\\s*\\(").containsMatchIn(normalized)) {
                add(CodeRisk.MEDIUM, "FILE_DELETE", "Posible eliminación de archivos",
                    "La línea parece borrar archivos o directorios. El contexto determina el alcance.")
            }
            if (Regex("\\b(socket|httpurlconnection|okhttpclient|requests\\.(get|post)|urlopen)\\b").containsMatchIn(normalized)) {
                add(CodeRisk.MEDIUM, "NETWORK_ACCESS", "Acceso de red",
                    "La línea parece realizar comunicaciones de red. Deben revisarse destino y datos transmitidos.")
            }
            if (Regex("(password|passwd|api[_-]?key|secret|token)").containsMatchIn(normalized)) {
                add(CodeRisk.MEDIUM, "POSSIBLE_SECRET", "Referencia a credenciales",
                    "La línea menciona posibles credenciales. Comprueba que no haya secretos incrustados.")
            }
            if (Regex("\\b(base64|frombase64string|b64decode)\\b").containsMatchIn(normalized)) {
                add(CodeRisk.LOW, "ENCODING", "Codificación de datos",
                    "La codificación puede ser legítima, pero también ocultar contenido; revisa los datos decodificados sin ejecutarlos.")
            }
            if (Regex("\\b(chmod|su\\s|sudo|os\\.system)\\b").containsMatchIn(normalized)) {
                add(CodeRisk.HIGH, "PRIVILEGED_OR_SHELL", "Comando de sistema o privilegios",
                    "La línea parece invocar una shell o cambiar permisos. No se debe ejecutar sin análisis adicional.")
            }
        }

        val critical = findings.any { it.risk == CodeRisk.CRITICAL || it.risk == CodeRisk.HIGH }
        val summary = when {
            critical -> "Se encontraron señales de alto riesgo. Revisa estas líneas antes de ejecutar el código."
            findings.isNotEmpty() -> "Se encontraron patrones que conviene revisar antes de ejecutar el código."
            else -> "No se detectaron patrones de riesgo cubiertos por estas reglas. Esto no demuestra que el código sea seguro."
        }
        return CodeGuardReport(language, findings.distinctBy { Triple(it.line, it.ruleId, it.evidence) },
            lines.size, true, summary)
    }

    private fun detectLanguage(fileName: String, source: String): String {
        val extension = fileName.substringAfterLast('.', "").lowercase()
        when (extension) {
            "py" -> return "Python"
            "js", "mjs", "cjs" -> return "JavaScript"
            "kt", "kts" -> return "Kotlin"
            "java" -> return "Java"
        }

        // File names created by the editor often have no extension. Detect common
        // JavaScript syntax instead of rejecting valid pasted programs.
        val text = source.trim()
        val jsSignals = listOf(
            Regex("(?m)^\\s*(const|let|var)\\s+[A-Za-z_$]"),
            Regex("\\bconsole\\s*\\.\\s*(log|info|warn|error)\\s*\\("),
            Regex("\\b(function|async)\\s+[A-Za-z_$][\\w$]*\\s*\\("),
            Regex("=>"),
            Regex("\\b(JSON|Math|Array|Object|Promise)\\s*\\."),
            Regex("(?m)^\\s*(if|for|while|switch)\\s*\\(")
        )
        val jsMatches = jsSignals.count { it.containsMatchIn(text) }
        if (jsMatches >= 1 && !text.contains("fun main(") && !Regex("(?m)^\\s*def\\s+").containsMatchIn(text)) {
            return "JavaScript"
        }
        if (text.contains("fun main(") || (text.contains("package ") && Regex("\\b(val|var)\\s+").containsMatchIn(text))) return "Kotlin probable"
        if (Regex("(?m)^\\s*def\\s+").containsMatchIn(text)) return "Python probable"
        if (Regex("(?m)^\\s*(public\\s+)?class\\s+").containsMatchIn(text) && text.contains("System.out")) return "Java probable"
        return "Desconocido"
    }
}
