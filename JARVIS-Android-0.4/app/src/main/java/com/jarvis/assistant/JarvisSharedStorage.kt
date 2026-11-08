package com.jarvis.assistant

import android.content.Context
import android.os.Environment
import java.io.File
import java.text.Normalizer
import java.util.Locale

data class SharedFileHit(val file: File, val excerpt: String)

object JarvisSharedStorage {
    private const val PARTITION_LIMIT = 512L * 1024L * 1024L
    private const val MAX_TEXT_FILE = 2L * 1024L * 1024L
    private const val MAX_SCANNED_FILES = 12000
    private val searchable = setOf(
        "txt", "md", "csv", "json", "xml", "html", "htm", "log",
        "kt", "java", "py", "js", "ts", "css", "yaml", "yml", "ini", "properties"
    )

    fun hasAccess(): Boolean = android.os.Build.VERSION.SDK_INT < 30 ||
        Environment.isExternalStorageManager()

    fun root(): File? {
        if (!hasAccess()) return null
        val documents = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
        return File(documents, "JARVIS")
    }

    fun ensureStructure(): File {
        check(hasAccess()) { "JARVIS necesita que autorices «Permitir administrar todos los archivos» en los ajustes de Android." }
        val root = root() ?: error("No se pudo localizar el almacenamiento compartido.")
        listOf("Documentos", "Modelos", "Copias_de_seguridad").forEach { File(root, it).mkdirs() }
        val data = partition(root)
        listOf("Memoria", "Historial", "Configuracion").forEach { File(data, it).mkdirs() }
        return root
    }

    fun syncExistingData(context: Context) {
        if (!hasAccess()) return
        val prefs = context.getSharedPreferences("jarvis_local_memory", Context.MODE_PRIVATE)
        val exports = listOf(
            "saved_memories" to ("Memoria" to "recuerdos.json"),
            "chat_history" to ("Historial" to "conversaciones.json"),
            "jarvis_tasks" to ("Configuracion" to "tareas.json"),
            "jarvis_goals" to ("Configuracion" to "metas.json"),
            "jarvis_routines" to ("Configuracion" to "rutinas.json")
        )
        exports.forEach { (key, destination) ->
            val value = prefs.getString(key, null)
            if (!value.isNullOrBlank()) runCatching { saveSnapshot(destination.first, destination.second, value) }
        }
    }

    private fun partition(root: File): File {
        val dirs = root.listFiles { f -> f.isDirectory && f.name.matches(Regex("Datos_[0-9]{3}")) }
            ?.sortedBy { it.name }.orEmpty()
        val current = dirs.lastOrNull() ?: File(root, "Datos_001").apply { mkdirs() }
        val used = current.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        val selected = if (used >= PARTITION_LIMIT) {
            val nextNumber = (dirs.size + 1).coerceAtLeast(1)
            File(root, "Datos_" + nextNumber.toString().padStart(3, '0')).apply { mkdirs() }
        } else current
        listOf("Memoria", "Historial", "Configuracion").forEach { File(selected, it).mkdirs() }
        return selected
    }

    fun saveSnapshot(category: String, filename: String, content: String) {
        val root = ensureStructure()
        val safeCategory = category.replace(Regex("[^A-Za-z0-9_-]"), "_").take(40)
        val safeName = filename.replace(Regex("[^A-Za-z0-9._-]"), "_").take(100)
        val bytes = content.toByteArray(Charsets.UTF_8)
        val partition = partition(root)
        val folder = File(partition, safeCategory).apply { mkdirs() }
        val target = File(folder, safeName)
        target.writeBytes(bytes)
    }

    private fun normalize(value: String): String = Normalizer.normalize(
        value.lowercase(Locale.ROOT), Normalizer.Form.NFD
    ).replace(Regex("\\p{M}+"), "")
        .replace(Regex("[^\\p{L}\\p{N} ]+"), " ")
        .replace(Regex("\\s+"), " ").trim()

    fun search(query: String, limit: Int = 8): List<SharedFileHit> {
        if (!hasAccess()) return emptyList()
        val root = Environment.getExternalStorageDirectory()
        if (!root.exists()) return emptyList()
        val terms = normalize(query).split(" ").filter { it.length >= 2 }.distinct()
        if (terms.isEmpty()) return emptyList()
        val ignored = setOf("Android", ".thumbnails", ".trash", "cache", "caches", "node_modules")
        val hits = mutableListOf<Pair<Int, SharedFileHit>>()
        var visited = 0
        val stack = ArrayDeque<File>()
        stack.add(root)
        while (stack.isNotEmpty() && visited < MAX_SCANNED_FILES) {
            val dir = stack.removeLast()
            val children = try { dir.listFiles()?.sortedBy { it.name.lowercase(Locale.ROOT) }.orEmpty() }
                catch (_: SecurityException) { emptyList() }
            for (file in children) {
                if (visited >= MAX_SCANNED_FILES) break
                if (file.isDirectory) {
                    if (file.name !in ignored && !file.name.startsWith(".")) stack.add(file)
                    continue
                }
                visited++
                val name = normalize(file.name)
                var score = terms.count { name.contains(it) } * 3
                var excerpt = "Archivo: ${file.name}"
                if (file.isFile && file.length() in 1..MAX_TEXT_FILE &&
                    file.extension.lowercase(Locale.ROOT) in searchable) {
                    val body = try { file.readText(Charsets.UTF_8) } catch (_: Exception) { "" }
                    if (body.isNotBlank() && !body.contains('\u0000')) {
                        val normalizedBody = normalize(body)
                        score += terms.count { normalizedBody.contains(it) } * 2
                        if (score > 0) {
                            val first = terms.firstOrNull { normalizedBody.contains(it) }
                            val at = if (first == null) 0 else normalizedBody.indexOf(first)
                            val start = (at - 100).coerceAtLeast(0)
                            excerpt = body.replace(Regex("\\s+"), " ").let { it.substring(start.coerceAtMost(it.length), (start + 320).coerceAtMost(it.length)) }
                        }
                    }
                }
                if (score > 0) hits.add(score to SharedFileHit(file, excerpt))
            }
        }
        return hits.sortedByDescending { it.first }.take(limit).map { it.second }
    }
}
