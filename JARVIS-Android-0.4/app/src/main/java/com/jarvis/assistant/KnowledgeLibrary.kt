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
     * Retrieves relevant information from the user's saved documents, saved memories,
     * and previous chat turns. This is local keyword retrieval, not an AI model.
     */
    fun answerQuestion(context: Context, question: String): String? {
        fun normalize(value: String): String = java.text.Normalizer.normalize(
            value.lowercase(Locale.ROOT), java.text.Normalizer.Form.NFD
        ).replace(Regex("\\p{M}+"), "")
            .replace(Regex("[^\\p{L}\\p{N} ]+"), " ")
            .replace(Regex("\\s+"), " ").trim()

        val stopWords = setOf(
            "cual", "cuales", "que", "quien", "como", "cuando", "donde", "por", "para",
            "es", "son", "soy", "eres", "mi", "mis", "me", "yo", "tu", "tus", "el", "la",
            "los", "las", "de", "del", "un", "una", "en", "con", "sobre", "favor",
            "dime", "sabes", "recuerdas", "dijiste", "contaste", "puedes", "podrias"
        )
        val synonyms = mapOf(
            "gusta" to setOf("gustan", "favorito", "favorita", "prefiero", "prefieres"),
            "gustan" to setOf("gusta", "favorito", "favorita", "prefiero"),
            "prefiero" to setOf("gusta", "gustan", "favorito", "favorita"),
            "vivo" to setOf("vives", "resido", "resides", "vivienda", "casa", "ciudad", "residencia"),
            "vives" to setOf("vivo", "resido", "resides", "vivienda", "casa", "ciudad", "residencia")
        )
        val normalizedQuestion = normalize(question)
        val questionWords = normalizedQuestion.split(" ")
            .filter { it.length > 1 && it !in stopWords }.distinct()
        if (questionWords.isEmpty()) return null

        fun scoreAgainst(text: String): Int {
            val normalizedText = normalize(text)
            val words = normalizedText.split(" ").filter { it.isNotBlank() }.toSet()
            val hits = questionWords.count { queryWord ->
                queryWord in words ||
                    words.any { it.startsWith(queryWord) || queryWord.startsWith(it) } ||
                    synonyms[queryWord].orEmpty().any { it in words }
            }
            val phraseBonus = if (normalizedText.contains(normalizedQuestion)) 30 else 0
            return hits * 20 + phraseBonus
        }

        data class Candidate(val score: Int, val priority: Int, val answer: String)
        val candidates = mutableListOf<Candidate>()

        // Structured facts and imported documents.
        list(context).forEach documentLoop@ { document ->
            val body = runCatching { read(context, document.id) }.getOrNull() ?: return@documentLoop
            body.lineSequence().forEach lineLoop@ { line ->
                val split = line.split(":", limit = 2)
                if (split.size != 2) return@lineLoop
                val key = split[0].trim()
                val value = split[1].trim()
                if (key.isBlank() || value.isBlank() || key.length > 100) return@lineLoop
                val score = scoreAgainst(key)
                if (score > 0) candidates.add(Candidate(score + 15, 3, value))
            }
            val docScore = scoreAgainst(document.name)
            if (docScore > 0) {
                val excerpt = body.replace(Regex("\\s+"), " ").trim().take(500)
                if (excerpt.isNotBlank()) candidates.add(Candidate(docScore, 1, excerpt))
            }
        }

        // Manually saved memories.
        val prefs = context.getSharedPreferences("jarvis_local_memory", Context.MODE_PRIVATE)
        val memoriesRaw = prefs.getString("saved_memories", null)
        if (!memoriesRaw.isNullOrBlank()) {
            runCatching {
                val array = org.json.JSONArray(memoriesRaw)
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i)
                    if (item != null) {
                        val savedText = item.optString("text", "").trim()
                        if (savedText.isNotBlank()) {
                            val score = scoreAgainst(savedText)
                            if (score > 0) candidates.add(Candidate(score + 10, 2, savedText))
                        }
                    }
                }
            }
        }

        // Previous user/assistant exchanges are already persisted locally as chat history.
        val historyRaw = prefs.getString("chat_history", null)
        if (!historyRaw.isNullOrBlank()) {
            runCatching {
                val array = org.json.JSONArray(historyRaw)
                var previousUser: String? = null
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i)
                    if (item != null) {
                        val who = item.optString("who", "")
                        val text = item.optString("text", "").trim()
                        if (who == "TÚ") {
                            previousUser = text
                        } else if (who == "JARVIS" && !previousUser.isNullOrBlank() && text.isNotBlank()) {
                            val score = scoreAgainst(previousUser.orEmpty())
                            if (score > 0) candidates.add(Candidate(score, 1, text))
                            previousUser = null
                        }
                    }
                }
            }
        }

        val minimumScore = if (questionWords.size == 1) 20 else 25
        return candidates
            .filter { it.score >= minimumScore && it.answer.isNotBlank() }
            .maxWithOrNull(compareBy<Candidate> { it.score }.thenBy { it.priority })
            ?.answer
    }

    fun remove(context: Context, id: String): Boolean {
        require(id.matches(Regex("[a-f0-9-]{36}"))) { "Identificador de documento no válido." }
        val folder = directory(context)
        File(folder, "$id.name").delete()
        return File(folder, "$id.txt").delete()
    }
}
