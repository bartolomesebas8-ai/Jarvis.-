package com.jarvis.assistant

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class CodeWorkspace(val id: String, val name: String, val source: String)

object CodeWorkspaces {
    private const val KEY = "jarvis_code_workspaces"
    private const val MAX_ITEMS = 20
    private const val MAX_CHARS = 200_000

    fun list(context: Context): List<CodeWorkspace> {
        val raw = context.getSharedPreferences("jarvis_local_memory", Context.MODE_PRIVATE).getString(KEY, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { i ->
                val item = array.optJSONObject(i) ?: return@mapNotNull null
                val name = item.optString("name", "").trim()
                val source = item.optString("source", "")
                if (name.isBlank() || source.isBlank() || source.length > MAX_CHARS) null
                else CodeWorkspace(item.optString("id", UUID.randomUUID().toString()), name.take(60), source)
            }.take(MAX_ITEMS)
        } catch (_: Exception) { emptyList() }
    }

    fun save(context: Context, items: List<CodeWorkspace>) {
        require(items.size <= MAX_ITEMS) { "Puedes guardar hasta 20 espacios de código." }
        require(items.all { it.name.isNotBlank() && it.source.isNotBlank() && it.source.length <= MAX_CHARS }) { "Cada espacio necesita nombre y código (máximo 200 000 caracteres)." }
        val array = JSONArray()
        items.forEach { array.put(JSONObject().put("id", it.id).put("name", it.name.take(60)).put("source", it.source)) }
        context.getSharedPreferences("jarvis_local_memory", Context.MODE_PRIVATE).edit().putString(KEY, array.toString()).commit()
    }

    fun compare(first: CodeWorkspace, second: CodeWorkspace): List<String> {
        val findings = mutableListOf<String>()
        fun declarations(source: String): Set<String> = Regex("""\\b(class|object|interface|fun|function|def)\\s+([A-Za-z_][A-Za-z0-9_]*)""")
            .findAll(source).map { it.groupValues[2] }.toSet()
        val duplicate = declarations(first.source).intersect(declarations(second.source))
        if (duplicate.isNotEmpty()) findings += "Posibles declaraciones duplicadas: ${duplicate.take(12).joinToString(", ")}."
        val packageA = Regex("""(?m)^\\s*package\\s+([A-Za-z0-9_.]+)""").find(first.source)?.groupValues?.get(1)
        val packageB = Regex("""(?m)^\\s*package\\s+([A-Za-z0-9_.]+)""").find(second.source)?.groupValues?.get(1)
        if (packageA != null && packageB != null && packageA != packageB) findings += "Los paquetes declarados no coinciden ($packageA frente a $packageB); revisa si esa separación es intencional."
        val importsA = Regex("""(?m)^\\s*import\\s+([A-Za-z0-9_.*]+)""").findAll(first.source).map { it.groupValues[1] }.toSet()
        val importsB = Regex("""(?m)^\\s*import\\s+([A-Za-z0-9_.*]+)""").findAll(second.source).map { it.groupValues[1] }.toSet()
        if (importsA.intersect(importsB).isNotEmpty()) findings += "Hay imports compartidos; normalmente no es un error, pero conviene revisar versiones y nombres en conflicto."
        if (findings.isEmpty()) findings += "No se detectaron conflictos estructurales simples con estas reglas. No demuestra compatibilidad: faltan compilación y pruebas aisladas."
        else findings += "Resultado preliminar por patrones de texto; puede haber falsos positivos y conflictos no detectados. No se ejecutó ningún código."
        return findings
    }
}
