package life.furrer.nine.core.util

import java.text.Normalizer

/** Contact/entry search matching: word-prefix name matching extended with digit-substring number
 *  matching and diacritic normalization. Pure functions only; callers decide how to combine a
 *  name match and a number match into one "does this entry match the query" answer, since that's
 *  presentation, not matching. */
object StringMatch {

    /** Any word of [name] (split on non-alphanumeric) starts with [query] — case-insensitive
     *  and diacritic-insensitive both ways, so "müller" matches "muller" and vice versa. An
     *  empty query matches everything (the unfiltered list). */
    fun matchesName(name: String, query: String): Boolean {
        if (query.isEmpty()) return true
        val normalizedQuery = normalize(query)
        val words = normalize(name).split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }
        return words.any { it.startsWith(normalizedQuery) }
    }

    /** Digit-substring match against a phone number, stripping all non-digits from both sides —
     *  the way a feature phone's contacts search has always worked. Callers should gate this on
     *  [isAllDigits] first — a query containing letters should never match by number. */
    fun matchesPhoneNumber(number: String, query: String): Boolean {
        if (query.isEmpty()) return true
        val queryDigits = query.filter { it.isDigit() }
        if (queryDigits.isEmpty()) return false
        return number.filter { it.isDigit() }.contains(queryDigits)
    }

    /** True when every character of [query] is a digit — the gate for number matching, and
     *  distinct from [looksDialable] below (which additionally accepts the punctuation a T9 IME
     *  would produce for a phone number, for the call-key fallback rather than for search
     *  matching). */
    fun isAllDigits(query: String): Boolean = query.isNotEmpty() && query.all { it.isDigit() }

    /** True when [query] is dial-able as typed — digits plus the punctuation a T9 IME would
     *  produce (+, spaces, parens, hyphens) — for "no entry matches and the query is a phone
     *  number... dial the typed digits verbatim." Deliberately more lenient than [isAllDigits],
     *  which gates *search* matching rather than *dialing*. */
    fun looksDialable(query: String): Boolean =
        query.isNotEmpty() && query.any { it.isDigit() } && query.all { it.isDigit() || it in "+()- " }

    private fun normalize(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .lowercase()
}
