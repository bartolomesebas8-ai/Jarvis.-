package com.jarvis.assistant

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale
import java.util.UUID

data class JarvisLearningRecord(
    val id: String, val title: String, val topic: String, val content: String,
    val sourceType: String, val sourceUrl: String, val sourceTitle: String,
    val learnedAt: Long, val verifiedAt: Long, val confidence: Double, val checksum: String
)
data class JarvisLearningMatch(val record: JarvisLearningRecord, val score: Int, val excerpt: String)
data class JarvisLearningMessage(val role: String, val text: String, val timestamp: Long)
data class JarvisLearningSession(
    val id: String, val title: String, val teacher: String, val status: String,
    val createdAt: Long, val updatedAt: Long, val messages: List<JarvisLearningMessage>
)
data class JarvisLearningStats(val records: Int, val sessions: Int, val totalBytes: Long, val lastLearnedAt: Long)

/** Contract for a future local or remote teacher; no paid API is assumed. */
interface JarvisLearningTeacher {
    val displayName: String
    suspend fun teach(topic: String, conversation: List<JarvisLearningMessage>): String
}

/** Search-provider contract. Every returned page must retain its source URL. */
interface JarvisResearchProvider {
    val displayName: String
    suspend fun search(query: String, maxResults: Int): List<JarvisResearchPage>
}
data class JarvisResearchPage(val title: String, val url: String, val extractedText: String)

/**
 * Persistent learning layer. Stores lessons and research with provenance,
 * deduplicates content, retrieves useful notes, and records teacher sessions.
 * It never executes downloaded code or treats web text as trusted instructions.
 */
object JarvisLearningEngine {
    private const val MAX_RECORDS = 3000
    private const val MAX_RECORD_BYTES = 1_000_000
    private const val MAX_TOTAL_BYTES = 64L * 1024L * 1024L
    private const val MAX_SESSIONS = 100
    private const val MAX_MESSAGES_PER_SESSION = 500
    private const val KNOWLEDGE_FILE = "knowledge.json"
    private const val SESSIONS_FILE = "sessions.json"
    private val lock = Any()

    private fun directory(context: Context): File =
        File(context.applicationContext.filesDir, "jarvis_learning").apply {
            if (!exists() && !mkdirs()) throw IllegalStateException("No se pudo crear la biblioteca de aprendizaje de JARVIS.")
        }
    private fun knowledgeFile(context: Context) = File(directory(context), KNOWLEDGE_FILE)
    private fun sessionsFile(context: Context) = File(directory(context), SESSIONS_FILE)

    private fun normalize(value: String): String = Normalizer.normalize(
        value.lowercase(Locale.ROOT), Normalizer.Form.NFD
    ).replace(Regex("\\p{M}+"), "")
        .replace(Regex("[^\\p{L}\\p{N} ]+"), " ")
        .replace(Regex("\\s+"), " ").trim()

