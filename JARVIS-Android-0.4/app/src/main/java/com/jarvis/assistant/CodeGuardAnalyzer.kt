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
    val summary: String,
    val confidence: String = "Media"
)

object CodeGuardAnalyzer {
    private const val MAX_SOURCE_CHARS = 200_000

    fun analyze(fileName: String, source: String): CodeGuardReport {
        require(source.length <= MAX_SOURCE_CHARS) {
            "El archivo supera el límite de análisis de 200 000 caracteres."
        }

        val detection = detectLanguage(fileName, source)
        val language = detection.first
        val confidence = detection.second
        if (language == "Desconocido") {
            return CodeGuardReport(
                language, emptyList(), source.lines().size, true,
                "No pude identificar el lenguaje con suficiente certeza. No se enviará a ningún motor.",
                confidence
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

            if (Regex("\\b(eval|exec|compile)\\s*\\(").containsMatchIn(normalized)) {
                add(CodeRisk.HIGH, "DYNAMIC_CODE", "Ejecución dinámica",
                    "La línea parece evaluar texto como código. Puede permitir ejecutar contenido no confiable.")
            }
            if (Regex("\\b(runtime\\.getruntime\\(\\)\\.exec|processbuilder)\\b").containsMatchIn(normalized)) {
                add(CodeRisk.HIGH, "SYSTEM_PROCESS", "Lanzamiento de procesos",
                    "La línea parece iniciar procesos del sistema. La ejecución requiere revisión adicional.")
            }
            if (Regex("\\b(delete|unlink|rmtree|remove)\\s*\\(").containsMatchIn(normalized)) {
                add(CodeRisk.MEDIUM, "FILE_DELETE", "Posible eliminación de archivos",
                    "La línea parece borrar archivos o directorios. Revisa su alcance.")
            }
            if (Regex("\\b(socket|httpurlconnection|okhttpclient|requests\\.(get|post)|urlopen)\\b").containsMatchIn(normalized)) {
                add(CodeRisk.MEDIUM, "NETWORK_ACCESS", "Acceso de red",
                    "La línea parece realizar comunicaciones de red.")
            }
            if (Regex("(password|passwd|api[_-]?key|secret|token)").containsMatchIn(normalized)) {
                add(CodeRisk.MEDIUM, "POSSIBLE_SECRET", "Referencia a credenciales",
                    "La línea menciona posibles credenciales. Comprueba que no haya secretos incrustados.")
            }
            if (Regex("\\b(base64|frombase64string|b64decode)\\b").containsMatchIn(normalized)) {
                add(CodeRisk.LOW, "ENCODING", "Codificación de datos",
                    "La codificación puede ser legítima, pero conviene revisar el contenido.")
            }
            if (Regex("\\b(chmod|su\\s|sudo|os\\.system|subprocess)\\b").containsMatchIn(normalized)) {
                add(CodeRisk.HIGH, "PRIVILEGED_OR_SHELL", "Comando de sistema o privilegios",
                    "La línea parece invocar una shell o cambiar permisos.")
            }
            if (Regex("\\b(import|from)\\s+(os|sys|subprocess|socket|pathlib|shutil)\\b").containsMatchIn(normalized)) {
                add(CodeRisk.HIGH, "SENSITIVE_MODULE", "Módulo con acceso al sistema",
                    "El código parece importar módulos que pueden interactuar con el sistema o archivos.")
            }
            if (Regex("\\b(Class\\.forName|classloader|setAccessible|reflect)\\b").containsMatchIn(normalized)) {
                add(CodeRisk.HIGH, "REFLECTION", "Acceso reflectivo",
                    "La reflexión puede eludir límites previstos del entorno de ejecución.")
            }
        }

        val blocked = findings.any { it.risk == CodeRisk.CRITICAL || it.risk == CodeRisk.HIGH }
        val summary = when {
            blocked -> "Se detectaron señales de alto riesgo. La ejecución queda bloqueada hasta revisar el código."
            findings.isNotEmpty() -> "Se encontraron patrones que conviene revisar. El análisis no garantiza que el código sea seguro."
            else -> "No se detectaron patrones de riesgo cubiertos por estas reglas. Esto no demuestra que el código sea seguro."
        }
        return CodeGuardReport(
            language,
            findings.distinctBy { Triple(it.line, it.ruleId, it.evidence) },
            lines.size,
            blocked,
            summary,
            confidence
        )
    }

    private fun detectLanguage(fileName: String, source: String): Pair<String, String> {
        val extension = fileName.substringAfterLast('.', "").lowercase()
        when (extension) {
            "py" -> return "Python" to "Alta (extensión .py)"
            "js", "mjs", "cjs" -> return "JavaScript" to "Alta (extensión .$extension)"
            "java" -> return "Java" to "Alta (extensión .java)"
            "kt", "kts" -> return "Kotlin" to "Alta (extensión .$extension)"
        }

        val text = source.trim()
        if (text.isBlank()) return "Desconocido" to "Nula"

        val pythonSignals = listOf(
            Regex("(?m)^\\s*def\\s+[A-Za-z_]\\w*\\s*\\("),
            Regex("(?m)^\\s*(from\\s+[\\w.]+\\s+import|import\\s+[\\w.]+)"),
            Regex("(?m)^\\s*if\\s+.+:\\s*$"),
            Regex("(?m)^\\s*print\\s*\\(")
        ).count { it.containsMatchIn(text) }
        val javaSignals = listOf(
            Regex("(?m)^\\s*public\\s+(static\\s+)?class\\s+"),
            Regex("(?m)^\\s*import\\s+java\\."),
            Regex("System\\.out\\.(print|println)\\s*\\("),
            Regex("public\\s+static\\s+void\\s+main\\s*\\(")
        ).count { it.containsMatchIn(text) }
        val jsSignals = listOf(
            Regex("(?m)^\\s*(const|let|var)\\s+[A-Za-z_$]"),
            Regex("\\bconsole\\s*\\.\\s*(log|info|warn|error)\\s*\\("),
            Regex("\\b(function|async)\\s+[A-Za-z_$][\\w$]*\\s*\\("),
            Regex("=>"),
            Regex("\\b(JSON|Math|Array|Object|Promise)\\s*\\.")
        ).count { it.containsMatchIn(text) }

        val ranked = listOf("Python" to pythonSignals, "Java" to javaSignals, "JavaScript" to jsSignals)
            .sortedByDescending { it.second }
        val best = ranked[0]
        val second = ranked[1]
        if (best.second >= 2 && best.second >= second.second + 1) {
            return best.first to "Media-alta (coincidencia de sintaxis)"
        }
        return "Desconocido" to "Insuficiente o ambigüa"
    }
}
