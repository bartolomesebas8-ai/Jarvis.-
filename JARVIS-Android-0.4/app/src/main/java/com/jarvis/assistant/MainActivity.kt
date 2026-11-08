package com.jarvis.assistant

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
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
import kotlinx.coroutines.launch
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
private fun SettingsGroupHeader(icon: String, title: String, subtitle: String, expanded: Boolean, onClick: () -> Unit) {
    Surface(
        color = Color(0xFF102B43),
        shape = RoundedCornerShape(15.dp),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 13.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(color = Color(0xFF1B4658), shape = RoundedCornerShape(11.dp), modifier = Modifier.size(38.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Text(icon, color = Color(0xFF8DEBFA), fontSize = 18.sp)
                }
            }
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f)) {
                Text(title, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                Spacer(Modifier.height(3.dp))
                Text(subtitle, color = Color(0xFF9CB4C7), fontSize = 11.sp)
            }
            Text(if (expanded) "⌄" else "›", color = Color(0xFF54D6E8), fontSize = 23.sp, fontWeight = FontWeight.Light)
        }
    }
}


@Composable
private fun HolographicOrb(size: androidx.compose.ui.unit.Dp, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "orbital-loading")
    val rotation = transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 4200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "orbital-rotation"
    )
    val reverseRotation = transition.animateFloat(
        initialValue = 360f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 6200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "counter-rotation"
    )
    Box(
        modifier = modifier.size(size),
        contentAlignment = Alignment.Center
    ) {
        Box(
            Modifier.fillMaxSize()
                .background(Color(0x2239BFFF), CircleShape)
                .border(1.dp, Color(0x8857DFFF), CircleShape)
        )
        Box(
            Modifier.fillMaxSize(0.76f)
                .background(
                    Brush.radialGradient(
                        listOf(Color(0xFFB8F7FF), Color(0xFF249FEA), Color(0xFF073B83), Color(0xFF06172F))
                    ),
                    CircleShape
                )
                .border(1.5.dp, Color(0xFF83E9FF), CircleShape)
        )
        Box(
            Modifier.fillMaxWidth(0.98f)
                .height(size * 0.30f)
                .rotate(rotation.value)
                .border(2.dp, Color(0xFF48D9FF), RoundedCornerShape(50))
        )
        Box(
            Modifier.fillMaxWidth(0.92f)
                .height(size * 0.44f)
                .rotate(reverseRotation.value)
                .border(1.5.dp, Color(0xFF9A8CFF), RoundedCornerShape(50))
        )
        Box(
            Modifier.size(size * 0.13f)
                .background(Color(0xFFE5FCFF), CircleShape)
        )
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
    var knowledgeManualText by remember { mutableStateOf("") }
    var codeWorkspaces by remember { mutableStateOf(CodeWorkspaces.list(context)) }
    var codeWorkspaceName by remember { mutableStateOf("") }
    var codeWorkspaceSource by remember { mutableStateOf("") }
    var selectedCodeA by remember { mutableStateOf("") }
    var selectedCodeB by remember { mutableStateOf("") }
    var codeComparison by remember { mutableStateOf(emptyList<String>()) }
    var codeWorkspaceReports by remember { mutableStateOf(emptyMap<String, CodeGuardReport>()) }
    var codeGuardReport by remember { mutableStateOf<CodeGuardReport?>(null) }
    var codeGuardStatus by remember { mutableStateOf("") }
    var codeRunResults by remember { mutableStateOf(emptyMap<String, CodeRunResult>()) }
    var codeRunDetails by remember { mutableStateOf(emptySet<String>()) }
    var codeRunningIds by remember { mutableStateOf(emptySet<String>()) }
    var pendingCodeRun by remember { mutableStateOf<CodeWorkspace?>(null) }
    var codeConsentReady by remember { mutableStateOf(false) }
    LaunchedEffect(pendingCodeRun?.id, pendingCodeRun?.source) {
        val source = pendingCodeRun?.source.orEmpty()
        val asksForPhoneData = Regex("(?i)(READ_CONTACTS|READ_SMS|READ_CALL_LOG|ACCESS_FINE_LOCATION|ACCESS_COARSE_LOCATION|CAMERA|RECORD_AUDIO|READ_MEDIA_|contacts|sms|calllog|geolocation|navigator\\.camera|microphone|ubicaci[oó]n|c[aá]mara|micr[oó]fono|contactos|mensajes|datos personales)").containsMatchIn(source)
        codeConsentReady = !asksForPhoneData
        if (pendingCodeRun != null && asksForPhoneData) {
            delay(30_000)
            codeConsentReady = true
        }
    }
    var pendingDeleteMemoryIndex by remember { mutableStateOf<Int?>(null) }
    var showSettings by remember { mutableStateOf(false) }
    var expandedSettingsFolder by remember { mutableStateOf("") }
    var localModel by remember { mutableStateOf(LocalModelManager.load(context)) }
    var localModelStatus by remember { mutableStateOf("Selecciona el motor de IA de JARVIS o importa un modelo GGUF compatible.") }
    val jarvisScope = rememberCoroutineScope()
    var llamaReady by remember { mutableStateOf(LocalLlamaRuntime.isLoaded()) }
    var llamaLoading by remember { mutableStateOf(false) }
    var llamaBusy by remember { mutableStateOf(false) }
    var qwenDownloaded by remember { mutableStateOf(Qwen3ModelDownloader.isDownloaded(context)) }
    var qwenDownloading by remember { mutableStateOf(false) }
    var qwenDownloadProgress by remember { mutableIntStateOf(0) }

    // Back gesture: nested settings -> settings root -> chat, without closing JARVIS.
    BackHandler(enabled = !showSplash && showSettings) {
        if (expandedSettingsFolder.isNotBlank()) expandedSettingsFolder = "" else showSettings = false
    }
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
    var focusRunning by remember { mutableStateOf(false) }
    var focusSeconds by remember { mutableIntStateOf(25 * 60) }
    var focusSessions by remember { mutableIntStateOf(context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE).getInt("focus_sessions", 0)) }
    LaunchedEffect(focusRunning) {
        while (focusRunning && focusSeconds > 0) {
            delay(1000)
            focusSeconds = (focusSeconds - 1).coerceAtLeast(0)
            if (focusSeconds == 0) {
                focusRunning = false
                focusSessions += 1
                context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE).edit().putInt("focus_sessions", focusSessions).apply()
            }
        }
    }
    var reminderPermissionGranted by remember { mutableStateOf(android.os.Build.VERSION.SDK_INT < 33 || androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED) }
    var showNotificationPermissionInfo by remember { mutableStateOf(false) }
    val reminderPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        reminderPermissionGranted = granted
        if (!granted) codeGuardStatus = "Notificaciones no autorizadas. JARVIS seguirá funcionando; los recordatorios no mostrarán avisos."
    }

    val localModelPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try {
                val selected = LocalModelManager.remember(context, uri)
                localModel = selected
                localModelStatus = "Archivo seleccionado: ${selected.displayName}. Pulsa «Cargar modelo en JARVIS» para importarlo y abrirlo."
            } catch (error: Exception) {
                localModelStatus = error.message ?: "No se pudo seleccionar el modelo GGUF."
            }
        }
    }

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
                if (codeWorkspaces.size < 20) {
                    val item = CodeWorkspace(java.util.UUID.randomUUID().toString(), name.take(60), source)
                    codeWorkspaces = codeWorkspaces + item
                    CodeWorkspaces.save(context, codeWorkspaces)
                    codeGuardStatus = "Archivo analizado y guardado en un espacio separado. No se ejecutó."
                } else codeGuardStatus = "Analizado, pero hay 20 espacios; elimina uno para guardar otro."
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
            val selfReportIntent = lower.contains("dime lo que haces") ||
                lower.contains("que puedes hacer") ||
                lower.contains("qué puedes hacer") ||
                lower.contains("estado de tus programas") ||
                lower.contains("informe del sistema")
            val reply = if (selfReportIntent) {
                buildString {
                    appendLine("¡Hola! Soy JARVIS. Este es mi informe de estado.")
                    appendLine()
                    appendLine("FUNCIONES DISPONIBLES:")
                    appendLine("• Memoria local: guardar, consultar y organizar recuerdos.")
                    appendLine("• Biblioteca: importar y buscar documentos.")
                    appendLine("• Investigación: revisar documentos importados.")
                    appendLine("• Code Guard: analizar código y mostrar advertencias.")
                    appendLine("• JavaScript: ejecutar código compatible después de tu confirmación.")
                    appendLine("• Extensiones: importar y administrar extensiones declarativas.")
                    appendLine("• Tareas: crear, consultar, completar y eliminar tareas.")
                    appendLine("• Metas y rutinas: organizar objetivos y hábitos.")
                    appendLine("• Planificador y temporizador: organizar actividades y sesiones de concentración.")
                    appendLine("• Seguridad: configurar un PIN local.")
                    appendLine("• Recordatorios: programar avisos si Android permite las notificaciones.")
                    appendLine()
                    appendLine("ESTADO REGISTRADO EN ESTE MOMENTO:")
                    appendLine("• Recuerdos guardados: ${memories.size}")
                    appendLine("• Documentos en biblioteca: ${knowledgeDocs.size}")
                    appendLine("• Espacios de código: ${codeWorkspaces.size}")
                    appendLine("• Extensiones importadas: ${extensions.size}")
                    appendLine("• Tareas pendientes: ${tasks.count { !it.completed }}")
                    appendLine("• Tareas completadas: ${tasks.count { it.completed }}")
                    appendLine("• Metas: ${goals.size}")
                    appendLine("• Rutinas: ${routines.size}")
                    appendLine("• Ejecuciones de código registradas: ${codeRunResults.size}")
                    appendLine("• Ejecuciones en curso: ${codeRunningIds.size}")
                    appendLine("• PIN configurado: ${if (pinConfigured) "Sí" else "No"}")
                    appendLine("• Permiso de notificaciones: ${if (reminderPermissionGranted) "Concedido" else "No concedido"}")
                    appendLine()
                    appendLine("LIMITACIONES Y COMPROBACIÓN:")
                    appendLine("• IA local llama.cpp: ${if (llamaReady) "modelo cargado" else "sin modelo cargado"}.")
                    appendLine("• Este informe muestra datos registrados; no ejecuta una prueba técnica de cada módulo.")
                    appendLine("• No puedo asegurar que todo funcione correctamente sin realizar pruebas.")
                }
            } else if (taskCreateIntent) {
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
                            matches.joinToString("\n\n") { match -> "📄 " + match.document.name + "\n" + match.excerpt } +
                            "\\n\\nBúsqueda local: no se ejecutó ningún archivo."
                    }
                }
            } else {
                KnowledgeLibrary.answerQuestion(context, msg)
                    ?: if (llamaReady) "__JARVIS_LOCAL_LLM__" else "Recibí tu mensaje y lo guardé en este dispositivo. Carga un modelo GGUF desde Ajustes > Inteligencia Artificial Local para activar las respuestas de IA."
            }
            if (reply == "__JARVIS_LOCAL_LLM__") {
                val thinking = Line("JARVIS", "JARVIS está pensando…")
                val updated = lines + Line("TÚ", msg) + thinking
                lines = updated
                saveHistory(context, updated)
                input = ""
                keyboard?.hide()
                val recentContext = lines.dropLast(2).takeLast(8).joinToString("\n") { "${it.who}: ${it.text}".take(700) }
                val memoryContext = memories.takeLast(18).joinToString("\n") { "• ${it.category}: ${it.text}".take(500) }
                val documentContext = runCatching {
                    KnowledgeLibrary.search(context, msg, 3).joinToString("\n") { "• ${it.document.name}: ${it.excerpt}".take(900) }
                }.getOrDefault("")
                val enrichedPrompt = buildString {
                    if (memoryContext.isNotBlank()) append("RECUERDOS LOCALES DEL USUARIO:\\n").append(memoryContext).append("\n\n")
                    if (documentContext.isNotBlank()) append("FRAGMENTOS RELEVANTES DE LA BIBLIOTECA:\\n").append(documentContext).append("\n\n")
                    if (recentContext.isNotBlank()) append("CONVERSACIÓN RECIENTE:\\n").append(recentContext).append("\n\n")
                    append("MENSAJE ACTUAL DEL USUARIO:\\n").append(msg)
                }
                jarvisScope.launch {
                    llamaBusy = true
                    try {
                        val answer = LocalLlamaRuntime.generate(enrichedPrompt)
                        val latest = lines.toMutableList()
                        val placeholderIndex = latest.indexOfLast { it.who == "JARVIS" && it.text == thinking.text }
                        if (placeholderIndex >= 0) latest[placeholderIndex] = Line("JARVIS", answer)
                        lines = latest
                        saveHistory(context, latest)
                    } catch (error: Exception) {
                        val latest = lines.toMutableList()
                        val placeholderIndex = latest.indexOfLast { it.who == "JARVIS" && it.text == thinking.text }
                        val message = error.message ?: "No se pudo generar una respuesta con el modelo local."
                        if (placeholderIndex >= 0) latest[placeholderIndex] = Line("JARVIS", "JARVIS no pudo generar la respuesta: $message")
                        lines = latest
                        saveHistory(context, latest)
                    } finally {
                        llamaBusy = false
                    }
                }
            } else {
                val updated = lines + Line("TÚ", msg) + Line("JARVIS", reply)
                lines = updated
                saveHistory(context, updated)
                input = ""
                keyboard?.hide()
            }
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
                        HolographicOrb(size = 166.dp)
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
                    Text("JARVIS  •  3.7", color = Color(0xFF60798D), fontSize = 10.sp, letterSpacing = 1.5.sp)
                }
            }
        } else {
            Column(Modifier.fillMaxSize().background(bg).padding(16.dp)) {
                if (!showSettings) {
                Surface(color = panel, shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth()) {
                    Box(Modifier.background(Brush.horizontalGradient(listOf(Color(0xFF173B55), Color(0xFF142238), Color(0xFF102033))))) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 13.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier.size(66.dp).background(
                                    Brush.radialGradient(listOf(Color(0xFF34758A), Color(0xFF17364D))),
                                    CircleShape
                                ),
                                contentAlignment = Alignment.Center
                            ) {
                                Image(
                                    painterResource(R.drawable.jarvis_logo),
                                    contentDescription = "Logo de JARVIS",
                                    modifier = Modifier.size(56.dp).clip(CircleShape)
                                )
                            }
                            Spacer(Modifier.width(13.dp))
                            Column(Modifier.weight(1f)) {
                                Text("J.A.R.V.I.S.", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.8.sp)
                                Text(if (showSettings) "CENTRO DE CONFIGURACIÓN" else "ASISTENTE PERSONAL", color = cyan, fontSize = 9.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.2.sp)
                                Spacer(Modifier.height(7.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(Modifier.size(7.dp).background(Color(0xFF6EE7A8), CircleShape))
                                    Spacer(Modifier.width(6.dp))
                                    Text("MEMORIA LOCAL ACTIVA", color = Color(0xFFB8C9D8), fontSize = 9.sp, letterSpacing = 0.7.sp)
                                }
                                Spacer(Modifier.height(4.dp))
                                Text("VERSIÓN 3.7", color = Color(0xFF7895AA), fontSize = 9.sp, letterSpacing = 1.1.sp)
                            }
                            if (showSettings) {
                                TextButton(onClick = { showSettings = false }) { Text("‹ Chat", color = cyan, fontSize = 12.sp) }
                            } else {
                                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                    TextButton(onClick = { showSettings = true }) {
                                        Text("⚙", color = cyan, fontSize = 23.sp)
                                    }
                                    TextButton(onClick = { showClearDialog = true }, contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)) {
                                        Text("Limpiar", color = Color(0xFFFFA0A0), fontSize = 10.sp)
                                    }
                                }
                            }
                        }
                    }
                }
                }
                Spacer(Modifier.height(12.dp))
                if (showSettings) {
                    Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Configuración", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                            Text(if (expandedSettingsFolder.isBlank()) "Selecciona una sección." else "Sección abierta", color = Color(0xFF9CB4C7), fontSize = 12.sp)
                        }
                        TextButton(onClick = { if (expandedSettingsFolder.isNotBlank()) expandedSettingsFolder = "" else showSettings = false }) {
                            Text(if (expandedSettingsFolder.isNotBlank()) "‹ Carpetas" else "‹ Chat", color = cyan)
                        }
                    }
                    if (expandedSettingsFolder.isBlank()) {
                        Text("Todo en su lugar: herramientas, memoria, productividad y protección.", color = Color(0xFF9CB4C7), fontSize = 13.sp)
                        Spacer(Modifier.height(8.dp))
                        Surface(color = Color(0xFF0D1A29), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                            Text("Toca una carpeta para entrar y ver su contenido en una pantalla propia.", color = Color(0xFFB8C9D8), fontSize = 11.sp, modifier = Modifier.padding(12.dp))
                        }
                        Spacer(Modifier.height(14.dp))
                    } else {
                        Text(
                            when (expandedSettingsFolder) {
                                "ai" -> "INTELIGENCIA ARTIFICIAL LOCAL"
                                "lab" -> "LABORATORIO Y PROYECTOS"
                                "memory" -> "MEMORIA Y CONOCIMIENTO"
                                "tools" -> "PERSONALIZACIÓN Y HERRAMIENTAS"
                                "productivity" -> "PRODUCTIVIDAD Y PLANIFICACIÓN"
                                else -> "SEGURIDAD Y ESTADO DEL ASISTENTE"
                            },
                            color = cyan, fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp
                        )
                        Spacer(Modifier.height(12.dp))
                    }
                    if (expandedSettingsFolder.isBlank()) SettingsGroupHeader("✧", "INTELIGENCIA ARTIFICIAL LOCAL", "Preparación de modelos GGUF y llama.cpp", expandedSettingsFolder == "ai", { expandedSettingsFolder = if (expandedSettingsFolder == "ai") "" else "ai" })
                    if (expandedSettingsFolder.isBlank()) SettingsGroupHeader("⌘", "LABORATORIO Y PROYECTOS", "Código, espacios de trabajo y desarrollo", expandedSettingsFolder == "lab", { expandedSettingsFolder = if (expandedSettingsFolder == "lab") "" else "lab" })
                    Spacer(Modifier.height(9.dp))
                    if (expandedSettingsFolder == "ai") {
                    Surface(color = panel, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            Text("MODELOS LOCALES · GGUF", color = cyan, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                            Spacer(Modifier.height(8.dp))
                            Text("JARVIS utiliza llama.cpp para ejecutar modelos GGUF localmente. Puedes instalar el modelo recomendado directamente o importar otro archivo compatible.", color = Color(0xFFB8C9D8), fontSize = 12.sp)
                            Spacer(Modifier.height(12.dp))
                            Surface(color = Color(0xFF0D1A29), shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(12.dp)) {
                                    Text("MOTOR DE INTELIGENCIA ARTIFICIAL · JARVIS", color = cyan, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp)
                                    Spacer(Modifier.height(5.dp))
                                    Text("Q4_K_M · GGUF · ${Qwen3ModelDownloader.MODEL_SIZE_LABEL}", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                    Text("Descarga manual iniciada por ti. El archivo se guarda en el almacenamiento privado de JARVIS y funciona sin conexión una vez instalado. Se recomienda Wi-Fi y espacio libre adicional.", color = Color(0xFFB8C9D8), fontSize = 11.sp)
                                    if (qwenDownloading) {
                                        Spacer(Modifier.height(8.dp))
                                        LinearProgressIndicator(
                                            progress = { qwenDownloadProgress / 100f },
                                            modifier = Modifier.fillMaxWidth(),
                                            color = cyan
                                        )
                                        Text("Descargando: $qwenDownloadProgress%", color = cyan, fontSize = 11.sp)
                                    }
                                    Spacer(Modifier.height(10.dp))
                                    Button(
                                        onClick = {
                                            if (!qwenDownloading && !llamaLoading && !llamaBusy) {
                                                jarvisScope.launch {
                                                    qwenDownloading = !qwenDownloaded
                                                    llamaLoading = true
                                                    qwenDownloadProgress = if (qwenDownloaded) 100 else 0
                                                    try {
                                                        val modelFile = if (qwenDownloaded) {
                                                            Qwen3ModelDownloader.localFile(context)
                                                        } else {
                                                            Qwen3ModelDownloader.download(context) { progress ->
                                                                runOnUiThread {
                                                                    qwenDownloadProgress = progress
                                                                    localModelStatus = "Descargando el motor de IA de JARVIS: $progress%. Mantén la aplicación abierta y evita interrumpir la conexión."
                                                                }
                                                            }.also { qwenDownloaded = true }
                                                        }
                                                        qwenDownloading = false
                                                        localModelStatus = "Descarga verificada. Inicializando el motor de inteligencia artificial de JARVIS…"
                                                        localModelStatus = LocalLlamaRuntime.loadDownloaded(
                                                            context,
                                                            modelFile,
                                                            "Eres JARVIS, el asistente de inteligencia artificial de esta aplicación. Tu identidad y nombre visible son siempre JARVIS. Responde en español cuando el usuario escriba en español. Razona cuidadosamente, explica de forma clara y concisa, utiliza el contexto y los recuerdos que se adjunten, no inventes datos y reconoce tus límites. No afirmes haber realizado acciones que no ejecutaste."
                                                        )
                                                        llamaReady = true
                                                    } catch (error: Exception) {
                                                        llamaReady = false
                                                        localModelStatus = error.message ?: "No se pudo descargar o iniciar el motor de IA de JARVIS."
                                                    } finally {
                                                        qwenDownloading = false
                                                        llamaLoading = false
                                                    }
                                                }
                                            }
                                        },
                                        enabled = !qwenDownloading && !llamaLoading && !llamaBusy,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text(
                                            when {
                                                qwenDownloading -> "Descargando motor de JARVIS…"
                                                llamaLoading -> "Iniciando JARVIS…"
                                                qwenDownloaded -> "Iniciar motor de JARVIS"
                                                else -> "Instalar inteligencia artificial de JARVIS"
                                            }
                                        )
                                    }
                                }
                            }
                            Spacer(Modifier.height(12.dp))
                            if (localModel != null) {
                                Text("MODELO SELECCIONADO", color = cyan, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                Text(localModel!!.displayName, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                                Text("Ubicación guardada mediante el selector seguro de Android.", color = Color(0xFF9CB4C7), fontSize = 11.sp)
                                TextButton(onClick = {
                                    LocalModelManager.clear(context)
                                    localModel = null
                                    localModelStatus = "No hay ningún archivo GGUF seleccionado."
                                }) { Text("Quitar selección", color = Color(0xFFFFA0A0)) }
                            } else {
                                Text("Todavía no has seleccionado un archivo GGUF.", color = Color(0xFF9CB4C7), fontSize = 12.sp)
                            }
                            Button(onClick = { localModelPicker.launch(arrayOf("*/*")) }, modifier = Modifier.fillMaxWidth()) {
                                Text(if (localModel == null) "Seleccionar archivo GGUF" else "Cambiar archivo GGUF")
                            }
                            Spacer(Modifier.height(8.dp))
                            Button(
                                onClick = {
                                    val selected = localModel
                                    if (selected != null && !llamaLoading) {
                                        jarvisScope.launch {
                                            llamaLoading = true
                                            localModelStatus = "Preparando el modelo GGUF y cargando llama.cpp. La primera carga puede tardar."
                                            try {
                                                localModelStatus = LocalLlamaRuntime.load(
                                                    context,
                                                    selected.uri,
                                                    selected.displayName,
                                                    "Eres JARVIS, un asistente local útil, claro y honesto. Responde en español cuando el usuario escriba en español. Usa los recuerdos y el contexto que acompañan cada mensaje, no inventes datos y reconoce cuando no sabes algo. Mantén las respuestas naturales, útiles y respetuosas."
                                                )
                                                llamaReady = true
                                            } catch (error: Exception) {
                                                llamaReady = false
                                                localModelStatus = error.message ?: "No se pudo cargar el modelo GGUF."
                                            } finally {
                                                llamaLoading = false
                                            }
                                        }
                                    }
                                },
                                enabled = localModel != null && !llamaLoading,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(if (llamaLoading) "Cargando modelo…" else if (llamaReady) "Volver a cargar modelo" else "Cargar modelo en JARVIS")
                            }
                            Spacer(Modifier.height(8.dp))
                            Surface(color = Color(0xFF0D1A29), shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(10.dp)) {
                                    Text("ESTADO DEL MOTOR", color = cyan, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                    Text(if (llamaReady) "llama.cpp está cargado y el modelo local está listo para responder en el chat." else "Motor nativo llama.cpp integrado. Selecciona un GGUF y pulsa Cargar modelo en JARVIS.", color = if (llamaReady) Color(0xFF8BE0A4) else Color(0xFFFFD28A), fontSize = 12.sp)
                                }
                            }
                            if (localModelStatus.isNotBlank()) {
                                Spacer(Modifier.height(8.dp))
                                Text(localModelStatus, color = Color(0xFF9CB4C7), fontSize = 11.sp)
                            }
                            Text("La primera carga copia el archivo a los datos privados de JARVIS y necesita espacio libre adicional. El modelo original de PocketPal no se modifica. La inferencia se ejecuta localmente.", color = Color(0xFF9CB4C7), fontSize = 10.sp)
                        }
                    }
                    }
                    if (expandedSettingsFolder == "lab") {
                    Surface(color = panel, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            Text("CODE GUARD · ESPACIOS INDEPENDIENTES", color = cyan, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                            Spacer(Modifier.height(6.dp))
                            Text("Pega o importa JavaScript, Python o Java. JARVIS detectará el lenguaje y seleccionará automáticamente el motor disponible. Pulsa Guardar y ejecutar para revisar el código antes de confirmar.", color = Color(0xFFB8C9D8), fontSize = 12.sp)
                            Spacer(Modifier.height(10.dp))
                            Text("1. CÓDIGO", color = cyan, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            OutlinedTextField(value = codeWorkspaceSource, onValueChange = { if (it.length <= 200000) codeWorkspaceSource = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Pega aquí el código") }, placeholder = { Text("El código aparecerá aquí…") }, minLines = 7, maxLines = 12)
                            OutlinedButton(onClick = {
                                codeWorkspaceSource = """
(() => {
  const checks = [];
  const test = (name, fn) => { try { checks.push({name, ok: !!fn()}); } catch (e) { checks.push({name, ok:false, error:e.message}); } };
  test("Matemáticas", () => 10 + 5 === 15);
  test("Variables", () => { let n = 20; return n === 20; });
  test("Funciones", () => ((a,b) => a+b)(7,8) === 15);
  test("Arreglos", () => [1,2,3].length === 3);
  test("Objetos", () => ({name:"JARVIS"}).name === "JARVIS");
  test("JSON", () => JSON.parse(JSON.stringify({active:true})).active === true);
  test("Condiciones", () => 10 > 5 && 5 < 20);
  test("Manejo de errores", () => { try { throw new Error("prueba"); } catch(e) { return e.message === "prueba"; } });
  const passed = checks.filter(x => x.ok).length;
  checks.forEach(x => console.log((x.ok ? "CORRECTO — " : "FALLO — ") + x.name + (x.error ? ": " + x.error : "")));
  console.log("DIAGNÓSTICO JAVASCRIPT: " + passed + "/" + checks.length);
  console.log(passed === checks.length ? "PRUEBAS SUPERADAS" : "SE DETECTARON FALLOS");
  return "Diagnóstico terminado";
})();
""".trimIndent()
                                codeGuardStatus = "Prueba de diagnóstico cargada. Pulsa Guardar y ejecutar."
                            }, modifier = Modifier.fillMaxWidth()) { Text("Cargar prueba de diagnóstico") }
                            Button(onClick = {
                                try {
                                    require(codeWorkspaceSource.isNotBlank()) { "Pega código o carga la prueba de diagnóstico." }
                                    require(codeWorkspaceSource.length <= 50000) { "El límite del motor JavaScript es 50 KB por ejecución." }
                                    val baseName = "Código " + (codeWorkspaces.size + 1)
                                    val existing = codeWorkspaces.firstOrNull { it.name == baseName }
                                    val item = CodeWorkspace(existing?.id ?: java.util.UUID.randomUUID().toString(), baseName, codeWorkspaceSource)
                                    val updatedWorkspaces = if (existing == null) codeWorkspaces + item else codeWorkspaces.map { if (it.id == existing.id) item else it }
                                    CodeWorkspaces.save(context, updatedWorkspaces)
                                    codeWorkspaces = updatedWorkspaces
                                    codeGuardStatus = "Código guardado. Revisa la confirmación para ejecutarlo."
                                    pendingCodeRun = item
                                } catch (error: Exception) { codeGuardStatus = error.message ?: "No se pudo guardar el código." }
                            }, enabled = codeWorkspaceSource.isNotBlank() && codeWorkspaces.size < 20, modifier = Modifier.fillMaxWidth()) { Text("Guardar y ejecutar") }
                            Button(onClick = { codeGuardPicker.launch(arrayOf("*/*")) }, modifier = Modifier.fillMaxWidth()) { Text("O importar un archivo") }
                            Button(onClick = { codeGuardPicker.launch(arrayOf("*/*")) }, modifier = Modifier.fillMaxWidth()) { Text("Importar archivo como espacio separado") }
                            if (codeGuardStatus.isNotBlank()) Text(codeGuardStatus, color = cyan, fontSize = 11.sp)
                            Text("Espacios guardados: " + codeWorkspaces.size + " / 20", color = cyan, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(8.dp))
                            Surface(color = Color(0xFF0D1A29), shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth()) { Column(Modifier.padding(10.dp)) {
                                Text("ESTADO DE EJECUCIÓN", color = cyan, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                Text("Enrutador central activo · JavaScript y Python 3.12 disponibles · Java mediante intérprete Java-like. Los motores Python y Java se ejecutan en un proceso Android aislado con límite de tiempo.", color = Color(0xFFFFD28A), fontSize = 12.sp)
                                Text("Cada ejecución requiere revisión y confirmación. La prueba está limitada: sin acceso a datos personales, archivos del teléfono, red ni procesos del sistema.", color = Color(0xFFB8C9D8), fontSize = 11.sp)
                            } }
                            codeWorkspaces.forEach { workspace ->
                                Surface(color = Color(0xFF0D1A29), shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth()) { Column(Modifier.padding(10.dp)) {
                                    Text(workspace.name, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                    Text(workspace.source.length.toString() + " caracteres · aislado de los demás espacios", color = Color(0xFF8FAABD), fontSize = 10.sp)
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        TextButton(onClick = { codeWorkspaceName = workspace.name; codeWorkspaceSource = workspace.source }) { Text("Editar", color = cyan) }
                                        TextButton(onClick = { pendingCodeRun = workspace }) { Text(if (workspace.id in codeRunningIds) "Ejecutando…" else "Ejecutar", color = Color(0xFF9FE6B8)) }
                                        TextButton(onClick = {
                                            try {
                                                val report = CodeGuardAnalyzer.analyze(workspace.name, workspace.source)
                                                codeWorkspaceReports = codeWorkspaceReports + (workspace.id to report)
                                                codeGuardStatus = "Análisis terminado: ${report.language}, ${report.analyzedLines} líneas revisadas."
                                            } catch (error: Exception) {
                                                codeGuardStatus = "Error al analizar: ${error.message ?: "no se pudo completar la revisión"}"
                                                codeWorkspaceReports = codeWorkspaceReports - workspace.id
                                            }
                                        }) { Text("Analizar", color = cyan) }
                                        TextButton(onClick = { codeWorkspaces = codeWorkspaces.filterNot { it.id == workspace.id }; CodeWorkspaces.save(context, codeWorkspaces); codeWorkspaceReports = codeWorkspaceReports - workspace.id; if (selectedCodeA == workspace.id) selectedCodeA = ""; if (selectedCodeB == workspace.id) selectedCodeB = "" }) { Text("Eliminar", color = Color(0xFFFF9B9B)) }
                                    }
                                    codeRunResults[workspace.id]?.let { result ->
                                        Spacer(Modifier.height(6.dp))
                                        Text(result.output, color = if (result.success) Color(0xFF9FE6B8) else Color(0xFFFFB4A2), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                        Text("Motor: ${result.engine} · ${result.durationMs} ms", color = cyan, fontSize = 10.sp)
                                        if (result.explanation.isNotBlank()) {
                                            TextButton(onClick = { codeRunDetails = if (workspace.id in codeRunDetails) codeRunDetails - workspace.id else codeRunDetails + workspace.id }) { Text(if (workspace.id in codeRunDetails) "Ocultar resultado" else "Ver resultado", color = cyan, fontSize = 11.sp) }
                                            if (workspace.id in codeRunDetails) {
                                                Text("Lenguaje: ${result.language}", color = Color(0xFFB8C9D8), fontSize = 11.sp)
                                                Text("Comprobaciones: ${result.checks}", color = Color(0xFFB8C9D8), fontSize = 11.sp)
                                                Text(result.explanation, color = Color(0xFFB8C9D8), fontSize = 11.sp)
                                            }
                                        }
                                    }
                                    codeWorkspaceReports[workspace.id]?.let { report ->
                                        Text("Análisis: " + report.language + " · confianza " + report.confidence + " · " + report.analyzedLines + " líneas", color = cyan, fontSize = 11.sp)
                                        Text(report.summary, color = Color(0xFFFFD28A), fontSize = 11.sp)
                                        report.findings.take(12).forEach { finding -> Text("Línea " + finding.line + " · " + finding.risk + " · " + finding.title + ": " + finding.explanation, color = Color(0xFFB8C9D8), fontSize = 10.sp) }
                                        if (report.findings.isEmpty()) Text("Sin coincidencias; no demuestra que sea seguro.", color = Color(0xFFFFD28A), fontSize = 10.sp)
                                    }
                                } }
                            }
                            Spacer(Modifier.height(8.dp))
                            Text("COMPARAR POSIBLES CONFLICTOS", color = cyan, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            var compareAExpanded by remember { mutableStateOf(false) }
                            var compareBExpanded by remember { mutableStateOf(false) }
                            Box { OutlinedButton(onClick = { compareAExpanded = true }, modifier = Modifier.fillMaxWidth()) { Text("Espacio A: " + (codeWorkspaces.firstOrNull { it.id == selectedCodeA }?.name ?: "Seleccionar")) }; DropdownMenu(expanded = compareAExpanded, onDismissRequest = { compareAExpanded = false }) { codeWorkspaces.forEach { item -> DropdownMenuItem(text = { Text(item.name) }, onClick = { selectedCodeA = item.id; compareAExpanded = false }) } } }
                            Box { OutlinedButton(onClick = { compareBExpanded = true }, modifier = Modifier.fillMaxWidth()) { Text("Espacio B: " + (codeWorkspaces.firstOrNull { it.id == selectedCodeB }?.name ?: "Seleccionar")) }; DropdownMenu(expanded = compareBExpanded, onDismissRequest = { compareBExpanded = false }) { codeWorkspaces.forEach { item -> DropdownMenuItem(text = { Text(item.name) }, onClick = { selectedCodeB = item.id; compareBExpanded = false }) } } }
                            Button(onClick = { val first = codeWorkspaces.firstOrNull { it.id == selectedCodeA }; val second = codeWorkspaces.firstOrNull { it.id == selectedCodeB }; codeComparison = if (first == null || second == null) listOf("Selecciona dos espacios guardados.") else if (first.id == second.id) listOf("Selecciona dos espacios diferentes.") else CodeWorkspaces.compare(first, second) }, enabled = codeWorkspaces.size >= 2, modifier = Modifier.fillMaxWidth()) { Text("Comparar compatibilidad") }
                            codeComparison.forEach { Text("• " + it, color = Color(0xFFFFD28A), fontSize = 11.sp) }
                            Text("El comparador usa patrones de texto: puede omitir conflictos o señalar coincidencias que no son errores. No compila, ejecuta ni fusiona código.", color = Color(0xFFFFD28A), fontSize = 10.sp)
                        }
                    }
                    }
                    Spacer(Modifier.height(18.dp))
                    if (expandedSettingsFolder.isBlank()) SettingsGroupHeader("◈", "MEMORIA Y CONOCIMIENTO", "Recuerdos, documentos y búsqueda local", expandedSettingsFolder == "memory", { expandedSettingsFolder = if (expandedSettingsFolder == "memory") "" else "memory" })
                    Spacer(Modifier.height(9.dp))
                    if (expandedSettingsFolder == "memory") {
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
                            Text("BIBLIOTECA DE CONOCIMIENTO", color = cyan, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
                            Spacer(Modifier.height(6.dp))
                            Text("Guarda documentos o escribe datos en formato clave: valor, por ejemplo «nombre: Sebastián». Después pregunta en el chat «¿Cuál es mi nombre?» y JARVIS consultará esta Biblioteca automáticamente, sin que tengas que pedir una búsqueda.", color = Color(0xFFB8C9D8), fontSize = 13.sp)
                            Spacer(Modifier.height(8.dp))
                            Text("Formatos: TXT, MD, JSON, XML y CSV. Los archivos de código se importan únicamente desde Code Guard. Máximo 512 KB por archivo y 5 MB en total.", color = Color(0xFF8FAABD), fontSize = 11.sp)
                            Button(onClick = { knowledgePicker.launch(arrayOf("text/plain", "text/markdown", "text/csv", "application/json", "application/xml")) }, modifier = Modifier.fillMaxWidth()) { Text("Importar documento") }
                            OutlinedTextField(value = knowledgeManualText, onValueChange = { if (it.length <= 512 * 1024) knowledgeManualText = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Datos: nombre: Sebastián") }, minLines = 4, maxLines = 8)
                            Button(onClick = { try { require(knowledgeManualText.isNotBlank()) { "Escribe o pega texto antes de guardarlo." }; val added = KnowledgeLibrary.import(context, "nota-manual-" + System.currentTimeMillis() + ".txt", java.io.ByteArrayInputStream(knowledgeManualText.toByteArray(Charsets.UTF_8))); knowledgeDocs = KnowledgeLibrary.list(context); knowledgeManualText = ""; knowledgeStatus = "Texto guardado: " + added.name; knowledgeResults = emptyList() } catch (error: Exception) { knowledgeStatus = error.message ?: "No se pudo guardar el texto." } }, enabled = knowledgeManualText.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("Guardar texto en Biblioteca") }
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
                    }
                    Spacer(Modifier.height(18.dp))
                    if (expandedSettingsFolder.isBlank()) SettingsGroupHeader("✧", "PERSONALIZACIÓN Y HERRAMIENTAS", "Extensiones y funciones complementarias", expandedSettingsFolder == "tools", { expandedSettingsFolder = if (expandedSettingsFolder == "tools") "" else "tools" })
                    Spacer(Modifier.height(9.dp))
                    if (expandedSettingsFolder == "tools") {
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
                    }
                    Spacer(Modifier.height(12.dp))
                    if (expandedSettingsFolder.isBlank()) SettingsGroupHeader("◷", "PRODUCTIVIDAD Y PLANIFICACIÓN", "Tareas, agenda, rutinas y objetivos", expandedSettingsFolder == "productivity", { expandedSettingsFolder = if (expandedSettingsFolder == "productivity") "" else "productivity" })
                    if (expandedSettingsFolder == "productivity") {
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
                            if (!reminderPermissionGranted && android.os.Build.VERSION.SDK_INT >= 33) { TextButton(onClick = { showNotificationPermissionInfo = true }) { Text("Configurar notificaciones") } }
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
                                            Column(Modifier.weight(1f)) {
                                                Text(routine.title, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                                Text(routine.cadence + " · " + routine.completedDates.size + " registro(s) histórico(s)", color = Color(0xFF9CB4C7), fontSize = 10.sp)
                                            }
                                            TextButton(onClick = { routines = routines.filterNot { it.id == routine.id }; saveRoutines(context, routines) }) { Text("Quitar", color = Color(0xFFFF9B9B), fontSize = 10.sp) }
                                        }
                                        Button(onClick = {
                                            routines = routines.map { item ->
                                                if (item.id == routine.id) item.copy(completedDates = if (doneToday) item.completedDates.filterNot { it == todayKey } else (item.completedDates + todayKey).distinct().takeLast(365)) else item
                                            }
                                            saveRoutines(context, routines)
                                        }, modifier = Modifier.fillMaxWidth()) { Text(if (doneToday) "✓ Hecho hoy · Deshacer" else "Marcar como realizada hoy") }
                                        Text("Últimos 7 días: " + lastWeek + " realización(es)", color = cyan, fontSize = 11.sp)
                                    }
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Surface(color = panel, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
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
                                            Column(Modifier.weight(1f)) {
                                                Text(goal.title, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                                Text(goal.category + " · " + goal.taskIds.size + " tarea(s) vinculada(s)", color = Color(0xFF9CB4C7), fontSize = 10.sp)
                                            }
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
                    }

                    if (expandedSettingsFolder.isBlank()) SettingsGroupHeader("◇", "SEGURIDAD Y ESTADO DEL ASISTENTE", "Protección del dispositivo y conexión de IA", expandedSettingsFolder == "security", { expandedSettingsFolder = if (expandedSettingsFolder == "security") "" else "security" })
                    if (expandedSettingsFolder == "security") {
                        Surface(color = panel, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp)) {
                                Text("SEGURIDAD DEL DISPOSITIVO", color = cyan, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
                                Spacer(Modifier.height(7.dp))
                                Text(if (pinConfigured) "Código de seguridad configurado en este dispositivo." else "Configura un código de 6 dígitos para proteger futuras funciones.", color = Color(0xFFB8C9D8), fontSize = 13.sp)
                                Spacer(Modifier.height(10.dp))
                                Button(onClick = { pinCurrent = ""; pinFirst = ""; pinSecond = ""; pinError = ""; showPinSetup = true }, modifier = Modifier.fillMaxWidth()) { Text(if (pinConfigured) "Cambiar código de seguridad" else "Configurar código de seguridad") }
                                Spacer(Modifier.height(14.dp))
                                Text("ESTADO DE IA", color = cyan, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
                                Spacer(Modifier.height(6.dp))
                                Text("Los servicios externos de inteligencia artificial todavía no están conectados a esta versión.", color = Color(0xFFB8C9D8), fontSize = 13.sp)
                            }
                        }
                    }                    }                } else {
                    Surface(color = Color(0xFF0D1A29), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                        Text("Tu espacio personal · Los mensajes se guardan en este dispositivo", color = Color(0xFF8FAABD), fontSize = 10.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp))
                    }
                    Spacer(Modifier.height(12.dp))
                    Text("CONVERSACIÓN", color = cyan, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
                    Text("Escribe tu mensaje abajo para comenzar o continuar hablando con JARVIS.", color = Color(0xFF9CB4C7), fontSize = 11.sp)
                    Spacer(Modifier.height(8.dp))
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
                    Text("JARVIS 3.3 · Modo local", color = Color.Gray, fontSize = 11.sp)
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


        if (showNotificationPermissionInfo) {
            AlertDialog(
                onDismissRequest = { showNotificationPermissionInfo = false },
                title = { Text("Permiso de notificaciones") },
                text = { Text("Android mostrará su propio aviso para permitir que JARVIS envíe notificaciones de recordatorios. Este permiso solo permite mostrar avisos; no da acceso a contactos, mensajes, archivos, cuentas, cámara ni micrófono. Puedes rechazarlo y seguir usando JARVIS, pero no recibirás notificaciones.") },
                confirmButton = {
                    TextButton(onClick = {
                        showNotificationPermissionInfo = false
                        if (android.os.Build.VERSION.SDK_INT >= 33) reminderPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                    }) { Text("Continuar con Android") }
                },
                dismissButton = { TextButton(onClick = { showNotificationPermissionInfo = false }) { Text("Ahora no") } }
            )
        }

        if (pendingCodeRun != null) {
            val candidate = pendingCodeRun!!
            val report = remember(candidate.id, candidate.source) { CodeGuardAnalyzer.analyze(candidate.name, candidate.source) }
            val asksForPhoneData = Regex("(?i)(READ_CONTACTS|WRITE_CONTACTS|READ_SMS|RECEIVE_SMS|READ_CALL_LOG|ACCESS_FINE_LOCATION|ACCESS_COARSE_LOCATION|READ_PHONE_STATE|READ_MEDIA_|CAMERA|RECORD_AUDIO|ContactsContract|Telephony\\.Sms|MediaStore|ContentResolver|navigator\\.geolocation|getUserMedia|microphone|ubicaci[oó]n|c[aá]mara|micr[oó]fono|contactos|mensajes|datos personales)").containsMatchIn(candidate.source)
            val route = remember(candidate.id, candidate.source) { CodeExecutionRouter.route(report) }
            val canRunCode = route.ready
            AlertDialog(
                onDismissRequest = { pendingCodeRun = null },
                title = { Text(if (asksForPhoneData) "Revisión de permisos y datos" else "Confirmar ejecución") },
                text = {
                    Column {
                        if (asksForPhoneData) {
                            Text("El código contiene señales de que podría intentar acceder a datos o funciones del teléfono. La detección es automática y puede equivocarse.")
                            Spacer(Modifier.height(8.dp))
                            Text("Archivo: ${candidate.name}", fontWeight = FontWeight.Bold)
                            report.findings.take(6).forEach { finding -> Text("• ${finding.title}: ${finding.explanation}", fontSize = 12.sp) }
                            Text("La ejecución disponible no concede permisos ni acceso a datos personales, archivos, red, cuentas, sensores o procesos del sistema.")
                            Spacer(Modifier.height(8.dp))
                            Text(if (codeConsentReady) "Terminó la espera de 30 segundos. Ya puedes decidir." else "Revisa la explicación. El botón de confirmación se habilitará al terminar los 30 segundos.")
                        } else {
                            Text("¿Quieres ejecutar este código? No se detectaron señales conocidas de solicitudes de datos personales o permisos del teléfono. El análisis es heurístico y no garantiza que el código sea seguro.")
                        }
                        if (report.blockedForExecution) {
                            Spacer(Modifier.height(8.dp))
                            Text("Code Guard bloqueó la ejecución por señales de alto riesgo. Revisa el análisis antes de continuar.")
                        } else if (!route.ready) {
                            Spacer(Modifier.height(8.dp))
                            Text("Lenguaje detectado: ${route.language} · Motor: ${route.engine}")
                            Text(route.reason)
                        } else {
                            Spacer(Modifier.height(8.dp))
                            Text("Enrutamiento automático: ${route.language} → ${route.engine}")
                            Text(route.reason)
                        }
                    }
                },
                confirmButton = {
                    TextButton(
                        enabled = codeConsentReady && canRunCode,
                        onClick = {
                            pendingCodeRun = null
                            codeRunningIds = codeRunningIds + candidate.id
                            val onResult: (CodeRunResult) -> Unit = { result ->
                                codeRunResults = codeRunResults + (candidate.id to result)
                                codeRunningIds = codeRunningIds - candidate.id
                                codeRunDetails = codeRunDetails + candidate.id
                            }
                            when (route.engine) {
                                CodeEngine.JAVASCRIPT_WEBVIEW -> JavaScriptRunner.run(context, candidate.source, onResult)
                                CodeEngine.PYTHON -> IsolatedCodeRunner.run(context, "Python", candidate.source, onResult)
                                CodeEngine.JAVA_INTERPRETER -> IsolatedCodeRunner.run(context, "Java", candidate.source, onResult)
                                CodeEngine.NONE -> {
                                    codeRunningIds = codeRunningIds - candidate.id
                                    onResult(CodeRunResult(false, "NO FUNCIONA", route.reason, route.language, "Ninguno"))
                                }
                            }
                        }
                    ) { Text("Confirmar") }
                },
                dismissButton = { TextButton(onClick = { pendingCodeRun = null }) { Text("Cancelar") } }
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