    private fun checksum(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private fun writeAtomically(target: File, content: String) {
        val temporary = File(target.parentFile, target.name + ".tmp")
        temporary.writeText(content, Charsets.UTF_8)
        if (target.exists() && !target.delete()) {
            temporary.delete()
            throw IllegalStateException("No se pudo actualizar la biblioteca de aprendizaje.")
        }
        if (!temporary.renameTo(target)) {
            temporary.delete()
            throw IllegalStateException("No se pudo guardar la biblioteca de aprendizaje.")
        }
    }

    private fun readRecords(context: Context): MutableList<JarvisLearningRecord> {
        val file = knowledgeFile(context)
        if (!file.isFile) return mutableListOf()
        return runCatching {
            val array = JSONArray(file.readText(Charsets.UTF_8))
            (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                val title = item.optString("title").trim()
                val body = item.optString("content")
                if (title.isBlank() || body.isBlank()) return@mapNotNull null
                JarvisLearningRecord(
                    item.optString("id", UUID.randomUUID().toString()), title.take(180),
                    item.optString("topic", "").take(120), body,
                    item.optString("sourceType", "unknown").take(30),
                    item.optString("sourceUrl", "").take(2048),
                    item.optString("sourceTitle", "").take(180),
                    item.optLong("learnedAt", 0L), item.optLong("verifiedAt", 0L),
                    item.optDouble("confidence", 0.4).coerceIn(0.0, 1.0),
                    item.optString("checksum", checksum(body))
                )
            }.toMutableList()
        }.getOrDefault(mutableListOf())
    }

    private fun recordJson(record: JarvisLearningRecord) = JSONObject()
        .put("id", record.id).put("title", record.title).put("topic", record.topic)
        .put("content", record.content).put("sourceType", record.sourceType)
        .put("sourceUrl", record.sourceUrl).put("sourceTitle", record.sourceTitle)
        .put("learnedAt", record.learnedAt).put("verifiedAt", record.verifiedAt)
        .put("confidence", record.confidence).put("checksum", record.checksum)

    private fun persistRecords(context: Context, records: List<JarvisLearningRecord>) {
        val json = JSONArray()
        records.forEach { json.put(recordJson(it)) }
        val serialized = json.toString()
        writeAtomically(knowledgeFile(context), serialized)
        runCatching { JarvisSharedStorage.saveSnapshot("Aprendizaje", KNOWLEDGE_FILE, serialized) }
    }

    /** Saves a lesson or researched page, preserving provenance and deduplicating exact content. */
    fun addKnowledge(
        context: Context, title: String, topic: String, content: String,
        sourceType: String = "user", sourceUrl: String = "", sourceTitle: String = "",
        confidence: Double = 0.75, verifiedAt: Long = 0L
    ): JarvisLearningRecord = synchronized(lock) {
        val cleanTitle = title.trim().take(180)
        val cleanTopic = topic.trim().take(120)
        val cleanContent = content.trim()
        require(cleanTitle.isNotBlank()) { "El conocimiento necesita un título." }
        require(cleanContent.isNotBlank()) { "No hay contenido para aprender." }
        require(cleanContent.toByteArray(Charsets.UTF_8).size <= MAX_RECORD_BYTES) {
            "Cada conocimiento debe pesar como máximo 1 MB."
        }
        val cleanUrl = sourceUrl.trim().take(2048)
        if (cleanUrl.isNotBlank()) {
            val parsed = android.net.Uri.parse(cleanUrl)
            require(parsed.scheme == "https" && !parsed.host.isNullOrBlank()) {
                "La fuente web debe ser una dirección HTTPS válida."
            }
        }
        val digest = checksum(cleanContent)
        val records = readRecords(context)
        val existing = records.firstOrNull { it.checksum == digest }
        if (existing != null) return@synchronized existing
        val currentBytes = records.sumOf { it.content.toByteArray(Charsets.UTF_8).size.toLong() }
        require(records.size < MAX_RECORDS) { "La biblioteca alcanzó el límite de " + MAX_RECORDS + " conocimientos." }
        require(currentBytes + cleanContent.toByteArray(Charsets.UTF_8).size <= MAX_TOTAL_BYTES) {
            "La biblioteca de aprendizaje alcanzó su límite local de 64 MB."
        }
        val now = System.currentTimeMillis()
        val record = JarvisLearningRecord(
            UUID.randomUUID().toString(), cleanTitle, cleanTopic, cleanContent,
            sourceType.trim().take(30).ifBlank { "unknown" }, cleanUrl,
            sourceTitle.trim().take(180), now, verifiedAt,
            confidence.coerceIn(0.0, 1.0), digest
        )
        records.add(record)
        persistRecords(context, records)
        record
    }

    fun search(context: Context, query: String, limit: Int = 8): List<JarvisLearningMatch> {
        val terms = normalize(query).split(" ").filter { it.length >= 2 }.distinct()
        if (terms.isEmpty()) return emptyList()
        return synchronized(lock) {
            readRecords(context).mapNotNull { record ->
                val searchable = normalize(record.title + " " + record.topic + " " + record.content)
                val titleText = normalize(record.title + " " + record.topic)
                val hits = terms.count { searchable.contains(it) }
                if (hits == 0) null else {
                    val titleHits = terms.count { titleText.contains(it) }
                    val exactPhrase = if (searchable.contains(normalize(query))) 8 else 0
                    val score = hits * 3 + titleHits * 5 + exactPhrase
                    val firstTerm = terms.firstOrNull { searchable.contains(it) }
                    val originalAt = if (firstTerm == null) 0 else normalize(record.content).indexOf(firstTerm).coerceAtLeast(0)
                    val start = (originalAt - 160).coerceAtLeast(0)
                    val end = (originalAt + 420).coerceAtMost(record.content.length)
                    JarvisLearningMatch(
                        record, score,
                        record.content.substring(start, end).replace(Regex("\\s+"), " ").trim()
                    )
                }
            }.sortedWith(compareByDescending<JarvisLearningMatch> { it.score }.thenByDescending { it.record.learnedAt })
                .take(limit.coerceIn(1, 20))
        }
    }

    /** Relevant excerpts that can be appended to a conversation prompt. */
    fun contextFor(context: Context, query: String, maxChars: Int = 6000): String {
        val matches = search(context, query, 6)
        if (matches.isEmpty()) return ""
        return buildString {
            appendLine("CONOCIMIENTOS APRENDIDOS Y GUARDADOS (usa solo lo pertinente; conserva las fuentes):")
            for (match in matches) {
                val source = match.record.sourceUrl.takeIf { it.isNotBlank() } ?: match.record.sourceType
                val block = "• " + match.record.title + " [tema: " + match.record.topic +
                    "; fuente: " + source + "; confianza: " +
                    "%.2f".format(Locale.ROOT, match.record.confidence) + "]\n" + match.excerpt + "\n"
                if (length + block.length > maxChars) break
                append(block)
            }
        }.trim()
    }

    fun allRecords(context: Context): List<JarvisLearningRecord> =
        synchronized(lock) { readRecords(context).sortedByDescending { it.learnedAt } }

    fun stats(context: Context): JarvisLearningStats = synchronized(lock) {
        val records = readRecords(context)
        val sessions = readSessions(context)
        JarvisLearningStats(
            records.size, sessions.size,
            records.sumOf { it.content.toByteArray(Charsets.UTF_8).size.toLong() },
            records.maxOfOrNull { it.learnedAt } ?: 0L
        )
    }

    private fun readSessions(context: Context): MutableList<JarvisLearningSession> {
        val file = sessionsFile(context)
        if (!file.isFile) return mutableListOf()
        return runCatching {
            val array = JSONArray(file.readText(Charsets.UTF_8))
            (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                val messagesArray = item.optJSONArray("messages") ?: JSONArray()
                val messages = (0 until messagesArray.length()).mapNotNull { messageIndex ->
                    val message = messagesArray.optJSONObject(messageIndex) ?: return@mapNotNull null
                    JarvisLearningMessage(
                        message.optString("role", "unknown").take(20),
                        message.optString("text", "").take(12000),
                        message.optLong("timestamp", 0L)
                    )
                }
                JarvisLearningSession(
                    item.optString("id", UUID.randomUUID().toString()),
                    item.optString("title", "Sesión de aprendizaje").take(180),
                    item.optString("teacher", "sin conectar").take(100),
                    item.optString("status", "paused").take(20),
                    item.optLong("createdAt", 0L), item.optLong("updatedAt", 0L), messages
                )
            }.toMutableList()
        }.getOrDefault(mutableListOf())
    }

    private fun persistSessions(context: Context, sessions: List<JarvisLearningSession>) {
        val array = JSONArray()
        sessions.forEach { session ->
            val messages = JSONArray()
            session.messages.forEach { message ->
                messages.put(JSONObject().put("role", message.role).put("text", message.text).put("timestamp", message.timestamp))
            }
            array.put(JSONObject().put("id", session.id).put("title", session.title)
                .put("teacher", session.teacher).put("status", session.status)
                .put("createdAt", session.createdAt).put("updatedAt", session.updatedAt)
                .put("messages", messages))
        }
        val serialized = array.toString()
        writeAtomically(sessionsFile(context), serialized)
        runCatching { JarvisSharedStorage.saveSnapshot("Aprendizaje", SESSIONS_FILE, serialized) }
    }

    fun startSession(context: Context, title: String, teacher: String): JarvisLearningSession = synchronized(lock) {
        val sessions = readSessions(context)
        val now = System.currentTimeMillis()
        val session = JarvisLearningSession(
            UUID.randomUUID().toString(), title.trim().take(180).ifBlank { "Formación inicial de JARVIS" },
            teacher.trim().take(100).ifBlank { "profesor pendiente" }, "active", now, now, emptyList()
        )
        sessions.add(session)
        persistSessions(context, sessions.takeLast(MAX_SESSIONS))
        session
    }

    fun appendSessionMessage(context: Context, sessionId: String, role: String, text: String): JarvisLearningSession =
        synchronized(lock) {
            require(text.isNotBlank()) { "El mensaje de aprendizaje está vacío." }
            val sessions = readSessions(context)
            val index = sessions.indexOfFirst { it.id == sessionId }
            require(index >= 0) { "No se encontró la sesión de aprendizaje." }
            val previous = sessions[index]
            require(previous.messages.size < MAX_MESSAGES_PER_SESSION) {
                "La sesión alcanzó el límite de mensajes; inicia otra sesión para continuar."
            }
            val updated = previous.copy(
                updatedAt = System.currentTimeMillis(),
                messages = previous.messages + JarvisLearningMessage(
                    role.trim().take(20).ifBlank { "unknown" }, text.trim().take(12000), System.currentTimeMillis()
                )
            )
            sessions[index] = updated
            persistSessions(context, sessions.takeLast(MAX_SESSIONS))
            updated
        }

    fun finishSession(context: Context, sessionId: String, status: String = "completed") = synchronized(lock) {
        require(status in setOf("completed", "paused", "failed")) { "Estado de sesión no válido." }
        val sessions = readSessions(context)
        val index = sessions.indexOfFirst { it.id == sessionId }
        require(index >= 0) { "No se encontró la sesión de aprendizaje." }
        sessions[index] = sessions[index].copy(status = status, updatedAt = System.currentTimeMillis())
        persistSessions(context, sessions.takeLast(MAX_SESSIONS))
    }

    fun sessions(context: Context): List<JarvisLearningSession> =
        synchronized(lock) { readSessions(context).sortedByDescending { it.updatedAt } }
}
