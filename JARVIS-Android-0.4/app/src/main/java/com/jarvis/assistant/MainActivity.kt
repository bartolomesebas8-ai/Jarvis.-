package com.jarvis.assistant

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import android.util.Base64
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class Line(val who: String, val text: String, val timestamp: Long = System.currentTimeMillis())

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { Jarvis() }
    }
}

private const val PREFS = "jarvis_local_memory"
private const val HISTORY = "chat_history"
private const val MEMORIES = "saved_memories"
private const val TASKS = "jarvis_tasks"
private const val SECURITY_SALT = "security_pin_salt"
private const val SECURITY_HASH = "security_pin_hash"
private fun hashPin(pin: String, salt: ByteArray): ByteArray { val spec = PBEKeySpec(pin.toCharArray(), salt, 120000, 256); return try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded } finally { spec.clearPassword() } }
private fun saveSecurityPin(context: android.content.Context, pin: String) { val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }; context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE).edit().putString(SECURITY_SALT, Base64.encodeToString(salt, Base64.NO_WRAP)).putString(SECURITY_HASH, Base64.encodeToString(hashPin(pin, salt), Base64.NO_WRAP)).commit() }
private fun hasSecurityPin(context: android.content.Context): Boolean = context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE).contains(SECURITY_HASH)
private fun verifySecurityPin(context: android.content.Context, pin: String): Boolean {
    return try {
        val prefs = context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
        val salt = Base64.decode(prefs.getString(SECURITY_SALT, null) ?: return false, Base64.NO_WRAP)
        val expected = Base64.decode(prefs.getString(SECURITY_HASH, null) ?: return false, Base64.NO_WRAP)
        java.security.MessageDigest.isEqual(expected, hashPin(pin, salt))
    } catch (_: Exception) { false }
}
private val MEMORY_CATEGORIES = listOf("Personal", "Proyectos", "Estudios", "Metas", "Temporal")
data class MemoryItem(val category: String, val text: String)
data class JarvisTask(val id: String, val title: String, val dueDate: String, val priority: String, val completed: Boolean = false)
private fun loadTasks(context: android.content.Context): List<JarvisTask> {
    val raw = context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE).getString(TASKS, null) ?: return emptyList()
    return try { val a = JSONArray(raw); (0 until a.length()).mapNotNull { i ->
        val o = a.optJSONObject(i) ?: return@mapNotNull null
        val title = o.optString("title", "").trim()
        if (title.isBlank()) null else JarvisTask(o.optString("id", java.util.UUID.randomUUID().toString()), title.take(160), o.optString("dueDate", "").take(40), o.optString("priority", "Media").let { if (it in listOf("Alta", "Media", "Baja")) it else "Media" }, o.optBoolean("completed", false))
    } } catch (_: Exception) { emptyList() }
}

private class JarvisReminderWorker(context: android.content.Context, params: androidx.work.WorkerParameters) : androidx.work.Worker(context, params) {
    override fun doWork(): Result {
        val title = inputData.getString("taskTitle") ?: "Tarea pendiente"
        val manager = applicationContext.getSystemService(android.content.Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            val channel = android.app.NotificationChannel("jarvis_reminders", "Recordatorios de JARVIS", android.app.NotificationManager.IMPORTANCE_DEFAULT)
            manager.createNotificationChannel(channel)
        }
        val intent = android.content.Intent(applicationContext, MainActivity::class.java)
        val pending = android.app.PendingIntent.getActivity(applicationContext, id.hashCode(), intent, android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE)
        if (android.os.Build.VERSION.SDK_INT >= 33 && androidx.core.content.ContextCompat.checkSelfPermission(applicationContext, android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) return Result.success()
        val notification = androidx.core.app.NotificationCompat.Builder(applicationContext, "jarvis_reminders")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Recordatorio de JARVIS")
            .setContentText(title)
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()
        manager.notify(id.hashCode(), notification)
        return Result.success()
    }
}

private fun scheduleJarvisReminder(context: android.content.Context, task: JarvisTask) {
    if (task.completed || task.dueDate.isBlank()) return
    val formats = listOf(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"), java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"))
    val trigger = formats.firstNotNullOfOrNull { formatter -> runCatching { java.time.LocalDateTime.parse(task.dueDate.trim(), formatter).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli() }.getOrNull() } ?: return
    val delay = trigger - System.currentTimeMillis()
    if (delay <= 0L) return
    val request = androidx.work.OneTimeWorkRequestBuilder<JarvisReminderWorker>().setInitialDelay(delay, java.util.concurrent.TimeUnit.MILLISECONDS).setInputData(androidx.work.workDataOf("taskTitle" to task.title)).addTag("jarvis_reminder_" + task.id).build()
    androidx.work.WorkManager.getInstance(context).enqueueUniqueWork("jarvis_reminder_" + task.id, androidx.work.ExistingWorkPolicy.REPLACE, request)
}

private fun cancelJarvisReminder(context: android.content.Context, taskId: String) {
    androidx.work.WorkManager.getInstance(context).cancelUniqueWork("jarvis_reminder_" + taskId)
}
private fun taskLocalDate(value: String): java.time.LocalDate? {
    val text = value.trim()
    val formats = listOf("yyyy-MM-dd HH:mm", "dd/MM/yyyy HH:mm", "yyyy-MM-dd", "dd/MM/yyyy")
    return formats.firstNotNullOfOrNull { pattern ->
        runCatching { java.time.LocalDateTime.parse(text, java.time.format.DateTimeFormatter.ofPattern(pattern)).toLocalDate() }.getOrElse {
            runCatching { java.time.LocalDate.parse(text, java.time.format.DateTimeFormatter.ofPattern(pattern)) }.getOrNull()
        }
    }
}

data class JarvisGoal(val id: String, val title: String, val category: String, val progress: Float = 0f, val taskIds: List<String> = emptyList())
private const val GOALS_KEY = "jarvis_goals"
private fun loadGoals(context: android.content.Context): List<JarvisGoal> {
    val raw = context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE).getString(GOALS_KEY, null) ?: return emptyList()
    return try {
        val array = JSONArray(raw)
        (0 until array.length()).mapNotNull { i ->
            val item = array.optJSONObject(i) ?: return@mapNotNull null
            val title = item.optString("title", "").trim()
            if (title.isBlank()) null else {
                val ids = item.optJSONArray("taskIds")
                JarvisGoal(item.optString("id", java.util.UUID.randomUUID().toString()), title.take(120), item.optString("category", "Personal").take(40), item.optDouble("progress", 0.0).toFloat().coerceIn(0f, 100f), if (ids == null) emptyList() else (0 until ids.length()).map { ids.optString(it) })
            }
        }
    } catch (_: Exception) { emptyList() }
}
data class JarvisRoutine(val id: String, val title: String, val cadence: String, val completedDates: List<String> = emptyList())
private const val ROUTINES_KEY = "jarvis_routines"
private fun loadRoutines(context: android.content.Context): List<JarvisRoutine> {
    val raw = context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE).getString(ROUTINES_KEY, null) ?: return emptyList()
    return try {
        val array = JSONArray(raw)
        (0 until array.length()).mapNotNull { i ->
            val item = array.optJSONObject(i) ?: return@mapNotNull null
            val title = item.optString("title", "").trim()
            if (title.isBlank()) null else {
                val dates = item.optJSONArray("completedDates")
                JarvisRoutine(item.optString("id", java.util.UUID.randomUUID().toString()), title.take(120), item.optString("cadence", "Diaria"), if (dates == null) emptyList() else (0 until dates.length()).map { dates.optString(it) }.takeLast(365))
            }
        }
    } catch (_: Exception) { emptyList() }
}
private fun saveRoutines(context: android.content.Context, routines: List<JarvisRoutine>) {
    val array = JSONArray()
    routines.forEach { routine ->
        val dates = JSONArray()
        routine.completedDates.distinct().takeLast(365).forEach { dates.put(it) }
        array.put(JSONObject().put("id", routine.id).put("title", routine.title).put("cadence", routine.cadence).put("completedDates", dates))
    }
    context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE).edit().putString(ROUTINES_KEY, array.toString()).commit()
}
private fun routineDateKey(date: java.time.LocalDate = java.time.LocalDate.now()): String = date.toString()
private fun saveGoals(context: android.content.Context, goals: List<JarvisGoal>) {
    val array = JSONArray()
    goals.forEach { goal ->
        val ids = JSONArray()
        goal.taskIds.forEach { ids.put(it) }
        array.put(JSONObject().put("id", goal.id).put("title", goal.title).put("category", goal.category).put("progress", goal.progress.toDouble()).put("taskIds", ids))
    }
    context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE).edit().putString(GOALS_KEY, array.toString()).commit()
}
private fun saveTasks(context: android.content.Context, tasks: List<JarvisTask>) {
    val a = JSONArray()
    tasks.forEach { a.put(JSONObject().put("id",it.id).put("title",it.title).put("dueDate",it.dueDate).put("priority",it.priority).put("completed",it.completed)) }
    context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE).edit().putString(TASKS,a.toString()).commit()
}

