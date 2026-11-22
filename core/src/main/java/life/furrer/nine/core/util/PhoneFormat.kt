package life.furrer.nine.core.util

import android.content.Context
import android.telephony.PhoneNumberFormattingTextWatcher
import android.telephony.PhoneNumberUtils
import android.telephony.TelephonyManager
import android.text.SpannableStringBuilder
import java.util.Locale
import life.furrer.nine.core.prefs.NinePrefs

/** How phone numbers are spaced for display, picked in each app's Settings ("Number format").
 *  Only ever applied to what's shown — dialing, lookups and storage always use the number as is. */
enum class NumberFormat(val label: String) {
    /** Each number grouped the way its own country writes it: `+41 79 123 45 67`,
     *  `+44 7751 234023`, `+49 30 123456`. A number without a country code is taken to be from
     *  the home country ([PhoneFormat.homeCountry]), so `0417004151` → `041 700 41 51` on a
     *  Swiss SIM. */
    BY_COUNTRY("By country"),

    /** Exactly as saved in the address book or the log; typed digits stay unspaced. */
    AS_SAVED("As saved"),
}

/**
 * Display spacing for phone numbers, by the numbering rules of each number's own country.
 *
 * Those rules come from the platform: Android ships Google's libphonenumber (the per-country
 * data every phone uses) behind the public [PhoneNumberUtils.formatNumber] for complete numbers
 * and [PhoneNumberFormattingTextWatcher] for a number still being typed. That replaced a
 * hand-written Swiss pattern plus "threes for everyone else", which put `+44 775 123 402 3` on a
 * UK number. Numbers it can't make sense of (an alphanumeric SMS sender, a `*#06#` code, a
 * number with a dial pause) are shown untouched.
 */
object PhoneFormat {

    /** [number] spaced per the running app's "Number format" setting. */
    fun display(context: Context, number: String): String =
        format(number, NinePrefs.numberFormat(context), homeCountry(context))

    /** Like [display], for a number still being typed: groups fill in left to right instead of
     *  waiting until the number is complete (`041 7`, `041 700 4`, ...). */
    fun displayTyping(context: Context, digits: String): String =
        formatTyping(digits, NinePrefs.numberFormat(context), homeCountry(context))

    /** The country a number without a country code is assumed to be from, as an ISO code
     *  ("CH"): the SIM's country (the carrier you belong to, which stays put when roaming), else
     *  the network you're on, else the phone's language region. Null when none is known. */
    fun homeCountry(context: Context): String? {
        val telephony = context.getSystemService(TelephonyManager::class.java)
        return listOfNotNull(telephony?.simCountryIso, telephony?.networkCountryIso, Locale.getDefault().country)
            .map { it.trim().uppercase() }
            .firstOrNull { it.length == 2 }
    }

    /** A complete number. [homeIso] only matters for numbers written without a country code. */
    fun format(number: String, style: NumberFormat, homeIso: String?): String {
        val trimmed = number.trim()
        if (style == NumberFormat.AS_SAVED || trimmed.isEmpty()) return trimmed
        return withInternationalPrefix(trimmed) { normalized ->
            if (!normalized.startsWith("+") && homeIso == null) return@withInternationalPrefix null
            PhoneNumberUtils.formatNumber(normalized, homeIso ?: FALLBACK_REGION)
        } ?: trimmed
    }

    /** A number being typed, digit by digit (plus a leading `+`). */
    fun formatTyping(digits: String, style: NumberFormat, homeIso: String?): String {
        if (style == NumberFormat.AS_SAVED || digits.isEmpty()) return digits
        if (digits.any { !it.isDigit() && it != '+' }) return digits
        return withInternationalPrefix(digits) { normalized ->
            if (!normalized.startsWith("+") && homeIso == null) return@withInternationalPrefix null
            val editable = SpannableStringBuilder(normalized)
            PhoneNumberFormattingTextWatcher(homeIso ?: FALLBACK_REGION).afterTextChanged(editable)
            editable.toString().trim()
        } ?: digits
    }

    /** `0041 79 …` is how a Swiss (and most European) phone writes an international number; the
     *  formatter only understands `+41 79 …`. So the `00` is swapped for `+` to format, and put
     *  back on the result. */
    private fun withInternationalPrefix(number: String, format: (String) -> String?): String? {
        if (!number.startsWith("00")) return format(number)
        val formatted = format("+" + number.drop(2)) ?: return null
        return if (formatted.startsWith("+")) "00" + formatted.drop(1) else formatted
    }

    /** Only used to satisfy the formatter's region argument for a number that carries its own
     *  `+` country code, where the region is ignored anyway. */
    private const val FALLBACK_REGION = "US"
}
