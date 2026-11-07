package com.jarvis.assistant

import android.content.Context
import java.util.Locale

data class InvestigationHit(
    val document: KnowledgeDocument,
    val lineNumber: Int,
    val excerpt: String,
    val score: Int
)

data class InvestigationReport(
    val query: String,
    val documentsScanned: Int,
    val hits: List<InvestigationHit>,
    val matchedDocuments: Int
)

/**
 * Local, read-only investigation across imported text/code files.
 * It never executes files, follows links, or changes source documents.
 */
object LibraryInvestigator {
    fun investigate(context: Context, query: String, maxHits: Int = 30): InvestigationReport {
        val cleanQuery = query.trim()
        require(cleanQuery.isNotEmpty()) { "Escribe qué quieres investigar." }
        require(cleanQuery.length <= 160) { "La búsqueda es demasiado larga (máximo 160 caracteres)." }
        val terms = cleanQuery.lowercase(Locale.ROOT)
            .split(Regex("[^\\p{L}\\p{N}_]+"))
            .filter { it.length >= 2 }
            .distinct()
        require(terms.isNotEmpty()) { "La búsqueda debe contener palabras de al menos dos caracteres." }

        val documents = KnowledgeLibrary.list(context)
        val hits = mutableListOf<InvestigationHit>()
        val matchedIds = mutableSetOf<String>()

        documents.forEach { document ->
            val text = try { KnowledgeLibrary.read(context, document.id) } catch (_: Exception) { return@forEach }
            text.lineSequence().forEachIndexed { index, line ->
                val lower = line.lowercase(Locale.ROOT)
                val count = terms.count { lower.contains(it) }
                if (count > 0) {
                    val phraseBonus = if (lower.contains(cleanQuery.lowercase(Locale.ROOT))) 5 else 0
                    val score = count * 3 + phraseBonus
                    matchedIds.add(document.id)
                    val excerpt = line.trim().take(500)
                    hits.add(InvestigationHit(document, index + 1, excerpt, score))
                }
            }
        }

        return InvestigationReport(
            query = cleanQuery,
            documentsScanned = documents.size,
            hits = hits.sortedWith(compareByDescending<InvestigationHit> { it.score }.thenBy { it.document.name }.thenBy { it.lineNumber }).take(maxHits),
            matchedDocuments = matchedIds.size
        )
    }
}
