package com.jarvis.assistant

import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.ComponentName
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.RemoteException

object IsolatedCodeRunner {
    fun run(context: Context, language: String, source: String, callback: (CodeRunResult) -> Unit) {
        val appContext = context.applicationContext
        val main = Handler(Looper.getMainLooper())
        var completed = false
        var bound = false
        lateinit var connection: ServiceConnection
        fun finish(result: CodeRunResult) {
            if (completed) return
            completed = true
            if (bound) runCatching { appContext.unbindService(connection) }
            callback(result)
        }
        val reply = Messenger(Handler(Looper.getMainLooper()) { message ->
            val data = message.data
            finish(CodeRunResult(
                data.getBoolean(IsolatedCodeService.KEY_SUCCESS),
                data.getString(IsolatedCodeService.KEY_STATUS) ?: "NO FUNCIONA",
                data.getString(IsolatedCodeService.KEY_OUTPUT) ?: "El motor no devolvió un resultado.",
                language,
                if (language == "Python") "Python 3.12 aislado" else "BeanShell (Java-like) aislado",
                "Proceso Android aislado sin permisos de la app; límite de 5 segundos; máximo 50 KB.",
                data.getLong(IsolatedCodeService.KEY_DURATION)
            ))
            true
        })
        connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: android.os.IBinder?) {
                try {
                    val request = Message.obtain(null, IsolatedCodeService.MSG_RUN).apply {
                        data = Bundle().apply {
                            putString(IsolatedCodeService.KEY_SOURCE, source)
                            putString(IsolatedCodeService.KEY_LANGUAGE, language)
                        }
                        replyTo = reply
                    }
                    Messenger(binder ?: throw IllegalStateException("Motor aislado no disponible.")).send(request)
                } catch (error: Exception) {
                    finish(CodeRunResult(false, "NO FUNCIONA", error.message ?: "No se pudo iniciar el motor aislado.", language))
                }
            }
            override fun onServiceDisconnected(name: ComponentName?) {
                finish(CodeRunResult(false, "NO FUNCIONA", "El proceso aislado se cerró antes de devolver el resultado.", language))
            }
        }
        val intent = Intent(appContext, IsolatedCodeService::class.java)
        try {
            bound = appContext.bindService(intent, connection, Context.BIND_AUTO_CREATE)
            if (!bound) {
                finish(CodeRunResult(false, "NO FUNCIONA", "Android no permitió iniciar el servicio aislado.", language))
                return
            }
            main.postDelayed({
                finish(CodeRunResult(false, "TIEMPO AGOTADO", "El motor aislado no respondió dentro del límite de seguridad.", language))
            }, 6_500L)
        } catch (error: Exception) {
            finish(CodeRunResult(false, "NO FUNCIONA", error.message ?: "Error al iniciar el motor aislado.", language))
        }
    }
}
