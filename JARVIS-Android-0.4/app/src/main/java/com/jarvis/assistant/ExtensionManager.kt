package com.jarvis.assistant

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.io.InputStream

data class JarvisExtension(
    val id: String,
    val name: String,
    val version: String,
    val description: String,
    val capabilities: List<String>,
    val enabled: Boolean,
    val fileName: String
)

object ExtensionManager {
    private const val MAX_BYTES = 512 * 1024
    private val allowedCapabilities = setOf("knowledge.search", "tasks.create")

    private fun directory(context: Context): File = File(context.filesDir, "jarvis_extensions").apply { mkdirs() }

    fun import(context: Context, input: InputStream): JarvisExtension {
        val bytes = input.use { stream ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            var total = 0
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                total += count
                require(total <= MAX_BYTES) { "El archivo supera el límite de 512 KB." }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
        val json = JSONObject(String(bytes, Charsets.UTF_8))
        require(json.optString("format") == "jarvis-extension") { "Formato no reconocido." }
        require(json.optInt("formatVersion", -1) == 1) { "Versión de formato no compatible." }
        val id = json.optString("id")
        require(Regex("[a-z0-9]+([.-][a-z0-9]+)+").matches(id)) { "El ID debe usar minúsculas y puntos o guiones." }
        val name = json.optString("name").trim()
        require(name.isNotEmpty() && name.length <= 80) { "El nombre debe tener entre 1 y 80 caracteres." }
        val version = json.optString("version")
        require(Regex("[0-9]+\\.[0-9]+\\.[0-9]+").matches(version)) { "La versión debe tener formato 1.0.0." }
        val description = json.optString("description").trim()
        require(description.length <= 300) { "La descripción es demasiado larga." }
        val capsJson = json.optJSONArray("capabilities") ?: org.json.JSONArray()
        val caps = (0 until capsJson.length()).map { capsJson.getString(it) }.distinct()
        require(caps.all { it in allowedCapabilities }) { "La extensión solicita un permiso no admitido." }
        val body = json.optJSONObject("content") ?: throw IllegalArgumentException("Falta el objeto content.")
        require(body.optString("instructions").isNotBlank()) { "Faltan las instrucciones." }
        val knowledge = body.optJSONArray("knowledge") ?: org.json.JSONArray()
        require(knowledge.length() <= 200) { "La extensión contiene demasiadas entradas de conocimiento." }
        val safeName = id.replace(Regex("[^a-z0-9.-]"), "_") + ".json"
        val target = File(directory(context), safeName)
        require(!target.exists()) { "Ya existe una extensión con ese ID." }
        json.put("enabled", false)
        target.writeText(json.toString(2), Charsets.UTF_8)
        return JarvisExtension(id, name, version, description, caps, false, safeName)
    }

    fun list(context: Context): List<JarvisExtension> = directory(context).listFiles { file -> file.extension == "json" }
        ?.mapNotNull { file -> try {
            val json = JSONObject(file.readText(Charsets.UTF_8))
            val caps = json.optJSONArray("capabilities") ?: org.json.JSONArray()
            JarvisExtension(json.optString("id"), json.optString("name"), json.optString("version"), json.optString("description"), (0 until caps.length()).map { caps.getString(it) }, json.optBoolean("enabled", false), file.name)
        } catch (_: Exception) { null } }?.sortedBy { it.name } ?: emptyList()

    fun setEnabled(context: Context, extension: JarvisExtension, enabled: Boolean) {
        val file = File(directory(context), extension.fileName)
        val json = JSONObject(file.readText(Charsets.UTF_8))
        json.put("enabled", enabled)
        file.writeText(json.toString(2), Charsets.UTF_8)
    }

    fun remove(context: Context, extension: JarvisExtension): Boolean = File(directory(context), extension.fileName).delete()
}
