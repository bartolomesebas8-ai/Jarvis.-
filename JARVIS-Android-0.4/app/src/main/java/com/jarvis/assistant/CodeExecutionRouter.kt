package com.jarvis.assistant

/**
 * Punto único de decisión para el Laboratorio. No ejecuta código por sí mismo.
 * Solo deriva a motores que estén integrados y aprobados explícitamente.
 */
enum class CodeEngine { JAVASCRIPT_WEBVIEW, PYTHON, JAVA_INTERPRETER, NONE }

data class CodeRoute(
    val language: String,
    val engine: CodeEngine,
    val ready: Boolean,
    val reason: String
)

object CodeExecutionRouter {
    fun route(report: CodeGuardReport): CodeRoute {
        if (report.language == "Desconocido" || !report.confidence.startsWith("Alta")) {
            return CodeRoute(report.language, CodeEngine.NONE, false,
                "La detección no tiene confianza suficiente. Ajusta el nombre o la extensión del archivo.")
        }
        if (report.blockedForExecution) {
            return CodeRoute(report.language, CodeEngine.NONE, false,
                "Code Guard detectó señales de alto riesgo y bloqueó la ejecución.")
        }
        return when (report.language) {
            "JavaScript" -> CodeRoute("JavaScript", CodeEngine.JAVASCRIPT_WEBVIEW, true,
                "Se utilizará WebView restringido: sin red, archivos ni permisos del teléfono y con límite de tiempo.")
            "Python" -> CodeRoute("Python", CodeEngine.PYTHON, true,
                "Se utilizará Python 3.12 dentro de un proceso Android aislado, sin permisos de la app y con límite de tiempo.")
            "Java" -> CodeRoute("Java", CodeEngine.JAVA_INTERPRETER, true,
                "Se utilizará BeanShell en un proceso Android aislado. Admite sintaxis Java-like, no todo el JDK ni compilación Java completa.")
            else -> CodeRoute(report.language, CodeEngine.NONE, false,
                "Este lenguaje aún no tiene un motor de ejecución compatible.")
        }
    }
}
