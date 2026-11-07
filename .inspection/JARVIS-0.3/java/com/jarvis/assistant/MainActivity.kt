package com.jarvis.assistant

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

 data class Line(val who: String, val text: String)

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
        Line("JARVIS", "Hola. Soy JARVIS. Tu historial se guardará en este dispositivo. Aún falta conectar los motores de IA.")
    )
    return try {
        val array = JSONArray(raw)
        (0 until array.length()).map { i ->
            val item = array.getJSONObject(i)
            Line(item.getString("who"), item.getString("text"))
        }.ifEmpty { listOf(Line("JARVIS", "Hola. Soy JARVIS. Tu historial se guardará en este dispositivo.")) }
    } catch (_: Exception) {
        listOf(Line("JARVIS", "Hola. Soy JARVIS. No pude leer el historial anterior; empezamos uno nuevo."))
    }
}

private fun saveHistory(context: android.content.Context, lines: List<Line>) {
    val array = JSONArray()
    lines.forEach { line ->
        array.put(JSONObject().put("who", line.who).put("text", line.text))
    }
    context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
        .edit().putString(HISTORY, array.toString()).commit()
}

@Composable
fun Jarvis() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val bg = Color(0xFF08111F)
    val panel = Color(0xFF142238)
    val cyan = Color(0xFF54D6E8)
    var input by remember { mutableStateOf("") }
    var lines by remember { mutableStateOf(loadHistory(context)) }
    val scope = rememberCoroutineScope()

    MaterialTheme(colorScheme = darkColorScheme(primary = cyan, background = bg, surface = panel, onSurface = Color.White)) {
        Column(Modifier.fillMaxSize().background(bg).padding(16.dp)) {
            Text("JARVIS", color = cyan, fontSize = 30.sp, fontWeight = FontWeight.Bold)
            Text("ASISTENTE PERSONAL", color = Color.LightGray, fontSize = 11.sp)
            Text("Memoria local activada", color = Color(0xFF8ED9A8), fontSize = 11.sp)
            Spacer(Modifier.height(16.dp))
            Surface(color = panel, shape = RoundedCornerShape(18.dp), modifier = Modifier.weight(1f).fillMaxWidth()) {
                LazyColumn(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(lines) { line ->
                        Column(Modifier.fillMaxWidth(), horizontalAlignment = if (line.who == "TÚ") Alignment.End else Alignment.Start) {
                            Text(line.who, color = cyan, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            Surface(color = if (line.who == "TÚ") Color(0xFF21465A) else Color(0xFF203047), shape = RoundedCornerShape(14.dp)) {
                                Text(line.text, Modifier.padding(12.dp), color = Color.White)
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(color = panel, shape = RoundedCornerShape(24.dp), modifier = Modifier.weight(1f)) {
                    BasicTextField(value = input, onValueChange = { input = it }, modifier = Modifier.fillMaxWidth().padding(15.dp), textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 16.sp), decorationBox = { inner ->
                        if (input.isEmpty()) Text("Escribe a JARVIS...", color = Color.Gray)
                        inner()
                    })
                }
                Spacer(Modifier.width(8.dp))
                Button(onClick = {
                    val msg = input.trim()
                    if (msg.isNotEmpty()) {
                        val updated = lines + Line("TÚ", msg) + Line("JARVIS", "Recibí tu mensaje. Tu conversación queda guardada en este dispositivo. Groq y Qwen aún no están conectados.")
                        lines = updated
                        saveHistory(context, updated)
                        input = ""
                    }
                }) { Text("Enviar") }
            }
            Spacer(Modifier.height(8.dp))
            Text("Versión 0.2 · Memoria local", color = Color.Gray, fontSize = 11.sp)
        }
    }
}
