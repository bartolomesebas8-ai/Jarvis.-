package com.jarvis.assistant

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import android.os.Process
import android.os.RemoteException
import bsh.Interpreter
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import java.io.StringWriter
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Code runs in a dedicated Android isolated process (no app permissions).
 * A watchdog terminates this process after five seconds to stop runaway code.
 */
class IsolatedCodeService : Service() {
    private lateinit var worker: HandlerThread
    private lateinit var messenger: Messenger

    override fun onCreate() {
        super.onCreate()
        worker = HandlerThread("jarvis-isolated-code").apply { start() }
        messenger = Messenger(Handler(worker.looper) { message ->
            execute(message)
            true
        })
    }

    override fun onBind(intent: Intent?): IBinder = messenger.binder

    override fun onDestroy() {
        worker.quitSafely()
        super.onDestroy()
    }

    private fun execute(message: Message) {
        val reply = message.replyTo ?: return
        val source = message.data?.getString(KEY_SOURCE).orEmpty()
        val language = message.data?.getString(KEY_LANGUAGE).orEmpty()
        if (source.isBlank() || source.length > MAX_SOURCE_CHARS) {
            send(reply, false, "NO FUNCIONA", "Código vacío o supera el límite de 50 KB.")
            return
        }

        val finished = AtomicBoolean(false)
        val watchdog = Thread {
            try {
                Thread.sleep(EXECUTION_TIMEOUT_MS)
                if (finished.compareAndSet(false, true)) {
                    Process.killProcess(Process.myPid())
                }
            } catch (_: InterruptedException) {
                // Execution completed before the deadline.
            }
        }.apply { name = "jarvis-code-watchdog"; isDaemon = true; start() }

        val started = System.currentTimeMillis()
        try {
            val output = when (language) {
                "Python" -> runPython(source)
                "Java" -> runJava(source)
                else -> throw IllegalArgumentException("Lenguaje no admitido por este servicio.")
            }
            if (finished.compareAndSet(false, true)) {
                watchdog.interrupt()
                send(reply, !output.startsWith("ERROR:"), if (output.startsWith("ERROR:")) "NO FUNCIONA" else "FUNCIONA",
                    output.take(6000), System.currentTimeMillis() - started)
            }
        } catch (error: Throwable) {
            if (finished.compareAndSet(false, true)) {
                watchdog.interrupt()
                send(reply, false, "NO FUNCIONA",
                    (error.message ?: error.javaClass.simpleName).take(6000),
                    System.currentTimeMillis() - started)
            }
        }
    }

    private fun runPython(source: String): String {
        if (!Python.isStarted()) Python.start(AndroidPlatform(this))
        return Python.getInstance().getModule("jarvis_runner").callAttr("run", source).toString()
    }

    private fun runJava(source: String): String {
        val interpreter = Interpreter()
        val output = StringWriter()
        interpreter.setOut(output)
        interpreter.setErr(output)
        interpreter.eval(source)
        val text = output.toString().trim()
        return text.ifBlank { "Interpretación Java completada sin salida." }
    }

    private fun send(
        reply: Messenger,
        success: Boolean,
        status: String,
        output: String,
        durationMs: Long = 0L
    ) {
        try {
            val result = Message.obtain(null, MSG_RESULT).apply {
                data = Bundle().apply {
                    putBoolean(KEY_SUCCESS, success)
                    putString(KEY_STATUS, status)
                    putString(KEY_OUTPUT, output)
                    putLong(KEY_DURATION, durationMs)
                }
            }
            reply.send(result)
        } catch (_: RemoteException) {
            // The requesting screen may have been closed.
        }
    }

    companion object {
        const val MSG_RUN = 10
        const val MSG_RESULT = 11
        const val KEY_SOURCE = "source"
        const val KEY_LANGUAGE = "language"
        const val KEY_SUCCESS = "success"
        const val KEY_STATUS = "status"
        const val KEY_OUTPUT = "output"
        const val KEY_DURATION = "duration"
        private const val MAX_SOURCE_CHARS = 50_000
        private const val EXECUTION_TIMEOUT_MS = 5_000L
    }
}
