package life.furrer.nine.core.prefs

import android.app.Activity
import android.content.Intent
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import life.furrer.nine.core.Nine

/** The export/import UI flow around [Backup] — the SAF intents, the "this replaces everything"
 *  confirm dialog, and result handling/toasts — that [life.furrer.nine.core.settings.NineSettingsActivity]'s
 *  shared Backup section needs beyond the actual JSON read/write. Core owns the request codes. */
object BackupFlow {
    private const val REQUEST_EXPORT = 9001
    private const val REQUEST_IMPORT = 9002

    fun export(activity: Activity) {
        val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
            putExtra(Intent.EXTRA_TITLE, "${Nine.app.id}-settings-$timestamp.json")
        }
        activity.startActivityForResult(intent, REQUEST_EXPORT)
    }

    /** Opens the file picker for an import; the caller asks first (Settings' "Import settings"
     *  row confirms on its sheet), since the file replaces every current setting. */
    fun chooseImportFile(activity: Activity) {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            // Not restricted to "application/json" — many file managers report JSON files under a
            // different MIME type, and Backup validates the content itself once a file is picked.
            type = "*/*"
        }
        activity.startActivityForResult(intent, REQUEST_IMPORT)
    }

    /** @return true if [requestCode] was one of ours (handled either way), so the caller knows
     *  whether to fall through to its own onActivityResult handling. [onImported] runs only after
     *  a successful import, to refresh whatever's currently on screen. */
    fun handleActivityResult(
        activity: Activity,
        requestCode: Int,
        resultCode: Int,
        data: Intent?,
        onImported: () -> Unit,
    ): Boolean {
        when (requestCode) {
            REQUEST_EXPORT -> {
                val uri = data?.data
                if (resultCode == Activity.RESULT_OK && uri != null) {
                    val success = Backup.exportTo(activity, uri)
                    Toast.makeText(activity, if (success) "Settings exported" else "Export failed", Toast.LENGTH_SHORT).show()
                }
                return true
            }
            REQUEST_IMPORT -> {
                val uri = data?.data
                if (resultCode == Activity.RESULT_OK && uri != null) {
                    val imported = Backup.importFrom(activity, uri)
                    if (imported) onImported()
                    val message = if (imported) "Settings imported" else "Import failed — not a valid ${Nine.app.name} backup file"
                    Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()
                }
                return true
            }
            else -> return false
        }
    }
}
