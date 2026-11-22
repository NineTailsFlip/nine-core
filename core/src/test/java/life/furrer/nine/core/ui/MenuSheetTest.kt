package life.furrer.nine.core.ui

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class MenuSheetTest {

    private val sheet = MenuSheet(RuntimeEnvironment.getApplication())
    private val chosen = mutableListOf<String>()

    private fun item(label: String, enabled: Boolean = true) = MenuItem(label, enabled) { chosen += label }

    private fun key(code: Int) = sheet.handleKeyDown(code, KeyEvent(KeyEvent.ACTION_DOWN, code))

    @Test
    fun `an enabled item behaves as before`() {
        sheet.show(listOf(item("a"), item("b")))
        key(KeyEvent.KEYCODE_2)
        assertEquals(listOf("b"), chosen)
        assertFalse(sheet.isShowing)
    }

    @Test
    fun `a disabled item keeps its number but its key does nothing`() {
        sheet.show(listOf(item("a", enabled = false), item("b")))
        key(KeyEvent.KEYCODE_1)
        assertTrue(chosen.isEmpty())
        assertTrue(sheet.isShowing)
        key(KeyEvent.KEYCODE_2)
        assertEquals(listOf("b"), chosen)
    }

    @Test
    fun `the highlight starts on and moves between enabled items only`() {
        sheet.show(listOf(item("a", enabled = false), item("b"), item("c", enabled = false), item("d")))
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals(listOf("d"), chosen)
    }

    @Test
    fun `OK with nothing enabled leaves the menu open`() {
        sheet.show(listOf(item("a", enabled = false)))
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        assertTrue(chosen.isEmpty())
        assertTrue(sheet.isShowing)
    }

    private fun down(code: Int) = sheet.interceptKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
    private fun up(code: Int) = sheet.interceptKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))

    @Test
    fun `the UP of the key that chose an item is swallowed after the menu closed`() {
        sheet.show(listOf(item("a")))
        assertTrue(down(KeyEvent.KEYCODE_DPAD_CENTER))
        assertFalse(sheet.isShowing)
        assertEquals(listOf("a"), chosen)
        assertTrue(up(KeyEvent.KEYCODE_DPAD_CENTER))
        // Only that one UP: the next press goes to the host again.
        assertFalse(down(KeyEvent.KEYCODE_DPAD_CENTER))
        assertFalse(up(KeyEvent.KEYCODE_DPAD_CENTER))
    }

    @Test
    fun `a hidden menu with nothing pending lets every key through`() {
        assertFalse(down(KeyEvent.KEYCODE_STAR))
        assertFalse(up(KeyEvent.KEYCODE_STAR))
    }

    @Test
    fun `a shown menu takes every event, even ones it doesn't act on`() {
        sheet.show(listOf(item("a")))
        assertTrue(down(KeyEvent.KEYCODE_POUND))
        assertTrue(up(KeyEvent.KEYCODE_POUND))
        assertTrue(sheet.isShowing)
    }

    private fun repeat(code: Int) = KeyEvent(0L, 0L, KeyEvent.ACTION_DOWN, code, 1)

    @Test
    fun `auto-repeats of the key that chose an item are swallowed, a fresh press is not`() {
        sheet.show(listOf(item("a")))
        assertTrue(down(KeyEvent.KEYCODE_1))
        assertTrue(sheet.interceptKeyEvent(repeat(KeyEvent.KEYCODE_1)))
        assertTrue(up(KeyEvent.KEYCODE_1))
        assertFalse(down(KeyEvent.KEYCODE_1))
        assertEquals(listOf("a"), chosen)
    }

    @Test
    fun `onKeyDown hosts get the same repeat swallowing through handleKeyDown`() {
        sheet.show(listOf(item("a")))
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        assertTrue(sheet.handleKeyDown(KeyEvent.KEYCODE_DPAD_CENTER, repeat(KeyEvent.KEYCODE_DPAD_CENTER)))
        assertFalse(key(KeyEvent.KEYCODE_DPAD_CENTER))
        assertFalse(sheet.handleKeyDown(KeyEvent.KEYCODE_2, repeat(KeyEvent.KEYCODE_2)))
    }

    private fun rowTexts(view: android.view.View = sheet): List<String> = when (view) {
        is android.widget.TextView -> listOf(view.text.toString())
        is android.view.ViewGroup -> (0 until view.childCount).flatMap { rowTexts(view.getChildAt(it)) }
        else -> emptyList()
    }

    @Test
    fun `0 picks the 10th row, numbered 0, and rows past it have no number`() {
        sheet.show((1..11).map { item("r$it") })
        val rows = rowTexts().filter { it.endsWith(" r9") || it.endsWith(" r10") || it.endsWith(" r11") }
        assertEquals(listOf("9 r9", "0 r10", "  r11"), rows)
        key(KeyEvent.KEYCODE_0)
        assertEquals(listOf("r10"), chosen)
    }

    @Test
    fun `0 in a menu of fewer than 10 rows chooses nothing, like any unnumbered key`() {
        sheet.show(listOf(item("a")))
        key(KeyEvent.KEYCODE_0)
        assertTrue(chosen.isEmpty())
        assertFalse(sheet.isShowing)
    }
}
