package life.furrer.nine.core.util

import android.database.Cursor
import android.database.MatrixCursor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CursorsTest {
    private fun cursor(vararg rows: Array<Any?>): MatrixCursor =
        MatrixCursor(arrayOf("s", "l", "i")).also { m -> rows.forEach { m.addRow(it) } }

    @Test
    fun readsValues() {
        val c = cursor(arrayOf("a", 5_000_000_000L, 7))
        c.moveToFirst()
        assertEquals("a", c.string("s"))
        assertEquals("a", c.stringOr("s", "x"))
        assertEquals(5_000_000_000L, c.long("l"))
        assertEquals(5_000_000_000L, c.longOrNull("l"))
        assertEquals(7, c.int("i"))
        assertEquals(7, c.intOrNull("i"))
        assertEquals(7, c.intOr("i", 1))
        assertTrue(c.bool("i"))
    }

    @Test
    fun nullColumns() {
        val c = cursor(arrayOf(null, null, null))
        c.moveToFirst()
        assertNull(c.string("s"))
        assertEquals("x", c.stringOr("s", "x"))
        assertEquals(0L, c.long("l"))
        assertNull(c.longOrNull("l"))
        assertEquals(0, c.int("i"))
        assertNull(c.intOrNull("i"))
        assertEquals(9, c.intOr("i", 9))
        assertFalse(c.bool("i"))
    }

    @Test
    fun intOrToleratesMissingColumn() {
        val c = cursor(arrayOf("a", 1L, 2))
        c.moveToFirst()
        assertEquals(4, c.intOr("nope", 4))
    }

    @Test(expected = IllegalArgumentException::class)
    fun strictHelpersThrowOnMissingColumn() {
        val c = cursor(arrayOf("a", 1L, 2))
        c.moveToFirst()
        c.string("nope")
    }

    @Test
    fun mapRowsReadsAllRowsAndCloses() {
        val c = cursor(arrayOf("a", 1L, 1), arrayOf("b", 2L, 2))
        assertEquals(listOf("a", "b"), c.mapRows { it.string("s") })
        assertTrue(c.isClosed)
    }

    @Test
    fun mapRowsOnNullIsEmpty() {
        val c: Cursor? = null
        assertEquals(emptyList<String>(), c.mapRows { it.string("s") })
        assertEquals(emptyList<String>(), c.mapRowsNotNull { it.string("s") })
        assertNull(c.firstRow { it.string("s") })
    }

    @Test
    fun mapRowsNotNullDropsNulls() {
        val c = cursor(arrayOf("a", 1L, 1), arrayOf(null, 2L, 2), arrayOf("c", 3L, 3))
        assertEquals(listOf("a", "c"), c.mapRowsNotNull { it.string("s") })
        assertTrue(c.isClosed)
    }

    @Test
    fun firstRowReadsOnlyTheFirstAndCloses() {
        val c = cursor(arrayOf("a", 1L, 1), arrayOf("b", 2L, 2))
        assertEquals("a", c.firstRow { it.string("s") })
        assertTrue(c.isClosed)
        val empty = cursor()
        assertNull(empty.firstRow { it.string("s") })
        assertTrue(empty.isClosed)
    }
}
