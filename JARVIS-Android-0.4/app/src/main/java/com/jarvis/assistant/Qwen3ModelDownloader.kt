package com.jarvis.assistant

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Explicit, resumeless download of the pinned Qwen3 1.7B Q4_K_M GGUF.
 * The large file is streamed to a temporary file in app-private storage.
 */
object Qwen3ModelDownloader {
    const val MODEL_FILE_NAME = "Qwen3-1.7B-Q4_K_M.gguf"
    const val MODEL_SIZE_LABEL = "aprox. 1,28 GB"
    private const val MODEL_URL =
        "https://huggingface.co/ggml-org/Qwen3-1.7B-GGUF/resolve/main/Qwen3-1.7B-Q4_K_M.gguf"
    private const val MIN_MODEL_BYTES = 1_000_000_000L
    private const val MAX_MODEL_BYTES = 2_000_000_000L

    fun localFile(context: Context): File =
        File(File(context.filesDir, "local_models"), MODEL_FILE_NAME)

    fun isDownloaded(context: Context): Boolean {
        val file = localFile(context)
        return file.isFile && file.length() in MIN_MODEL_BYTES..MAX_MODEL_BYTES
    }

    suspend fun download(
        context: Context,
        onProgress: (Int) -> Unit
    ): File = withContext(Dispatchers.IO) {
        val directory = File(context.applicationContext.filesDir, "local_models")
        if (!directory.exists() && !directory.mkdirs()) {
            throw IllegalStateException("No se pudo preparar el almacenamiento privado para Qwen3.")
        }
        val destination = File(directory, MODEL_FILE_NAME)
        if (destination.isFile && destination.length() in MIN_MODEL_BYTES..MAX_MODEL_BYTES) {
            onProgress(100)
            return@withContext destination
        }

        val temporary = File(directory, "$MODEL_FILE_NAME.part")
        temporary.delete()
        val connection = (URL(MODEL_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 30_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            requestMethod = "GET"
            setRequestProperty("User-Agent", "JARVIS-Android/3.13")
            setRequestProperty("Accept", "application/octet-stream")
        }

        try {
            connection.connect()
            val status = connection.responseCode
            require(status == HttpURLConnection.HTTP_OK) {
                "Hugging Face respondió HTTP $status al solicitar el modelo Qwen3."
            }
            val expectedBytes = connection.contentLengthLong
            require(expectedBytes in MIN_MODEL_BYTES..MAX_MODEL_BYTES) {
                "El tamaño recibido no coincide con el archivo Qwen3 1.7B Q4_K_M esperado."
            }
            require(directory.usableSpace >= expectedBytes + 128L * 1024L * 1024L) {
                "No hay espacio suficiente. Se necesita el modelo de 1,28 GB y espacio adicional para instalarlo."
            }

            val coroutineContext = currentCoroutineContext()
            var downloaded = 0L
            connection.inputStream.buffered().use { input ->
                temporary.outputStream().buffered().use { output ->
                    val buffer = ByteArray(1024 * 1024)
                    while (true) {
                        coroutineContext.ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        downloaded += count
                        require(downloaded <= expectedBytes && downloaded <= MAX_MODEL_BYTES) {
                            "La descarga superó el tamaño esperado; se canceló por seguridad."
                        }
                        output.write(buffer, 0, count)
                        onProgress(((downloaded * 100L) / expectedBytes).toInt().coerceIn(0, 99))
                    }
                }
            }
            require(downloaded == expectedBytes && temporary.length() in MIN_MODEL_BYTES..MAX_MODEL_BYTES) {
                "La descarga de Qwen3 quedó incompleta. Inténtalo de nuevo con una conexión estable."
            }
            if (destination.exists() && !destination.delete()) {
                throw IllegalStateException("No se pudo reemplazar el archivo Qwen3 anterior.")
            }
            if (!temporary.renameTo(destination)) {
                throw IllegalStateException("No se pudo finalizar la instalación local de Qwen3.")
            }
            onProgress(100)
            destination
        } catch (error: Exception) {
            temporary.delete()
            throw error
        } finally {
            connection.disconnect()
        }
    }
}
