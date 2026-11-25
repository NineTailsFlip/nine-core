package io.github.ninetailsflip.update

import android.content.Context
import android.net.Uri
import android.util.Log
import life.furrer.nine.core.prefs.NinePrefs

/** What the user chose for the whole Nine suite in the phone's first-time setup (NineTailsFlip's
 *  setup wizard on our LineageOS build): the font and the hint bar, the same two rows every app's
 *  Settings → Theme shows.
 *
 *  The wizard serves its choices from a read-only content provider. Each app reads them once
 *  per process from [Updates.init]; a set of choices it hasn't seen yet (a newer [KEY_REVISION])
 *  is written into the app's own NinePrefs, then the app's own Settings take over again. Without
 *  the wizard - any other phone - nothing happens. */
object SetupChoices {
    const val AUTHORITY = "io.github.ninetailsflip.setup.choices"
    val URI: Uri = Uri.parse("content://$AUTHORITY/look")
    const val COLUMN_KEY = "key"
    const val COLUMN_VALUE = "value"

    /** When the choices were made (epoch ms, as a string): the version an app adopts once. */
    const val KEY_REVISION = "revision"
    const val KEY_MONOSPACE_FONT = "use_monospace_font"
    const val KEY_SHOW_HINT_BAR = "show_hint_bar"

    private const val TAG = "SetupChoices"
    private const val PREFS = "nine_setup_choices"
    private const val KEY_ADOPTED = "adopted_revision"

    fun adopt(context: Context) {
        val choices = try {
            read(context)
        } catch (e: Exception) {
            // The wizard is part of the system image; a provider that can't answer is no reason
            // to keep an app from starting.
            Log.w(TAG, "Couldn't read the setup choices", e)
            return
        } ?: return
        apply(context, choices)
    }

    /** Applies [choices] (key to value, as the wizard serves them) unless this revision was
     *  adopted already. Returns whether anything was written. */
    fun apply(context: Context, choices: Map<String, String>): Boolean {
        val revision = choices[KEY_REVISION]?.toLongOrNull() ?: return false
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (revision <= prefs.getLong(KEY_ADOPTED, 0L)) return false
        choices[KEY_MONOSPACE_FONT]?.toBooleanStrictOrNull()?.let { NinePrefs.setUseMonospaceFont(context, it) }
        choices[KEY_SHOW_HINT_BAR]?.toBooleanStrictOrNull()?.let { NinePrefs.setShowHintBar(context, it) }
        prefs.edit().putLong(KEY_ADOPTED, revision).apply()
        return true
    }

    /** The wizard's choices, or null when it isn't installed or hasn't saved any. */
    private fun read(context: Context): Map<String, String>? {
        val cursor = context.contentResolver.query(URI, arrayOf(COLUMN_KEY, COLUMN_VALUE), null, null, null) ?: return null
        return cursor.use { c ->
            buildMap { while (c.moveToNext()) put(c.getString(0), c.getString(1)) }.takeIf { it.isNotEmpty() }
        }
    }
}
