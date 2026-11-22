package life.furrer.nine.core.util

import android.database.Cursor

/*
 * Read helpers for the provider stores. Column names resolve through getColumnIndexOrThrow on
 * every call, which is fine for single-row reads and short lists. A loop over thousands of rows
 * (all contacts, the whole phone table, a message thread) should instead resolve its indices once
 * before the loop and use the raw getX(index) calls - these helpers are deliberately by-name only.
 *
 * Null handling mirrors the platform: string() returns null for a NULL column, long()/int()
 * return 0 for one. Use longOrNull()/intOrNull()/stringOr()/intOr() when NULL means something.
 */

/** The column's text, or null for a NULL value. */
fun Cursor.string(column: String): String? = getString(getColumnIndexOrThrow(column))

/** The column's text, or [default] for a NULL value. */
fun Cursor.stringOr(column: String, default: String): String = string(column) ?: default

/** The column's value; 0 for a NULL value, as with [Cursor.getLong]. */
fun Cursor.long(column: String): Long = getLong(getColumnIndexOrThrow(column))

/** The column's value, or null for a NULL value. */
fun Cursor.longOrNull(column: String): Long? =
    getColumnIndexOrThrow(column).let { if (isNull(it)) null else getLong(it) }

/** The column's value; 0 for a NULL value, as with [Cursor.getInt]. */
fun Cursor.int(column: String): Int = getInt(getColumnIndexOrThrow(column))

/** The column's value, or null for a NULL value. */
fun Cursor.intOrNull(column: String): Int? =
    getColumnIndexOrThrow(column).let { if (isNull(it)) null else getInt(it) }

/** The column's value, or [default] for a NULL value *or a column the provider doesn't have*. */
fun Cursor.intOr(column: String, default: Int): Int {
    val index = getColumnIndex(column)
    return if (index < 0 || isNull(index)) default else getInt(index)
}

/** True when the column's integer value is non-zero (a flag column); NULL reads as false. */
fun Cursor.bool(column: String): Boolean = int(column) != 0

/** Reads every row with [read] and closes the cursor. A null cursor (provider unavailable) is empty. */
inline fun <T> Cursor?.mapRows(read: (Cursor) -> T): List<T> {
    if (this == null) return emptyList()
    return use { c ->
        val out = ArrayList<T>(c.count.coerceAtLeast(0))
        while (c.moveToNext()) out.add(read(c))
        out
    }
}

/** Like [mapRows], dropping the rows [read] maps to null. */
inline fun <T : Any> Cursor?.mapRowsNotNull(read: (Cursor) -> T?): List<T> {
    if (this == null) return emptyList()
    return use { c ->
        val out = ArrayList<T>()
        while (c.moveToNext()) read(c)?.let { out.add(it) }
        out
    }
}

/** Reads the first row with [read] and closes the cursor; null for a null or empty cursor. */
inline fun <T> Cursor?.firstRow(read: (Cursor) -> T): T? {
    if (this == null) return null
    return use { c -> if (c.moveToFirst()) read(c) else null }
}
