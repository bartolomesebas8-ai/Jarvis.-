package com.jarvis.assistant

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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

    fun sendMessage() {
        val msg = input.trim()
        if (msg.isNotEmpty()) {
            val updated = lines + Line("TÚ", msg) + Line(
                "JARVIS",
                "Recibí tu mensaje y lo guardé en este dispositivo. La conexión con Groq y el motor Qwen todavía está pendiente."
            )
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
                    Text("JARVIS  •  0.5", color = Color(0xFF60798D), fontSize = 10.sp, letterSpacing = 1.5.sp)
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
                            Text("ASISTENTE PERSONAL", color = cyan, fontSize = 9.sp, letterSpacing = 1.6.sp)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(6.dp).background(Color(0xFF6EE7A8), CircleShape))
                                Spacer(Modifier.width(5.dp))
                                Text("Memoria local activa", color = Color(0xFF9CB4C7), fontSize = 10.sp)
                            }
                        }
                        TextButton(onClick = { showClearDialog = true }) { Text("Borrar", color = Color(0xFFFF9B9B), fontSize = 12.sp) }
                    }
                }
                Spacer(Modifier.height(12.dp))
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
                Text("JARVIS 0.5 · Modo local", color = Color.Gray, fontSize = 11.sp)
            }
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
