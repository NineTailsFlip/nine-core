package life.furrer.nine.core.prefs

import android.content.Context
import android.content.SharedPreferences
import life.furrer.nine.core.Nine
import life.furrer.nine.core.util.NumberFormat

/** The keys core itself owns — everything shown in the shared Theme section of Settings.
 *  Everything else stays in each app's own repository/Prefs object, keyed against the same
 *  underlying file via [of]. */
object NinePrefs {
    private const val KEY_USE_MONOSPACE_FONT = "use_monospace_font"
    private const val KEY_SHOW_HINT_BAR = "show_hint_bar"
    private const val KEY_NUMBER_FORMAT = "number_format"

    fun of(context: Context): SharedPreferences =
        context.getSharedPreferences(Nine.app.id, Context.MODE_PRIVATE)

    fun useMonospaceFont(context: Context): Boolean =
        of(context).getBoolean(KEY_USE_MONOSPACE_FONT, true)

    fun setUseMonospaceFont(context: Context, value: Boolean) {
        of(context).edit().putBoolean(KEY_USE_MONOSPACE_FONT, value).apply()
    }

    /** The hint bar costs one row, and it can be turned off in Settings — only offered when
     *  [Nine.app]'s [life.furrer.nine.core.NineApp.hasHintBar] is true. */
    fun showHintBar(context: Context): Boolean =
        of(context).getBoolean(KEY_SHOW_HINT_BAR, true)

    fun setShowHintBar(context: Context, value: Boolean) {
        of(context).edit().putBoolean(KEY_SHOW_HINT_BAR, value).apply()
    }

    /** How phone numbers are spaced for display (P9, C9 and M9 each offer it in Settings).
     *  Until chosen — and for the retired "Swiss"/"In threes" choices, which the per-country
     *  rules now cover — by country. */
    fun numberFormat(context: Context): NumberFormat {
        val saved = of(context).getString(KEY_NUMBER_FORMAT, null)
        return NumberFormat.entries.firstOrNull { it.name == saved } ?: NumberFormat.BY_COUNTRY
    }

    fun setNumberFormat(context: Context, value: NumberFormat) {
        of(context).edit().putString(KEY_NUMBER_FORMAT, value.name).apply()
    }
}
