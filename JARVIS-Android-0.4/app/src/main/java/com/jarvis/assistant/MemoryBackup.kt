package com.jarvis.assistant

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Validated JSON backup for JARVIS local memories.
 * Import is additive and deduplicates exact category/text pairs.
 */
object MemoryBackup {
    private const val PREFS = "jarvis_local_memory"
    private const val KEY = "saved_memories"
    private const val MAX_BACKUP_BYTES = 512 * 1024
    private val categories = setOf("Personal", "Proyectos", "Estudios", "Metas", "Temporal")

    fun export(context: Context): String {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "[]") ?: "[]"
        val source = JSONArray(raw)
        val memories = JSONArray()
        for (i in 0 until source.length()) {
            val item = source.optJSONObject(i)
            if (item != null) {
                memories.put(JSONObject()
                    .put("category", item.optString("category", "Personal"))
                    .put("text", item.optString("text", "")))
            } else {
                memories.put(JSONObject().put("category", "Personal").put("text", source.optString(i)))
            }
        }
        return JSONObject()
            .put("format", "jarvis-memory-backup")
            .put("formatVersion", 1)
            .put("memories", memories)
            .toString(2)
    }

    fun import(context: Context, jsonText: String): Int {
        require(jsonText.toByteArray(Charsets.UTF_8).size <= MAX_BACKUP_BYTES) { "La copia supera 512 KB." }
        val root = JSONObject(jsonText)
        require(root.optString("format") == "jarvis-memory-backup") { "El archivo no es una copia de memoria de JARVIS." }
        require(root.optInt("formatVersion", -1) == 1) { "La versión de esta copia no es compatible." }
        val incoming = root.optJSONArray("memories") ?: throw IllegalArgumentException("La copia no contiene recuerdos.")
        require(incoming.length() <= 2000) { "La copia contiene demasiados recuerdos." }

        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val existingRaw = prefs.getString(KEY, "[]") ?: "[]"
        val merged = JSONArray(existingRaw)
        val known = mutableSetOf<String>()
        for (i in 0 until merged.length()) {
            val item = merged.optJSONObject(i) ?: continue
            known.add(item.optString("category", "Personal") + "\u0000" + item.optString("text", ""))
        }

        val additions = mutableListOf<JSONObject>()
        for (i in 0 until incoming.length()) {
            val item = incoming.optJSONObject(i) ?: continue
            val category = item.optString("category", "Personal")
            val text = item.optString("text", "").trim()
            require(category in categories) { "La copia contiene una categoría no válida." }
            require(text.isNotEmpty() && text.length <= 2000) { "La copia contiene un recuerdo vacío o demasiado largo." }
            val key = category + "\u0000" + text
            if (known.add(key)) additions.add(JSONObject().put("category", category).put("text", text))
        }
        require(merged.length() + additions.size <= 2000) { "La memoria alcanzaría el límite de 2000 recuerdos." }
        additions.forEach { merged.put(it) }
        check(prefs.edit().putString(KEY, merged.toString()).commit()) { "No se pudo guardar la copia importada." }
        return additions.size
    }
}