private fun loadHistory(context: android.content.Context): List<Line> {
    val raw = context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
        .getString(HISTORY, null) ?: return listOf(
        Line("JARVIS", "Hola. Soy JARVIS. Tu historial se guarda en este dispositivo. Los motores de IA todavía no están conectados.")
    )
    return try {
        val array = JSONArray(raw)
        (0 until array.length()).map { i ->
            val item = array.getJSONObject(i)
            Line(item.getString("who"), item.getString("text"), item.optLong("timestamp", System.currentTimeMillis()))
        }.ifEmpty { listOf(Line("JARVIS", "Hola. Soy JARVIS. Tu historial se guarda en este dispositivo.")) }
    } catch (_: Exception) {
        listOf(Line("JARVIS", "No pude leer el historial anterior; empezamos uno nuevo."))
    }
}

private fun saveHistory(context: android.content.Context, lines: List<Line>) {
    val array = JSONArray()
    lines.forEach { line -> array.put(JSONObject().put("who", line.who).put("text", line.text).put("timestamp", line.timestamp)) }
    context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
        .edit().putString(HISTORY, array.toString()).commit()
}

private fun loadMemories(context: android.content.Context): List<MemoryItem> {
    val raw = context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
        .getString(MEMORIES, null) ?: return emptyList()
    return try {
        val array = JSONArray(raw)
        (0 until array.length()).map { i ->
            val item = array.get(i)
            if (item is JSONObject) MemoryItem(
                item.optString("category", "Personal").let { if (it in MEMORY_CATEGORIES) it else "Personal" },
                item.optString("text", "")
            ) else MemoryItem("Personal", item.toString())
        }.filter { it.text.isNotBlank() }
    } catch (_: Exception) {
        emptyList()
    }
}

