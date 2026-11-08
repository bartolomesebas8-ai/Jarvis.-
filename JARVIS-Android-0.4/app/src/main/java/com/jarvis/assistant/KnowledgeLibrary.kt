package com.jarvis.assistant

import android.content.Context
import java.io.File
import java.io.InputStream
import java.util.Locale

data class KnowledgeDocument(
    val id: String,
    val name: String,
    val size: Long,
    val modified: Long
)

data class KnowledgeMatch(
    val document: KnowledgeDocument,
    val excerpt: String
)

object KnowledgeLibrary {
    private const val MAX_FILE_BYTES = 512 * 1024
    private const val MAX_TOTAL_BYTES = 5L * 1024 * 1024
    private val allowedExtensions = setOf("txt", "md", "json", "xml", "csv")

    private fun directory(context: Context): File =
        File(context.filesDir, "jarvis_knowledge").apply { mkdirs() }

    fun import(context: Context, displayName: String, input: InputStream): KnowledgeDocument {
        val safeName = displayName.substringAfterLast('/').substringAfterLast('\\').take(100)
        val extension = safeName.substringAfterLast('.', "").lowercase(Locale.ROOT)
        require(extension in allowedExtensions) { "Tipo de archivo no admitido en Biblioteca. Usa TXT, MD, JSON, XML o CSV. Para código, utiliza Code Guard." }

        val bytes = input.use { stream ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            var total = 0
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                total += count
                require(total <= MAX_FILE_BYTES) { "Cada archivo debe pesar como máximo 512 KB." }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
        val text = String(bytes, Charsets.UTF_8)
        require(text.isNotBlank()) { "El archivo está vacío." }
        require(!text.contains('\u0000')) { "El archivo no parece ser texto UTF-8 válido." }
        val folder = directory(context)
        val used = folder.listFiles()?.filter { it.isFile }?.sumOf { it.length() } ?: 0L
        require(used + bytes.size <= MAX_TOTAL_BYTES) { "La biblioteca alcanzó el límite local de 5 MB." }

        val id = java.util.UUID.randomUUID().toString()
        val stored = File(folder, "$id.txt")
        stored.writeBytes(bytes)
        File(folder, "$id.name").writeText(safeName, Charsets.UTF_8)
        return KnowledgeDocument(id, safeName, bytes.size.toLong(), stored.lastModified())
    }

    fun list(context: Context): List<KnowledgeDocument> {
        val folder = directory(context)
        return folder.listFiles { file -> file.extension == "txt" }?.mapNotNull { file ->
            try {
                val id = file.nameWithoutExtension
                val nameFile = File(folder, "$id.name")
                KnowledgeDocument(id, if (nameFile.exists()) nameFile.readText(Charsets.UTF_8) else file.name, file.length(), file.lastModified())
            } catch (_: Exception) { null }
        }?.sortedBy { it.name.lowercase(Locale.ROOT) } ?: emptyList()
    }

    fun read(context: Context, id: String): String {
        require(id.matches(Regex("[a-f0-9-]{36}"))) { "Identificador de documento no válido." }
        val file = File(directory(context), "$id.txt")
        require(file.exists() && file.isFile) { "El documento ya no está disponible." }
        return file.readText(Charsets.UTF_8)
    }

    fun search(context: Context, query: String, limit: Int = 20): List<KnowledgeMatch> {
        val terms = query.trim().lowercase(Locale.ROOT).split(Regex("\\s+")).filter { it.length >= 2 }.distinct()
        if (terms.isEmpty()) return emptyList()
        return list(context).mapNotNull { doc ->
            val body = try { read(context, doc.id) } catch (_: Exception) { return@mapNotNull null }
            val lower = body.lowercase(Locale.ROOT)
            val hits = terms.count { lower.contains(it) }
            if (hits == 0) null else {
                val first = terms.firstOrNull { lower.contains(it) } ?: terms.first()
                val at = lower.indexOf(first).coerceAtLeast(0)
                val start = (at - 100).coerceAtLeast(0)
                val end = (at + 260).coerceAtMost(body.length)
                KnowledgeMatch(doc, body.substring(start, end).replace(Regex("\\s+"), " ").trim())
            }
        }.sortedByDescending { match -> terms.count { match.excerpt.lowercase(Locale.ROOT).contains(it) } }
            .take(limit)
    }

    /**
     * Answers simple questions from explicit "key: value" entries in the local library.
     * This is deterministic local lookup, not an AI-generated answer.
     */
    fun answerQuestion(context: Context, question: String): String? {
        fun normalize(value: String): String = java.text.Normalizer.normalize(
            value.lowercase(Locale.ROOT), java.text.Normalizer.Form.NFD
        ).replace(Regex("\\p{M}+"), "").replace(Regex("[^\\p{L}\\p{N} ]+"), " ")
            .replace(Regex("\\s+"), " ").trim()

        val q = normalize(question)
        if (q.isBlank()) return null
        val aliases = mapOf(
            "nombre" to setOf("nombre", "llamo", "llamas", "llamado", "llamada"),
            "edad" to setOf("edad", "anos"),
            "ciudad" to setOf("ciudad", "vivo", "vives", "resido"),
            "pais" to setOf("pais", "nacionalidad"),
            "cumpleanos" to setOf("cumpleanos", "naci", "nacimiento")
        )
        val stopWords = setOf("cual", "cuales", "que", "quien", "como", "cuando", "donde", "es", "soy", "mi", "mis", "me", "el", "la", "los", "las", "de", "del", "por", "favor", "dime", "sabes", "recuerdas")
        data class Candidate(val key: String, val value: String, val score: Int)
        val candidates = mutableListOf<Candidate>()

        list(context).forEach documentLoop@ { document ->
            val body = runCatching { read(context, document.id) }.getOrNull() ?: return@documentLoop
            body.lineSequence().forEach lineLoop@ { line ->
                val split = line.split(":", limit = 2)
                if (split.size != 2) return@lineLoop
                val key = split[0].trim()
                val value = split[1].trim()
                if (key.isBlank() || value.isBlank() || key.length > 60) return@lineLoop
                val normalizedKey = normalize(key)
                val keyWords = normalizedKey.split(" ").filter { it.isNotBlank() }
                val keyAliases = aliases[normalizedKey].orEmpty()
                val exact = q.contains(normalizedKey)
                val aliasMatch = keyAliases.any { q.split(" ").contains(it) }
                val allKeyWords = keyWords.isNotEmpty() && keyWords.all { q.split(" ").contains(it) }
                val questionWords = q.split(" ").filter { it.length > 1 && it !in stopWords }
                val overlap = questionWords.count { word -> keyWords.any { it == word || it.startsWith(word) || word.startsWith(it) } }
                val score = when {
                    exact -> 100 + keyWords.size
                    aliasMatch -> 90
                    allKeyWords -> 80 + keyWords.size
                    overlap > 0 -> 40 + overlap
                    else -> 0
                }
                if (score > 0) candidates.add(Candidate(key, value, score))
            }
        }
        val best = candidates.maxByOrNull { it.score } ?: return null
        return if (normalize(best.key) == "nombre") "Tu nombre es ${best.value}."
        else "Según tu Biblioteca, ${best.key}: ${best.value}."
    }

    fun remove(context: Context, id: String): Boolean {
        require(id.matches(Regex("[a-f0-9-]{36}"))) { "Identificador de documento no válido." }
        val folder = directory(context)
        File(folder, "$id.name").delete()
        return File(folder, "$id.txt").delete()
    }
}
