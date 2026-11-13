package com.jarvis.assistant

import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Free, keyless public-web research using Wikimedia's official MediaWiki API.
 * This is a focused encyclopedia source, not a universal search engine.
 */
object JarvisFreeWebResearchProvider : JarvisResearchProvider {
    override val displayName: String = "Wikipedia / MediaWiki (gratuito)"

    override suspend fun search(query: String, maxResults: Int): List<JarvisResearchPage> =
        withContext(Dispatchers.IO) {
            val cleanQuery = query.trim().take(240)
            if (cleanQuery.length < 2) return@withContext emptyList()

            val endpoint = Uri.parse("https://es.wikipedia.org/w/api.php")
                .buildUpon()
                .appendQueryParameter("action", "query")
                .appendQueryParameter("generator", "search")
                .appendQueryParameter("gsrsearch", cleanQuery)
                .appendQueryParameter("gsrlimit", maxResults.coerceIn(1, 5).toString())
                .appendQueryParameter("prop", "extracts")
                .appendQueryParameter("explaintext", "1")
                .appendQueryParameter("exintro", "1")
                .appendQueryParameter("exchars", "5000")
                .appendQueryParameter("format", "json")
                .appendQueryParameter("formatversion", "2")
                .build()
                .toString()

            val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 9000
                readTimeout = 12000
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", "JARVIS-Android/3.13 (educational personal assistant)")
                instanceFollowRedirects = false
            }

            try {
                val status = connection.responseCode
                if (status !in 200..299) return@withContext emptyList()
                val body = connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                    val output = StringBuilder()
                    val buffer = CharArray(4096)
                    var total = 0
                    while (true) {
                        val count = reader.read(buffer)
                        if (count < 0) break
                        total += count
                        if (total > 180_000) throw IllegalStateException("La respuesta web superó el límite permitido.")
                        output.append(buffer, 0, count)
                    }
                    output.toString()
                }
                val root = JSONObject(body)
                val pages = root.optJSONObject("query")?.optJSONArray("pages") ?: return@withContext emptyList()
                buildList {
                    for (index in 0 until pages.length()) {
                        val page = pages.optJSONObject(index) ?: continue
                        val title = page.optString("title").trim()
                        val extract = page.optString("extract").trim()
                        val pageId = page.optLong("pageid", -1L)
                        if (title.isBlank() || extract.length < 80 || pageId <= 0L) continue
                        add(
                            JarvisResearchPage(
                                title = title.take(180),
                                url = "https://es.wikipedia.org/?curid=$pageId",
                                extractedText = extract.take(5000)
                            )
                        )
                    }
                }
            } finally {
                connection.disconnect()
            }
        }
}