@Composable
fun Jarvis() {
    var showSplash by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) { delay(1900); showSplash = false }

    val context = androidx.compose.ui.platform.LocalContext.current
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val listState = rememberLazyListState()
    val bg = Color(0xFF08111F)
    val panel = Color(0xFF142238)
    val cyan = Color(0xFF54D6E8)
    var input by remember { mutableStateOf("") }
    var lines by remember { mutableStateOf(loadHistory(context)) }
    var showClearDialog by remember { mutableStateOf(false) }
    var showMemoryDialog by remember { mutableStateOf(false) }
    var showExtensionHelp by remember { mutableStateOf(false) }
    var extensionStatus by remember { mutableStateOf("") }
    var extensions by remember { mutableStateOf(ExtensionManager.list(context)) }
    var knowledgeDocs by remember { mutableStateOf(KnowledgeLibrary.list(context)) }
    var knowledgeQuery by remember { mutableStateOf("") }
    var knowledgeStatus by remember { mutableStateOf("") }
    var knowledgeResults by remember { mutableStateOf(emptyList<KnowledgeMatch>()) }
    var codeGuardReport by remember { mutableStateOf<CodeGuardReport?>(null) }
    var codeGuardStatus by remember { mutableStateOf("") }
    var pendingDeleteMemoryIndex by remember { mutableStateOf<Int?>(null) }
    var showSettings by remember { mutableStateOf(false) }
    var showPinSetup by remember { mutableStateOf(false) }
    var pinCurrent by remember { mutableStateOf("") }
    var pinFirst by remember { mutableStateOf("") }
    var pinSecond by remember { mutableStateOf("") }
    var pinError by remember { mutableStateOf("") }
    var pinConfigured by remember { mutableStateOf(hasSecurityPin(context)) }
    var memoryInput by remember { mutableStateOf("") }
    var memorySearch by remember { mutableStateOf("") }
    var pendingMemorySuggestion by remember { mutableStateOf("") }
    var investigationReport by remember { mutableStateOf<InvestigationReport?>(null) }
    var backupStatus by remember { mutableStateOf("") }
    var editingMemoryIndex by remember { mutableStateOf<Int?>(null) }
    var selectedMemoryCategory by remember { mutableStateOf("Personal") }
    var expandedMemoryCategory by remember { mutableStateOf(false) }
    var memories by remember { mutableStateOf(loadMemories(context)) }
    var tasks by remember { mutableStateOf(loadTasks(context)) }
    var goals by remember { mutableStateOf(loadGoals(context)) }
    var routines by remember { mutableStateOf(loadRoutines(context)) }
    var routineTitle by remember { mutableStateOf("") }
    var routineCadence by remember { mutableStateOf("Diaria") }
    var showRoutineDialog by remember { mutableStateOf(false) }
    var goalTitle by remember { mutableStateOf("") }
    var goalCategory by remember { mutableStateOf("Personal") }
    var showGoalDialog by remember { mutableStateOf(false) }
    var linkingGoalId by remember { mutableStateOf<String?>(null) }
    var taskTitle by remember { mutableStateOf("") }
    var taskDueDate by remember { mutableStateOf("") }
    var taskPriority by remember { mutableStateOf("Media") }
    var showTaskDialog by remember { mutableStateOf(false) }
    var showCompletedTasks by remember { mutableStateOf(false) }
    var plannerMonth by remember { mutableStateOf(java.time.YearMonth.now()) }
    var plannerDate by remember { mutableStateOf(java.time.LocalDate.now()) }
    var plannerFilter by remember { mutableStateOf("Hoy") }
    var reminderPermissionGranted by remember { mutableStateOf(android.os.Build.VERSION.SDK_INT < 33 || androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED) }
    val reminderPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> reminderPermissionGranted = granted }

    val knowledgePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try {
                val stream = context.contentResolver.openInputStream(uri)
                    ?: throw IllegalArgumentException("No se pudo abrir el archivo.")
                val displayName = uri.lastPathSegment?.substringAfterLast(':')?.substringAfterLast('/') ?: "documento.txt"
                val added = KnowledgeLibrary.import(context, displayName, stream)
                knowledgeDocs = KnowledgeLibrary.list(context)
                knowledgeStatus = "Documento importado: " + added.name
                knowledgeResults = emptyList()
            } catch (error: Exception) {
                knowledgeStatus = error.message ?: "No se pudo importar el documento."
            }
        }
    }

    val codeGuardPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try {
                val bytes = context.contentResolver.openInputStream(uri)?.use { stream ->
                    val output = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    var total = 0
                    while (true) {
                        val count = stream.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= 200_000) { "El archivo supera el límite de 200 KB." }
                        output.write(buffer, 0, count)
                    }
                    output.toByteArray()
                } ?: throw IllegalArgumentException("No se pudo abrir el archivo.")
                val source = bytes.toString(Charsets.UTF_8)
                val name = uri.lastPathSegment?.substringAfterLast(':')?.substringAfterLast('/') ?: "codigo.txt"
                codeGuardReport = CodeGuardAnalyzer.analyze(name, source)
                codeGuardStatus = "Análisis estático terminado. El archivo no se ejecutó."
            } catch (error: Exception) {
                codeGuardReport = null
                codeGuardStatus = error.message ?: "No se pudo analizar el archivo."
            }
        }
    }

    val exportMemoryPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) try { context.contentResolver.openOutputStream(uri)?.bufferedWriter(Charsets.UTF_8)?.use { it.write(MemoryBackup.export(context)) } ?: throw IllegalArgumentException("No se pudo crear la copia."); backupStatus = "Copia exportada." } catch (error: Exception) { backupStatus = error.message ?: "Error al exportar." }
    }
    val importMemoryPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) try { val content = context.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: throw IllegalArgumentException("No se pudo leer la copia."); val added = MemoryBackup.import(context, content); memories = loadMemories(context); backupStatus = "Importados $added recuerdo(s); los existentes se conservaron." } catch (error: Exception) { backupStatus = error.message ?: "Error al importar." }
    }

    val extensionPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try {
                val stream = context.contentResolver.openInputStream(uri)
                    ?: throw IllegalArgumentException("No se pudo abrir el archivo.")
                val added = ExtensionManager.import(context, stream)
                extensions = ExtensionManager.list(context)
                extensionStatus = "Extensión importada y desactivada: " + added.name
            } catch (error: Exception) {
                extensionStatus = error.message ?: "No se pudo importar la extensión."
            }
        }
    }

    fun saveMemories(items: List<MemoryItem>) {
        val array = JSONArray()
        items.forEach { item -> array.put(JSONObject().put("category", item.category).put("text", item.text)) }
        context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
            .edit().putString(MEMORIES, array.toString()).commit()
    }

    fun sendMessage() {
        val msg = input.trim()
        if (msg.isNotEmpty()) {
            val lower = msg.lowercase(Locale.ROOT)
            val searchIntent = lower.startsWith("busca en mi biblioteca") ||
                lower.startsWith("buscar en mi biblioteca") ||
                lower.startsWith("busca en biblioteca") ||
                lower.startsWith("buscar en biblioteca") ||
                lower.startsWith("consulta mi biblioteca") ||
                lower.startsWith("encuentra en mi biblioteca")
            val investigationIntent = lower.startsWith("investiga mis archivos") || lower.startsWith("investigar mis archivos") || lower.startsWith("investiga mi biblioteca") || lower.startsWith("revisa mis archivos")
            val memorySaveIntent = lower.startsWith("guardar en memoria:") || lower.startsWith("recuerda esto:")
            val taskCreateIntent = lower.startsWith("crear tarea:") || lower.startsWith("agregar tarea:") || lower.startsWith("añadir tarea:")
            val taskListIntent = lower == "mis tareas" || lower == "listar tareas" || lower == "ver tareas" || lower == "tareas pendientes"
            val taskCompleteIntent = lower.startsWith("completar tarea:") || lower.startsWith("marcar tarea:")
            val taskDeleteIntent = lower.startsWith("eliminar tarea:") || lower.startsWith("borrar tarea:")
            val reply = if (taskCreateIntent) {
                val title = msg.substringAfter(":").trim()
                if (title.isBlank()) "Escribe el nombre después de «crear tarea:»." else if (title.length > 160) "El nombre de la tarea no puede superar 160 caracteres." else {
                    val created = JarvisTask(java.util.UUID.randomUUID().toString(), title, "", "Media")
                    tasks = tasks + created
                    saveTasks(context, tasks)
                    scheduleJarvisReminder(context, created)
                    "Listo. Añadí «" + created.title + "» a tus tareas con prioridad media. Puedes decir «mis tareas» para consultarlas."
                }
            } else if (taskListIntent) {
                val pending = tasks.filter { !it.completed }
                if (pending.isEmpty()) "No tienes tareas pendientes." else "Tus tareas pendientes:\n" + pending.joinToString("\n") { "• " + it.title + " (" + it.priority + ")" }
            } else if (taskCompleteIntent) {
                val title = msg.substringAfter(":").trim()
                val match = tasks.firstOrNull { !it.completed && it.title.equals(title, ignoreCase = true) }
                if (match == null) "No encontré una tarea pendiente con ese nombre exacto. Escribe «mis tareas» para consultar los nombres." else {
                    tasks = tasks.map { if (it.id == match.id) it.copy(completed = true) else it }
                    cancelJarvisReminder(context, match.id)
                    saveTasks(context, tasks)
                    "Marcada como completada: «" + match.title + "»."
                }
            } else if (taskDeleteIntent) {
                val title = msg.substringAfter(":").trim()
                val match = tasks.firstOrNull { it.title.equals(title, ignoreCase = true) }
                if (match == null) "No encontré una tarea con ese nombre exacto. Escribe «mis tareas» para consultar los nombres." else {
                    tasks = tasks.filterNot { it.id == match.id }
                    cancelJarvisReminder(context, match.id)
                    saveTasks(context, tasks)
                    "Eliminé la tarea «" + match.title + "» de este dispositivo."
                }
            } else if (memorySaveIntent) { val proposed = if (msg.contains(":")) msg.substringAfter(":").trim() else msg.substringAfter("recuerda esto:", "").trim(); if (proposed.isBlank()) "Escribe qué quieres guardar después de los dos puntos." else { pendingMemorySuggestion = proposed; "Te pediré confirmación antes de guardar este recuerdo." } }
            else if (investigationIntent) { val query = msg.substringAfter("archivos", msg.substringAfter("biblioteca", "")).trim().removePrefix(":").trim(); if (query.isBlank()) "Dime qué tema quieres investigar en tus archivos." else try { val report = LibraryInvestigator.investigate(context, query); investigationReport = report; if (report.hits.isEmpty()) "Revisé ${report.documentsScanned} documento(s) y no encontré coincidencias." else "Encontré ${report.hits.size} fragmento(s) en ${report.matchedDocuments} documento(s). Abre Configuración > Biblioteca para ver el informe. No ejecuté ni modifiqué archivos." } catch (error: Exception) { error.message ?: "No se pudo investigar." } }
            else if (searchIntent) {
                val query = msg.substringAfter("biblioteca", "").trim()
                    .removePrefix("de ").removePrefix("sobre ").removePrefix("acerca de ").trim()
                if (query.isBlank()) {
                    "Dime qué palabra, función o tema quieres buscar en los documentos importados."
                } else {
                    val matches = KnowledgeLibrary.search(context, query, 5)
                    if (matches.isEmpty()) {
                        "No encontré coincidencias en tu biblioteca para: «" + query + "». Importa los documentos correspondientes o prueba con otras palabras."
                    } else {
                        "Encontré " + matches.size + " resultado(s) en tu biblioteca:\\n\\n" +
                            matches.joinToString("\\n\\n") { match -> "📄 " + match.document.name + "\\n" + match.excerpt } +
                            "\\n\\nBúsqueda local: no se ejecutó ningún archivo."
                    }
                }
            } else {
                "Recibí tu mensaje y lo guardé en este dispositivo. La conexión con Groq y el motor Qwen todavía está pendiente."
            }
            val updated = lines + Line("TÚ", msg) + Line("JARVIS", reply)
            lines = updated
            saveHistory(context, updated)
            input = ""
            keyboard?.hide()
        }
    }

    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) listState.animateScrollToItem(lines.lastIndex)
    }

    MaterialTheme(colorScheme = darkColorScheme(primary = cyan, background = bg, surface = panel, onSurface = Color.White)) {
        if (showSplash) {
            Box(
                Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF102B43), bg, Color(0xFF050A12)))),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center, modifier = Modifier.padding(28.dp)) {
                    Box(contentAlignment = Alignment.Center) {
                        Surface(modifier = Modifier.size(204.dp), shape = CircleShape, color = Color(0x2262E5F2)) {}
                        Image(painterResource(R.drawable.jarvis_logo), contentDescription = "Icono de JARVIS", modifier = Modifier.size(166.dp))
                    }
                    Spacer(Modifier.height(28.dp))
                    Text("J.A.R.V.I.S.", color = Color.White, fontSize = 35.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 4.sp)
                    Spacer(Modifier.height(8.dp))
                    Text("ASISTENTE PERSONAL", color = cyan, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 3.sp)
                    Spacer(Modifier.height(26.dp))
                    Surface(color = Color(0x332A6B80), shape = RoundedCornerShape(50.dp)) {
                        Text("SISTEMA EN INICIO", color = cyan, fontSize = 10.sp, letterSpacing = 1.8.sp, modifier = Modifier.padding(horizontal = 18.dp, vertical = 9.dp))
                    }
                    Spacer(Modifier.height(24.dp))
                    LinearProgressIndicator(color = cyan, trackColor = Color(0xFF20364A), modifier = Modifier.width(190.dp).height(4.dp).clip(CircleShape))
                    Spacer(Modifier.height(12.dp))
                    Text("Preparando tu espacio…", color = Color(0xFF9CB4C7), fontSize = 12.sp)
                    Spacer(Modifier.height(34.dp))
                    Text("JARVIS  •  1.0", color = Color(0xFF60798D), fontSize = 10.sp, letterSpacing = 1.5.sp)
                }
            }
        } else {
            Column(Modifier.fillMaxSize().background(bg).padding(16.dp)) {
                Surface(color = panel, shape = RoundedCornerShape(22.dp), modifier = Modifier.fillMaxWidth()) {
                    Row(modifier = Modifier.padding(horizontal = 13.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Surface(shape = CircleShape, color = Color(0xFF1B3B50), modifier = Modifier.size(54.dp)) {
                            Image(painterResource(R.drawable.jarvis_logo), contentDescription = "Perfil de JARVIS", modifier = Modifier.padding(3.dp))
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("J.A.R.V.I.S.", color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
                            Text(if (showSettings) "CONFIGURACIÓN" else "ASISTENTE PERSONAL", color = cyan, fontSize = 9.sp, letterSpacing = 1.6.sp)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(6.dp).background(Color(0xFF6EE7A8), CircleShape))
                                Spacer(Modifier.width(5.dp))
                                Text("Memoria local activa", color = Color(0xFF9CB4C7), fontSize = 10.sp)
                            }
                        }
                        if (showSettings) {
                            TextButton(onClick = { showSettings = false }) { Text("Volver", color = cyan) }
                        } else {
                            TextButton(onClick = { showSettings = true }) { Text("⚙", color = cyan, fontSize = 24.sp) }
                            TextButton(onClick = { showClearDialog = true }) { Text("Borrar", color = Color(0xFFFF9B9B), fontSize = 12.sp) }
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                if (showSettings) {
                    Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
                    Text("Configuración", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    Text("Organiza las opciones de JARVIS sin llenar la pantalla principal.", color = Color(0xFF9CB4C7), fontSize = 13.sp)
                    Spacer(Modifier.height(14.dp))
                    Surface(color = panel, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            Text("CODE GUARD · ANÁLISIS DE CÓDIGO", color = cyan, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                            Spacer(Modifier.height(6.dp))
                            Text("Importa código para inspeccionarlo sin ejecutarlo. Esta primera etapa usa reglas estáticas heurísticas; no garantiza detectar todo el código malicioso. La ejecución permanece deshabilitada.", color = Color(0xFFB8C9D8), fontSize = 12.sp)
                            Spacer(Modifier.height(10.dp))
                            Button(onClick = { codeGuardPicker.launch(arrayOf("*/*")) }, modifier = Modifier.fillMaxWidth()) { Text("Analizar archivo de código") }
                            if (codeGuardStatus.isNotBlank()) {
                                Spacer(Modifier.height(8.dp))
                                Text(codeGuardStatus, color = cyan, fontSize = 11.sp)
                            }
                            codeGuardReport?.let { report ->
                                Spacer(Modifier.height(10.dp))
                                Text("Lenguaje: " + report.language + " · Líneas: " + report.analyzedLines, color = Color.White, fontSize = 12.sp)
                                Text(report.summary, color = if (report.findings.any { it.risk == CodeRisk.HIGH || it.risk == CodeRisk.CRITICAL }) Color(0xFFFF9B9B) else Color(0xFFFFD28A), fontSize = 12.sp)
                                report.findings.take(30).forEach { finding ->
                                    Spacer(Modifier.height(7.dp))
                                    Text("Línea " + finding.line + " · " + finding.risk + " · " + finding.title, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                    Text(finding.explanation, color = Color(0xFFB8C9D8), fontSize = 11.sp)
                                    if (finding.evidence.isNotBlank()) Text(finding.evidence, color = Color(0xFF8FAABD), fontSize = 10.sp)
                                }
                                if (report.findings.isEmpty()) Text("No hubo coincidencias en las reglas actuales. Esto no significa que el archivo sea seguro.", color = Color(0xFFFFD28A), fontSize = 11.sp)
                            }
                        }
                    }
                    Spacer(Modifier.height(18.dp))
                    Surface(color = panel, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            Text("MEMORIA", color = cyan, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
                            Spacer(Modifier.height(6.dp))
                            Text("Guarda información escrita en lenguaje normal; no necesitas códigos. Elige una categoría y escribe una idea clara que quieras conservar.", color = Color(0xFFB8C9D8), fontSize = 13.sp)
                            Spacer(Modifier.height(6.dp))
                            Text("Ejemplos: Personal: «Prefiero explicaciones paso a paso». Proyectos: «JARVIS tendrá memoria local y extensiones». Estudios: «Estoy aprendiendo álgebra». Metas: «Quiero terminar mi proyecto». Temporal: «Esta semana debo entregar una tarea».", color = Color(0xFF8FAABD), fontSize = 11.sp)
                            Spacer(Modifier.height(10.dp))
                            Spacer(Modifier.height(8.dp))
                            MEMORY_CATEGORIES.forEach { category ->
                                val count = memories.count { it.category == category }
                                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text(category, color = Color(0xFFB8C9D8), fontSize = 12.sp, modifier = Modifier.weight(1f))
                                    Text("$count", color = cyan, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                            Button(onClick = { memorySearch = ""; showMemoryDialog = true }, modifier = Modifier.fillMaxWidth()) { Text("Abrir memoria") }
                    Text("En el chat puedes escribir «guardar en memoria: …»; JARVIS pedirá permiso antes de guardarlo.", color = Color(0xFF8FAABD), fontSize = 11.sp)
                    Button(onClick = { exportMemoryPicker.launch("jarvis-memoria.json") }, modifier = Modifier.fillMaxWidth()) { Text("Exportar copia de memoria") }
                    OutlinedButton(onClick = { importMemoryPicker.launch(arrayOf("application/json", "text/*")) }, modifier = Modifier.fillMaxWidth()) { Text("Importar copia") }
                    if (backupStatus.isNotBlank()) Text(backupStatus, color = Color(0xFFFFD28A), fontSize = 11.sp)
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Surface(color = panel, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            Text("BIBLIOTECA DE CONOCIMIENTO Y CÓDIGOS", color = cyan, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
                            Spacer(Modifier.height(6.dp))
                            Text("Importa apuntes y archivos de texto o código. JARVIS los guarda dentro de esta aplicación y permite buscar palabras en ellos sin Internet. Los archivos se analizan como texto: nunca se ejecutan.", color = Color(0xFFB8C9D8), fontSize = 13.sp)
                            Spacer(Modifier.height(8.dp))
                            Text("Formatos: TXT, MD, JSON, XML y CSV. Los archivos de código se importan únicamente desde Code Guard. Máximo 512 KB por archivo y 5 MB en total.", color = Color(0xFF8FAABD), fontSize = 11.sp)
                            Button(onClick = { knowledgePicker.launch(arrayOf("text/plain", "text/markdown", "text/csv", "application/json", "application/xml")) }, modifier = Modifier.fillMaxWidth()) { Text("Importar documento") }
                            if (knowledgeStatus.isNotBlank()) {
                                Spacer(Modifier.height(6.dp))
                                Text(knowledgeStatus, color = Color(0xFFFFD28A), fontSize = 11.sp)
                            }
                            Text("Documentos guardados: ${knowledgeDocs.size}", color = cyan, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            if (knowledgeDocs.isEmpty()) Text("La biblioteca está vacía.", color = Color(0xFF9CB4C7), fontSize = 12.sp)
                            knowledgeDocs.forEach { doc ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(doc.name, color = Color.White, fontSize = 12.sp)
                                        Text("${doc.size / 1024} KB", color = Color(0xFF8FAABD), fontSize = 10.sp)
                                    }
                                    TextButton(onClick = {
                                        if (KnowledgeLibrary.remove(context, doc.id)) {
                                            knowledgeDocs = KnowledgeLibrary.list(context)
                                            knowledgeResults = knowledgeResults.filterNot { it.document.id == doc.id }
                                            knowledgeStatus = "Documento eliminado."
                                        }
                                    }) { Text("Quitar", color = Color(0xFFFF9B9B), fontSize = 11.sp) }
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                            OutlinedTextField(value = knowledgeQuery, onValueChange = { knowledgeQuery = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Buscar en la biblioteca") }, singleLine = true)
                            Button(onClick = {
                                knowledgeResults = KnowledgeLibrary.search(context, knowledgeQuery)
                                knowledgeStatus = if (knowledgeQuery.isBlank()) "Escribe una palabra o frase para buscar." else "Coincidencias encontradas: ${knowledgeResults.size}"
                            }, enabled = knowledgeQuery.isNotBlank() && knowledgeDocs.isNotEmpty(), modifier = Modifier.fillMaxWidth()) { Text("Buscar") }
                            Button(onClick = { try { investigationReport = LibraryInvestigator.investigate(context, knowledgeQuery); val report = investigationReport!!; knowledgeStatus = "${report.hits.size} fragmentos en ${report.matchedDocuments} documentos." } catch (error: Exception) { knowledgeStatus = error.message ?: "Error de investigación." } }, enabled = knowledgeQuery.isNotBlank() && knowledgeDocs.isNotEmpty(), modifier = Modifier.fillMaxWidth()) { Text("Investigar todos los archivos") }
                            investigationReport?.let { report ->
                                Text("Informe local · ${report.matchedDocuments}/${report.documentsScanned} documentos", color = cyan, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                report.hits.forEach { hit -> Surface(color = Color(0xFF0D1A29), shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth()) { Column(Modifier.padding(10.dp)) { Text("${hit.document.name} · línea ${hit.lineNumber} · relevancia ${hit.score}", color = cyan, fontSize = 11.sp); Text(hit.excerpt, color = Color(0xFFB8C9D8), fontSize = 11.sp) } } }
                                Text("Coincidencias de texto; aún no es síntesis semántica de IA.", color = Color(0xFFFFD28A), fontSize = 10.sp)
                            }
                            knowledgeResults.forEach { match ->
                                Surface(color = Color(0xFF0D1A29), shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth()) {
                                    Column(Modifier.padding(10.dp)) {
                                        Text(match.document.name, color = cyan, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                        Text(match.excerpt, color = Color(0xFFB8C9D8), fontSize = 11.sp)
                                    }
                                }
                                Spacer(Modifier.height(6.dp))
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Surface(color = panel, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            Text("EXTENSIONES", color = cyan, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
                            Spacer(Modifier.height(6.dp))
                            Text("Importa paquetes JSON declarativos. JARVIS valida su formato y permisos; no ejecuta código externo. Toda extensión nueva queda desactivada hasta que tú la actives.", color = Color(0xFFB8C9D8), fontSize = 13.sp)
                            Spacer(Modifier.height(10.dp))
                            Button(onClick = { extensionPicker.launch(arrayOf("application/json", "text/*")) }, modifier = Modifier.fillMaxWidth()) { Text("Importar archivo .json") }
                            OutlinedButton(onClick = { showExtensionHelp = true }, modifier = Modifier.fillMaxWidth()) { Text("Cómo preparar una extensión") }
                            if (extensionStatus.isNotBlank()) {
                                Spacer(Modifier.height(8.dp))
                                Text(extensionStatus, color = Color(0xFFFFD28A), fontSize = 11.sp)
                            }
                            Spacer(Modifier.height(8.dp))
                            Text("Extensiones instaladas: ${extensions.size}", color = cyan, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            if (extensions.isEmpty()) Text("Aún no has importado extensiones.", color = Color(0xFF9CB4C7), fontSize = 12.sp)
                            extensions.forEach { extension ->
                                Spacer(Modifier.height(8.dp))
                                Surface(color = Color(0xFF0D1A29), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                                    Column(Modifier.padding(10.dp)) {
                                        Text(extension.name + " · " + extension.version, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                        Text(extension.description, color = Color(0xFFB8C9D8), fontSize = 11.sp)
                                        Text("Permisos: " + if (extension.capabilities.isEmpty()) "ninguno" else extension.capabilities.joinToString(), color = Color(0xFF8FAABD), fontSize = 10.sp)
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(if (extension.enabled) "Activa" else "Desactivada", color = cyan, fontSize = 11.sp, modifier = Modifier.weight(1f))
                                            Switch(checked = extension.enabled, onCheckedChange = { enabled ->
                                                try {
                                                    ExtensionManager.setEnabled(context, extension, enabled)
                                                    extensions = ExtensionManager.list(context)
                                                    extensionStatus = if (enabled) "Extensión activada." else "Extensión desactivada."
                                                } catch (error: Exception) {
                                                    extensionStatus = error.message ?: "No se pudo cambiar el estado."
                                                }
                                            })
                                            TextButton(onClick = {
                                                if (ExtensionManager.remove(context, extension)) {
                                                    extensions = ExtensionManager.list(context)
                                                    extensionStatus = "Extensión eliminada."
                                                }
                                            }) { Text("Quitar", color = Color(0xFFFF9B9B), fontSize = 11.sp) }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Surface(color = panel, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            Text("GESTOR DE TAREAS", color = cyan, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
                            Text("Organiza pendientes, prioridades y fechas. Los avisos se programan en este dispositivo.", color = Color(0xFFB8C9D8), fontSize = 13.sp)
                            Spacer(Modifier.height(8.dp))
                            Text("${tasks.count { !it.completed }} pendiente(s) · ${tasks.count { it.completed }} completada(s)", color = cyan, fontSize = 12.sp, fontWeight = FontWeight.Bold)
Spacer(Modifier.height(12.dp))
Text("AGENDA Y PLANIFICACIÓN", color = cyan, fontSize = 11.sp, fontWeight = FontWeight.Bold)
Row(verticalAlignment = Alignment.CenterVertically) {
    TextButton(onClick = { plannerMonth = plannerMonth.minusMonths(1) }) { Text("‹", color = cyan) }
    Text(plannerMonth.month.getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale("es", "PE")).replaceFirstChar { it.uppercase() } + " " + plannerMonth.year, color = Color.White, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    TextButton(onClick = { plannerMonth = plannerMonth.plusMonths(1) }) { Text("›", color = cyan) }
}
Row(Modifier.fillMaxWidth()) { listOf("L","M","X","J","V","S","D").forEach { day -> Text(day, color = cyan, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center, fontSize = 11.sp) } }
val offset = plannerMonth.atDay(1).dayOfWeek.value - 1
val dayCells = (0 until offset).map { null } + (1..plannerMonth.lengthOfMonth()).map { it }
dayCells.chunked(7).forEach { week -> Row(Modifier.fillMaxWidth()) { week.forEach { day ->
    Box(Modifier.weight(1f).padding(2.dp).height(34.dp).background(if (day != null && plannerDate == plannerMonth.atDay(day)) Color(0xFF24516A) else Color.Transparent, RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
        if (day != null) { val date = plannerMonth.atDay(day); val marked = tasks.any { !it.completed && taskLocalDate(it.dueDate) == date }; TextButton(onClick = { plannerDate = date; plannerFilter = "Fecha" }, contentPadding = PaddingValues(0.dp)) { Text(day.toString() + if (marked) " •" else "", color = if (date == java.time.LocalDate.now()) cyan else Color.White, fontSize = 12.sp) } }
    }
} } }
Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { listOf("Hoy","Mañana","Próximos","Vencidas").forEach { filter -> TextButton(onClick = { plannerFilter = filter; if (filter == "Hoy") plannerDate = java.time.LocalDate.now(); if (filter == "Mañana") plannerDate = java.time.LocalDate.now().plusDays(1) }) { Text(filter, color = if (plannerFilter == filter) cyan else Color(0xFFB8C9D8), fontSize = 10.sp) } } }
val today = java.time.LocalDate.now()
val plannedTasks = tasks.filter { !it.completed }.filter { task -> val date = taskLocalDate(task.dueDate); when (plannerFilter) { "Hoy" -> date == today; "Mañana" -> date == today.plusDays(1); "Próximos" -> date != null && date > today.plusDays(1); "Vencidas" -> date != null && date < today; else -> date == plannerDate } }.sortedBy { taskLocalDate(it.dueDate) ?: java.time.LocalDate.MAX }
Text(if (plannerFilter == "Fecha") "Tareas del " + plannerDate.format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy")) else "Tareas: " + plannerFilter.lowercase(), color = cyan, fontSize = 12.sp, fontWeight = FontWeight.Bold)
if (plannedTasks.isEmpty()) Text("No hay tareas para este periodo. Las tareas sin fecha no aparecen en la agenda.", color = Color(0xFF9CB4C7), fontSize = 11.sp) else plannedTasks.forEach { task -> Text("• " + task.title + " · " + task.dueDate, color = Color.White, fontSize = 11.sp) }
Text("Fechas admitidas: dd/MM/yyyy o yyyy-MM-dd, con hora HH:mm opcional.", color = Color(0xFF9CB4C7), fontSize = 10.sp)

                            Text(if (reminderPermissionGranted) "Notificaciones activadas" else "Activa las notificaciones para recibir recordatorios.", color = Color(0xFFB8C9D8), fontSize = 11.sp)
                            if (!reminderPermissionGranted && android.os.Build.VERSION.SDK_INT >= 33) { TextButton(onClick = { reminderPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS) }) { Text("Permitir notificaciones") } }
                            Button(onClick = { taskTitle = ""; taskDueDate = ""; taskPriority = "Media"; showTaskDialog = true }, modifier = Modifier.fillMaxWidth()) { Text("＋ Nueva tarea") }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Mostrar completadas", color = Color(0xFFB8C9D8), fontSize = 12.sp, modifier = Modifier.weight(1f))
                                Switch(checked = showCompletedTasks, onCheckedChange = { showCompletedTasks = it })
                            }
                            val visibleTasks = tasks.filter { it.completed == showCompletedTasks }.sortedBy { when(it.priority) { "Alta" -> 0; "Media" -> 1; else -> 2 } }
                            if (visibleTasks.isEmpty()) Text(if (showCompletedTasks) "Aún no hay tareas completadas." else "No tienes tareas pendientes.", color = Color(0xFF9CB4C7), fontSize = 12.sp)
                            visibleTasks.forEach { task ->
                                Surface(color = Color(0xFF0D1A29), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                                    Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Checkbox(checked = task.completed, onCheckedChange = { checked -> tasks = tasks.map { if (it.id == task.id) it.copy(completed = checked) else it }; saveTasks(context,tasks) })
                                        Column(Modifier.weight(1f)) {
                                            Text(task.title, color = Color.White, fontSize = 13.sp, textDecoration = if(task.completed) androidx.compose.ui.text.style.TextDecoration.LineThrough else null)
                                            Text("Prioridad: ${task.priority}" + if(task.dueDate.isNotBlank()) " · Fecha: ${task.dueDate}" else " · Sin fecha", color = Color(0xFF9CB4C7), fontSize = 10.sp)
                                        }
                                        TextButton(onClick = { tasks = tasks.filterNot { it.id == task.id }; saveTasks(context,tasks) }) { Text("Quitar", color = Color(0xFFFF9B9B), fontSize = 10.sp) }
                                    }
                                }
                                Spacer(Modifier.height(6.dp))
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Surface(color = panel, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                                                Spacer(Modifier.height(12.dp))
                    Surface(color = panel, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                                                Spacer(Modifier.height(12.dp))
                    Surface(color = panel, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            Text("RUTINAS Y HÁBITOS", color = cyan, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
                            Text("Construye constancia con actividades diarias o semanales. Tu historial se guarda en este dispositivo.", color = Color(0xFFB8C9D8), fontSize = 12.sp)
                            Button(onClick = { routineTitle = ""; routineCadence = "Diaria"; showRoutineDialog = true }, modifier = Modifier.fillMaxWidth()) { Text("＋ Nueva rutina") }
                            if (routines.isEmpty()) Text("Aún no tienes rutinas creadas.", color = Color(0xFF9CB4C7), fontSize = 12.sp)
                            val todayKey = routineDateKey()
                            routines.forEach { routine ->
                                val doneToday = todayKey in routine.completedDates
                                val lastWeek = routine.completedDates.count { runCatching { java.time.LocalDate.parse(it) }.getOrNull()?.let { date -> !date.isBefore(java.time.LocalDate.now().minusDays(6)) && !date.isAfter(java.time.LocalDate.now()) } == true }
                                Spacer(Modifier.height(8.dp))
                                Surface(color = Color(0xFF0D1A29), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                                    Column(Modifier.padding(10.dp)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Column(Modifier.weight(1f)) { Text(routine.title, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold); Text(routine.cadence + " · " + routine.completedDates.size + " registro(s) histórico(s)", color = Color(0xFF9CB4C7), fontSize = 10.sp) }
                                            TextButton(onClick = { routines = routines.filterNot { it.id == routine.id }; saveRoutines(context, routines) }) { Text("Quitar", color = Color(0xFFFF9B9B), fontSize = 10.sp) }
                                        }
                                        Button(onClick = { routines = routines.map { item -> if (item.id == routine.id) item.copy(completedDates = if (doneToday) item.completedDates.filterNot { it == todayKey } else (item.completedDates + todayKey).distinct().takeLast(365)) else item }; saveRoutines(context, routines) }, modifier = Modifier.fillMaxWidth()) { Text(if (doneToday) "✓ Hecho hoy · Deshacer" else "Marcar como realizada hoy") }
                                        Text("Últimos 7 días: " + lastWeek + " realización(es)", color = cyan, fontSize = 11.sp)
                                    }
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Text("METAS Y OBJETIVOS", color = cyan, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
                            Text("Define objetivos, registra avances y relaciónalos con tus tareas.", color = Color(0xFFB8C9D8), fontSize = 12.sp)
                            Text("${goals.count { it.progress < 100f }} activa(s) · ${goals.count { it.progress >= 100f }} finalizada(s)", color = cyan, fontSize = 12.sp)
                            Button(onClick = { goalTitle = ""; goalCategory = "Personal"; showGoalDialog = true }, modifier = Modifier.fillMaxWidth()) { Text("＋ Nueva meta") }
                            if (goals.isEmpty()) Text("Todavía no tienes metas. Crea una para comenzar.", color = Color(0xFF9CB4C7), fontSize = 12.sp)
                            goals.forEach { goal ->
                                Spacer(Modifier.height(8.dp))
                                Surface(color = Color(0xFF0D1A29), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                                    Column(Modifier.padding(10.dp)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Column(Modifier.weight(1f)) { Text(goal.title, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold); Text(goal.category + " · " + goal.taskIds.size + " tarea(s) vinculada(s)", color = Color(0xFF9CB4C7), fontSize = 10.sp) }
                                            TextButton(onClick = { goals = goals.filterNot { it.id == goal.id }; saveGoals(context, goals) }) { Text("Quitar", color = Color(0xFFFF9B9B), fontSize = 10.sp) }
                                        }
                                        Slider(value = goal.progress, onValueChange = { value -> goals = goals.map { if (it.id == goal.id) it.copy(progress = value) else it } }, onValueChangeFinished = { saveGoals(context, goals) }, valueRange = 0f..100f)
                                        Text("${goal.progress.toInt()}% completado", color = cyan, fontSize = 11.sp)
                                        TextButton(onClick = { linkingGoalId = goal.id }) { Text("Vincular una tarea", color = cyan, fontSize = 11.sp) }
                                    }
                                }
                            }
                        }
                    }
                    Text("SEGURIDAD", color = cyan, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
                            Spacer(Modifier.height(6.dp))
                            Text(if (pinConfigured) "Código de seguridad configurado en este dispositivo." else "Configura un código de 6 dígitos para autorizar futuras funciones protegidas.", color = Color(0xFFB8C9D8), fontSize = 13.sp)
                            Spacer(Modifier.height(10.dp))
                            Button(onClick = { pinCurrent = ""; pinFirst = ""; pinSecond = ""; pinError = ""; showPinSetup = true }, modifier = Modifier.fillMaxWidth()) { Text(if (pinConfigured) "Cambiar código de seguridad" else "Configurar código de seguridad") }
                        }
                    }
                    Surface(color = panel, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            Text("ESTADO DE IA", color = cyan, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
                            Spacer(Modifier.height(6.dp))
                            Text("Groq y Qwen todavía no están conectados a esta versión.", color = Color(0xFFB8C9D8), fontSize = 13.sp)
                        }
                    }
                    }
                } else {
                    Surface(color = Color(0xFF0D1A29), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                        Text("Tu espacio personal · Los mensajes se guardan en este dispositivo", color = Color(0xFF8FAABD), fontSize = 10.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp))
                    }
                    Spacer(Modifier.height(12.dp))
                    Surface(color = panel, shape = RoundedCornerShape(18.dp), modifier = Modifier.weight(1f).fillMaxWidth()) {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize().padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            items(lines) { line ->
                                Column(Modifier.fillMaxWidth(), horizontalAlignment = if (line.who == "TÚ") Alignment.End else Alignment.Start) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        if (line.who != "TÚ") {
                                            Surface(shape = CircleShape, color = Color(0xFF1B4658), modifier = Modifier.size(23.dp)) {
                                                Box(contentAlignment = Alignment.Center) { Text("J", color = cyan, fontSize = 11.sp, fontWeight = FontWeight.Bold) }
                                            }
                                            Spacer(Modifier.width(6.dp))
                                        }
                                        Text(if (line.who == "TÚ") "Tú" else "JARVIS", color = if (line.who == "TÚ") Color(0xFF9CCBEA) else cyan, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                        if (line.who == "TÚ") {
                                            Spacer(Modifier.width(6.dp))
                                            Surface(shape = CircleShape, color = Color(0xFF24465C), modifier = Modifier.size(23.dp)) {
                                                Box(contentAlignment = Alignment.Center) { Text("T", color = Color(0xFFB9E4FF), fontSize = 11.sp, fontWeight = FontWeight.Bold) }
                                            }
                                        }
                                    }
                                    Spacer(Modifier.height(4.dp))
                                    Surface(
                                        color = if (line.who == "TÚ") Color(0xFF17445A) else Color(0xFF1A2D43),
                                        shape = RoundedCornerShape(
                                            topStart = 15.dp, topEnd = 15.dp,
                                            bottomEnd = if (line.who == "TÚ") 5.dp else 15.dp,
                                            bottomStart = if (line.who == "TÚ") 15.dp else 5.dp
                                        )
                                    ) {
                                        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                                            Text(line.text, color = Color.White, fontSize = 14.sp)
                                            Spacer(Modifier.height(5.dp))
                                            Text(
                                                SimpleDateFormat("HH:mm", Locale("es", "PE")).format(Date(line.timestamp)),
                                                color = Color(0xFF91AFC1),
                                                fontSize = 9.sp,
                                                modifier = Modifier.align(Alignment.End)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(color = panel, shape = RoundedCornerShape(24.dp), modifier = Modifier.weight(1f)) {
                            BasicTextField(
                                value = input,
                                onValueChange = { input = it },
                                modifier = Modifier.fillMaxWidth().padding(15.dp),
                                textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 16.sp),
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                                keyboardActions = KeyboardActions(onSend = { sendMessage() }),
                                decorationBox = { inner ->
                                    if (input.isEmpty()) Text("Escribe a JARVIS…", color = Color.Gray)
                                    inner()
                                }
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Button(onClick = { sendMessage() }, enabled = input.isNotBlank()) { Text("Enviar") }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("JARVIS 1.5 · Modo local", color = Color.Gray, fontSize = 11.sp)
                }
            }
        }


        if (showRoutineDialog) {
            AlertDialog(onDismissRequest = { showRoutineDialog = false }, title = { Text("Nueva rutina") }, text = {
                Column {
                    OutlinedTextField(value = routineTitle, onValueChange = { routineTitle = it.take(120) }, label = { Text("Actividad o hábito") }, singleLine = true)
                    Spacer(Modifier.height(8.dp))
                    Text("Frecuencia", color = cyan, fontSize = 12.sp)
                    Row { listOf("Diaria", "Semanal").forEach { cadence -> TextButton(onClick = { routineCadence = cadence }) { Text(if (routineCadence == cadence) "✓ " + cadence else cadence, color = if (routineCadence == cadence) cyan else Color.White) } } }
                }
            }, confirmButton = { TextButton(onClick = { val title = routineTitle.trim(); if (title.isNotBlank()) { routines = routines + JarvisRoutine(java.util.UUID.randomUUID().toString(), title, routineCadence); saveRoutines(context, routines); showRoutineDialog = false } }, enabled = routineTitle.isNotBlank()) { Text("Crear rutina") } }, dismissButton = { TextButton(onClick = { showRoutineDialog = false }) { Text("Cancelar") } })
        }
        if (showGoalDialog) {
            AlertDialog(onDismissRequest = { showGoalDialog = false }, title = { Text("Nueva meta") }, text = {
                Column {
                    OutlinedTextField(value = goalTitle, onValueChange = { goalTitle = it.take(120) }, label = { Text("¿Qué quieres lograr?") }, singleLine = true)
                    Spacer(Modifier.height(8.dp))
                    Text("Categoría", color = cyan, fontSize = 12.sp)
                    Row { listOf("Personal", "Estudios", "Proyectos").forEach { category -> TextButton(onClick = { goalCategory = category }) { Text(if (goalCategory == category) "✓ " + category else category, color = if (goalCategory == category) cyan else Color.White, fontSize = 10.sp) } } }
                }
            }, confirmButton = { TextButton(onClick = { val title = goalTitle.trim(); if (title.isNotBlank()) { goals = goals + JarvisGoal(java.util.UUID.randomUUID().toString(), title, goalCategory); saveGoals(context, goals); showGoalDialog = false } }, enabled = goalTitle.isNotBlank()) { Text("Crear meta") } }, dismissButton = { TextButton(onClick = { showGoalDialog = false }) { Text("Cancelar") } })
        }
        if (linkingGoalId != null) {
            val goal = goals.firstOrNull { it.id == linkingGoalId }
            AlertDialog(onDismissRequest = { linkingGoalId = null }, title = { Text("Vincular tarea") }, text = {
                Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                    Text("Selecciona una tarea para relacionarla con esta meta.", fontSize = 12.sp)
                    tasks.forEach { task -> TextButton(onClick = { goals = goals.map { item -> if (item.id == linkingGoalId && task.id !in item.taskIds) item.copy(taskIds = item.taskIds + task.id) else item }; saveGoals(context, goals); linkingGoalId = null }, enabled = goal != null && task.id !in goal.taskIds, modifier = Modifier.fillMaxWidth()) { Text(task.title, color = Color.White) } }
                    if (tasks.isEmpty()) Text("Primero crea una tarea en el gestor.", color = Color(0xFFB8C9D8))
                }
            }, confirmButton = { TextButton(onClick = { linkingGoalId = null }) { Text("Cerrar") } })
        }
        if (showTaskDialog) {
            AlertDialog(onDismissRequest = { showTaskDialog = false }, title = { Text("Nueva tarea") }, text = {
                Column {
                    OutlinedTextField(value = taskTitle, onValueChange = { taskTitle = it.take(160) }, label = { Text("¿Qué necesitas hacer?") })
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(value = taskDueDate, onValueChange = { taskDueDate = it.take(40) }, label = { Text("Fecha límite (opcional)") }, placeholder = { Text("yyyy-MM-dd HH:mm (ej. 2026-11-15 18:30)") }, singleLine = true)
                    Spacer(Modifier.height(8.dp))
                    Text("Prioridad", color = cyan, fontSize = 12.sp)
                    Row(verticalAlignment = Alignment.CenterVertically) { listOf("Alta","Media","Baja").forEach { option -> Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) { RadioButton(selected = taskPriority == option, onClick = { taskPriority = option }); Text(option, color = Color.White, fontSize = 10.sp) } } }
                }
            }, confirmButton = { TextButton(onClick = { val title = taskTitle.trim(); if(title.isNotBlank()) { val created = JarvisTask(java.util.UUID.randomUUID().toString(),title,taskDueDate.trim(),taskPriority); tasks = tasks + created; saveTasks(context,tasks); scheduleJarvisReminder(context,created); showTaskDialog = false } }, enabled = taskTitle.isNotBlank()) { Text("Guardar tarea") } }, dismissButton = { TextButton(onClick = { showTaskDialog = false }) { Text("Cancelar") } })
        }

        if (showExtensionHelp) {
            AlertDialog(
                onDismissRequest = { showExtensionHelp = false },
                title = { Text("Guía para preparar una extensión") },
                text = {
                    Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
                        Text("Una extensión se prepara como un archivo de texto JSON y se guarda con extensión .json. No pegues este contenido en Memoria.", fontSize = 13.sp)
                        Spacer(Modifier.height(10.dp))
                        Text("Qué significa cada campo:", color = cyan, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        Text("• format: debe decir jarvis-extension.\n• formatVersion: versión del formato; usa 1.\n• id: identificador único, en minúsculas y sin espacios.\n• name: nombre visible.\n• version: versión, por ejemplo 1.0.0.\n• description: explica qué hace.\n• capabilities: permisos/acciones que solicita.\n• content.instructions: instrucciones de la extensión.\n• content.knowledge: lista de conocimientos; puede estar vacía.", fontSize = 12.sp)
                        Spacer(Modifier.height(10.dp))
                        Text("Ejemplo completo:", color = cyan, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        Text("{\n  \"format\": \"jarvis-extension\",\n  \"formatVersion\": 1,\n  \"id\": \"com.ejemplo.ayudante-estudio\",\n  \"name\": \"Ayudante de estudio\",\n  \"version\": \"1.0.0\",\n  \"description\": \"Organiza temas de estudio\",\n  \"capabilities\": [\"knowledge.search\"],\n  \"content\": {\n    \"instructions\": \"Ayuda a ordenar temas y apuntes.\",\n    \"knowledge\": []\n  }\n}", color = Color(0xFFD5E7F2), fontSize = 10.sp)
                        Spacer(Modifier.height(8.dp))
                        Text("Este es un ejemplo de plantilla. JARVIS valida e importa extensiones declarativas, pero todavía no ejecuta sus capacidades. Tampoco ejecuta scripts ni código externo.", color = Color(0xFFFFD28A), fontSize = 11.sp)
                    }
                },
                confirmButton = { TextButton(onClick = { showExtensionHelp = false }) { Text("Entendido") } }
            )
        }
        if (showPinSetup) {
            AlertDialog(onDismissRequest = { showPinSetup = false; pinCurrent = ""; pinFirst = ""; pinSecond = ""; pinError = "" }, title = { Text(if (pinConfigured) "Verificar y cambiar código" else "Crear código de seguridad") }, text = {
                Column {
                    Text("Elige un código de 6 dígitos. No lo compartas ni lo escribas en el chat.", fontSize = 13.sp)
                    Spacer(Modifier.height(10.dp))
                    if (pinConfigured) { OutlinedTextField(value = pinCurrent, onValueChange = { if (it.length <= 6 && it.all { c -> c.isDigit() }) pinCurrent = it }, label = { Text("Código actual") }, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword), singleLine = true); Spacer(Modifier.height(8.dp)) }
                    OutlinedTextField(value = pinFirst, onValueChange = { if (it.length <= 6 && it.all { c -> c.isDigit() }) pinFirst = it }, label = { Text("Nuevo código") }, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword), singleLine = true)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(value = pinSecond, onValueChange = { if (it.length <= 6 && it.all { c -> c.isDigit() }) pinSecond = it }, label = { Text("Confirmar código") }, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword), singleLine = true)
                    if (pinError.isNotBlank()) Text(pinError, color = Color(0xFFFF9B9B), fontSize = 12.sp)
                }
            }, confirmButton = { TextButton(onClick = { when { pinConfigured && (pinCurrent.length != 6 || !verifySecurityPin(context, pinCurrent)) -> pinError = "El código actual no es correcto."; pinFirst.length != 6 -> pinError = "El nuevo código debe tener exactamente 6 dígitos."; pinFirst != pinSecond -> pinError = "Los códigos nuevos no coinciden."; else -> { saveSecurityPin(context, pinFirst); pinConfigured = true; showPinSetup = false; pinCurrent = ""; pinFirst = ""; pinSecond = ""; pinError = "" } } }) { Text("Guardar código") } }, dismissButton = { TextButton(onClick = { showPinSetup = false; pinCurrent = ""; pinFirst = ""; pinSecond = ""; pinError = "" }) { Text("Cancelar") } })
        }
        if (showMemoryDialog) {
            AlertDialog(
                onDismissRequest = { showMemoryDialog = false },
                title = { Text("Memoria de JARVIS") },
                text = {
                    Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState())) {
                        Text("Estos recuerdos se guardan solo en este teléfono. Escribe una frase normal, no código. Guarda solo información que quieras conservar.", fontSize = 13.sp)
                        Spacer(Modifier.height(6.dp))
                        Text("Personal: preferencias. Proyectos: ideas y avances. Estudios: temas que aprendes. Metas: objetivos. Temporal: información útil por poco tiempo.", color = Color(0xFF9CB4C7), fontSize = 11.sp)
                        Spacer(Modifier.height(10.dp))
                        OutlinedTextField(value = memorySearch, onValueChange = { memorySearch = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Buscar recuerdos") }, singleLine = true)
                        Spacer(Modifier.height(8.dp))
                        Box {
                            OutlinedButton(onClick = { expandedMemoryCategory = true }) { Text("Sección: $selectedMemoryCategory") }
                            DropdownMenu(expanded = expandedMemoryCategory, onDismissRequest = { expandedMemoryCategory = false }) {
                                MEMORY_CATEGORIES.forEach { category ->
                                    DropdownMenuItem(text = { Text(category) }, onClick = {
                                        selectedMemoryCategory = category
                                        expandedMemoryCategory = false
                                    })
                                }
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        BasicTextField(
                            value = memoryInput,
                            onValueChange = { memoryInput = it },
                            modifier = Modifier.fillMaxWidth().background(Color(0xFF20364A), RoundedCornerShape(10.dp)).padding(12.dp),
                            textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 14.sp),
                            decorationBox = { inner ->
                                if (memoryInput.isEmpty()) Text("Ejemplo: «En JARVIS quiero organizar mis proyectos por etapas»", color = Color.LightGray, fontSize = 12.sp)
                                inner()
                            }
                        )
                        Spacer(Modifier.height(10.dp))
                        val visibleMemories = memories.withIndex().filter { it.value.category == selectedMemoryCategory && it.value.text.contains(memorySearch.trim(), ignoreCase = true) }
                        Text("$selectedMemoryCategory · ${visibleMemories.size} recuerdos", color = cyan, fontSize = 12.sp)
                        if (visibleMemories.isEmpty()) Text("Todavía no hay recuerdos en esta sección.", color = Color.LightGray, fontSize = 12.sp)
                        visibleMemories.forEach { indexed ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("• ${indexed.value.text}", color = Color.White, fontSize = 12.sp, modifier = Modifier.weight(1f))
                                TextButton(onClick = {
                                    memoryInput = indexed.value.text
                                    selectedMemoryCategory = indexed.value.category
                                    editingMemoryIndex = indexed.index
                                }) { Text("Editar", color = cyan, fontSize = 11.sp) }
                                TextButton(onClick = {
                                    pendingDeleteMemoryIndex = indexed.index
                                }) { Text("Quitar", color = Color(0xFFFF9B9B), fontSize = 11.sp) }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        val value = memoryInput.trim()
                        if (value.isNotEmpty()) {
                            val editing = editingMemoryIndex
                            memories = if (editing != null && editing in memories.indices) {
                                memories.toMutableList().also { it[editing] = MemoryItem(selectedMemoryCategory, value) }
                            } else memories + MemoryItem(selectedMemoryCategory, value)
                            saveMemories(memories)
                            memoryInput = ""
                            editingMemoryIndex = null
                        }
                    }) { Text(if (editingMemoryIndex == null) "Guardar recuerdo" else "Guardar cambios") }
                },
                dismissButton = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (editingMemoryIndex != null) {
                            TextButton(onClick = {
                                editingMemoryIndex = null
                                memoryInput = ""
                            }) { Text("Cancelar edición") }
                        }
                        TextButton(onClick = {
                            editingMemoryIndex = null
                            memoryInput = ""
                            showMemoryDialog = false
                        }) { Text("Cerrar") }
                    }
                }
            )
        }

        if (pendingMemorySuggestion.isNotBlank()) { AlertDialog(onDismissRequest = { pendingMemorySuggestion = "" }, title = { Text("¿Guardar este recuerdo?") }, text = { Text("Se guardará en Personal, solo en este dispositivo:\n\n$pendingMemorySuggestion") }, confirmButton = { TextButton(onClick = { memories = memories + MemoryItem("Personal", pendingMemorySuggestion); saveMemories(memories); pendingMemorySuggestion = "" }) { Text("Sí, guardar") } }, dismissButton = { TextButton(onClick = { pendingMemorySuggestion = "" }) { Text("Ahora no") } }) }

        if (pendingDeleteMemoryIndex != null) {
            AlertDialog(
                onDismissRequest = { pendingDeleteMemoryIndex = null },
                title = { Text("¿Quitar este recuerdo?") },
                text = { Text("Este recuerdo se eliminará de la memoria local. Esta acción no se puede deshacer.") },
                confirmButton = {
                    TextButton(onClick = {
                        val index = pendingDeleteMemoryIndex
                        if (index != null && index in memories.indices) {
                            memories = memories.toMutableList().also { it.removeAt(index) }
                            val editing = editingMemoryIndex
                            if (editing == index) {
                                editingMemoryIndex = null
                                memoryInput = ""
                            } else if (editing != null && editing > index) {
                                editingMemoryIndex = editing - 1
                            }
                            saveMemories(memories)
                        }
                        pendingDeleteMemoryIndex = null
                    }) { Text("Quitar recuerdo", color = Color(0xFFFF9B9B)) }
                },
                dismissButton = {
                    TextButton(onClick = { pendingDeleteMemoryIndex = null }) { Text("Cancelar") }
                }
            )
        }

        if (showClearDialog) {
            AlertDialog(
                onDismissRequest = { showClearDialog = false },
                title = { Text("¿Borrar el historial?") },
                text = { Text("Se eliminarán los mensajes guardados en este dispositivo. Esta acción no se puede deshacer.") },
                confirmButton = {
                    TextButton(onClick = {
                        val fresh = listOf(Line("JARVIS", "Historial borrado. Empezamos de nuevo."))
                        lines = fresh
                        saveHistory(context, fresh)
                        showClearDialog = false
                    }) { Text("Borrar historial", color = Color(0xFFFF7777)) }
                },
                dismissButton = {
                    TextButton(onClick = { showClearDialog = false }) { Text("Cancelar") }
                }
            )
        }
    }
}
