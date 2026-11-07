package com.jarvis.assistant

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.ByteArrayInputStream

data class CodeRunResult(val success: Boolean, val output: String, val explanation: String)

object JavaScriptRunner {
    fun run(context: Context, source: String, callback: (CodeRunResult) -> Unit) {
        Handler(Looper.getMainLooper()).post {
            if (source.isBlank()) {
                callback(CodeRunResult(false, "NO FUNCIONA", "El código está vacío."))
                return@post
            }
            if (source.length > 50000) {
                callback(CodeRunResult(false, "NO FUNCIONA", "El límite de esta versión es 50 KB por ejecución."))
                return@post
            }
            val web = WebView(context.applicationContext)
            var finished = false
            fun finish(result: CodeRunResult) {
                if (finished) return
                finished = true
                runCatching { web.stopLoading(); web.destroy() }
                callback(result)
            }
            web.settings.javaScriptEnabled = true
            web.settings.allowFileAccess = false
            web.settings.allowContentAccess = false
            web.settings.domStorageEnabled = false
            web.settings.javaScriptCanOpenWindowsAutomatically = false
            web.settings.setSupportMultipleWindows(false)
            web.settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
            web.webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse =
                    WebResourceResponse("text/plain", "UTF-8", 403, "Blocked", emptyMap(), ByteArrayInputStream(ByteArray(0)))
                override fun onPageFinished(view: WebView?, url: String?) {
                    view?.evaluateJavascript("(function(){var e=document.getElementById('jarvis-result');return e?e.textContent:'Sin resultado';})()") { raw ->
                        val decoded = runCatching { org.json.JSONTokener(raw).nextValue() as? String }.getOrNull() ?: raw
                        val failed = decoded.startsWith("ERROR:")
                        finish(CodeRunResult(!failed, if (failed) "NO FUNCIONA" else "FUNCIONA", decoded.removePrefix("ERROR:").trim().take(700)))
                    }
                }
            }
            val safeSource = source.replace(Regex("(?i)</script"), "<\\\\/script")
            val html = """
                <!doctype html><html><head><meta charset="utf-8">
                <meta http-equiv="Content-Security-Policy" content="default-src 'none'; connect-src 'none'; img-src 'none'; media-src 'none'; object-src 'none'; frame-src 'none'; form-action 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'">
                </head><body><pre id="jarvis-result">ERROR: No se pudo iniciar.</pre><script>
                (function(){
                  var lines=[];
                  console.log=function(){lines.push(Array.prototype.map.call(arguments,String).join(' '));};
                  console.info=console.log; console.warn=console.log;
                  window.onerror=function(message){document.getElementById('jarvis-result').textContent='ERROR: '+String(message);};
                  try {
                    var result=(function(){ __SOURCE__
                    })();
                    if(result!==undefined) lines.push(String(result));
                    document.getElementById('jarvis-result').textContent=lines.join('\\n') || 'Ejecución completada.';
                  } catch(e) {
                    document.getElementById('jarvis-result').textContent='ERROR: '+(e && e.message ? e.message : String(e));
                  }
                })();
                </script></body></html>
            """.trimIndent().replace("__SOURCE__", safeSource)
            web.loadDataWithBaseURL("https://jarvis.invalid/", html, "text/html", "UTF-8", null)
            Handler(Looper.getMainLooper()).postDelayed({
                finish(CodeRunResult(false, "NO FUNCIONA", "La ejecución superó el límite de 5 segundos y fue detenida."))
            }, 5000L)
        }
    }
}
