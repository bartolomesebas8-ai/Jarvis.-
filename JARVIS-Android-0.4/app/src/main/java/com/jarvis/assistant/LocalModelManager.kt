package com.jarvis.assistant

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns

/**
 * Stores a user-selected GGUF document reference using Android's Storage Access Framework.
 * This is only the model-selection layer; it does not load or execute the model.
 */
data class LocalModelSelection(
    val uri: String,
    val displayName: String
)

object LocalModelManager {
    private const val PREFS = "jarvis_local_model"
    private const val URI_KEY = "gguf_uri"
    private const val NAME_KEY = "gguf_name"

    fun load(context: Context): LocalModelSelection? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val uri = prefs.getString(URI_KEY, null) ?: return null
        val name = prefs.getString(NAME_KEY, "Modelo GGUF") ?: "Modelo GGUF"
        return LocalModelSelection(uri, name)
    }

    fun remember(context: Context, uri: Uri): LocalModelSelection {
        val name = queryDisplayName(context, uri)
        require(name.substringAfterLast('.', "").equals("gguf", ignoreCase = true)) {
            "Selecciona un archivo con extensión .gguf."
        }
        val permission = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
        try {
            context.contentResolver.takePersistableUriPermission(uri, permission)
        } catch (_: SecurityException) {
            // Some document providers do not offer persistable grants; the current grant may still work.
        } catch (_: IllegalArgumentException) {
            // Provider did not return a persistable URI grant.
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(URI_KEY, uri.toString())
            .putString(NAME_KEY, name)
            .apply()
        return LocalModelSelection(uri.toString(), name)
    }

    fun clear(context: Context) {
        val previous = load(context)
        if (previous != null) {
            runCatching {
                context.contentResolver.releasePersistableUriPermission(
                    Uri.parse(previous.uri),
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove(URI_KEY)
            .remove(NAME_KEY)
            .apply()
    }

    private fun queryDisplayName(context: Context, uri: Uri): String {
        var result: String? = null
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) result = cursor.getString(index)
            }
        }
        return (result ?: uri.lastPathSegment ?: "modelo.gguf").trim()
    }
}
