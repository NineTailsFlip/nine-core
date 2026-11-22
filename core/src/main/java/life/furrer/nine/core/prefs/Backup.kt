package life.furrer.nine.core.prefs

import android.content.Context
import android.net.Uri
import life.furrer.nine.core.Nine
import org.json.JSONObject

/** Exports/imports the entire "[Nine.app.id]" SharedPreferences file (see [NinePrefs.of]) as
 *  JSON — every repository in an app shares that one file, so a full backup/restore is just a
 *  dump/replace of its contents, no per-feature logic needed here.
 *
 *  File format:
 *  ```
 *  { "app": "l9", "version": 1, "preferences": { ... } }
 *  ```
 *  Forward compatibility, no work yet: keeping `version` and the `{app, version, preferences}`
 *  envelope means a future suite-wide backup could be
 *  `{"suite": "nine", "version": 2, "apps": {"l9": {...}, "c9": {...}}}` and the per-app importer
 *  could accept either shape. Don't build that until there's a reason to. */
object Backup {
    private const val BACKUP_VERSION = 1

    fun exportTo(context: Context, uri: Uri): Boolean {
        return try {
            val prefs = NinePrefs.of(context)
            val values = JSONObject()
            prefs.all.forEach { (key, value) ->
                when (value) {
                    is String -> values.put(key, value)
                    is Int -> values.put(key, value)
                    is Boolean -> values.put(key, value)
                    is Long -> values.put(key, value)
                    is Float -> values.put(key, value.toDouble())
                    // Sets and anything else aren't used by any Nine app's preferences.
                    else -> Unit
                }
            }
            val root = JSONObject().apply {
                put("app", Nine.app.id)
                put("version", BACKUP_VERSION)
                put("preferences", values)
            }
            val output = context.contentResolver.openOutputStream(uri) ?: return false
            output.use { it.write(root.toString(2).toByteArray()) }
            true
        } catch (e: Exception) {
            false
        }
    }

    /** Rejects a file whose `app` doesn't match the running app — a C9 backup must never load
     *  into L9, or vice versa. */
    fun importFrom(context: Context, uri: Uri): Boolean {
        return try {
            val input = context.contentResolver.openInputStream(uri) ?: return false
            val text = input.use { it.readBytes().decodeToString() }
            val root = JSONObject(text)
            if (root.optString("app") != Nine.app.id) return false
            val values = root.getJSONObject("preferences")
            val editor = NinePrefs.of(context).edit()
            editor.clear()
            values.keys().forEach { key ->
                when (val value = values.get(key)) {
                    is String -> editor.putString(key, value)
                    is Int -> editor.putInt(key, value)
                    is Boolean -> editor.putBoolean(key, value)
                    is Long -> editor.putLong(key, value)
                    is Double -> editor.putFloat(key, value.toFloat())
                    else -> Unit
                }
            }
            editor.apply()
            true
        } catch (e: Exception) {
            false
        }
    }
}
