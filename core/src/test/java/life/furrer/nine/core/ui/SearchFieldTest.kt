package life.furrer.nine.core.ui

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class SearchFieldTest {

    private lateinit var field: SearchField
    private var lastQuery: String? = null

    @Before
    fun setUp() {
        field = SearchField(RuntimeEnvironment.getApplication())
        field.onQueryChanged = { lastQuery = it }
    }

    @Test
    fun `field starts unfocusable so a lone window doesn't auto-focus it`() {
        assertFalse(field.editText.isFocusable)
    }

    @Test
    fun `a reserved key never focuses the field`() {
        val consumed = field.handleKeyEvent(
            KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_DOWN, 0),
            setOf(KeyEvent.KEYCODE_DPAD_DOWN),
        )
        assertTrue(consumed)
        assertFalse(field.editText.isFocusable)
    }

    @Test
    fun `a real T9 key activates the field`() {
        val consumed = field.handleKeyEvent(
            KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A, 0),
            setOf(KeyEvent.KEYCODE_DPAD_DOWN),
        )
        assertFalse(consumed)
        assertTrue(field.editText.isFocusable)
        assertTrue(field.editText.isFocused)
    }

    @Test
    fun `a key-up with no matching key-down is swallowed (leaked open-keypress guard)`() {
        // No matching ACTION_DOWN was ever seen for this key (e.g. the "0" that opened this
        // screen) — its ACTION_UP must not reach the field/IME.
        val consumed = field.handleKeyEvent(
            KeyEvent(0, 0, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_0, 0),
            emptySet(),
        )
        assertTrue(consumed)
    }

    @Test
    fun `a key-up matching a seen key-down is not swallowed`() {
        field.handleKeyEvent(KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A, 0), emptySet())
        val consumed = field.handleKeyEvent(
            KeyEvent(0, 0, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_A, 0),
            emptySet(),
        )
        assertFalse(consumed)
    }

    @Test
    fun `onPause clears seen key-downs so a stale key-up is swallowed again`() {
        field.handleKeyEvent(KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A, 0), emptySet())
        field.onPause()
        val consumed = field.handleKeyEvent(
            KeyEvent(0, 0, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_A, 0),
            emptySet(),
        )
        assertTrue(consumed)
    }

    @Test
    fun `a text change right after window focus is discarded (leaked composed character)`() {
        // s.clear() re-triggers afterTextChanged recursively (past the now-true
        // firstTextChangeHandled guard), so onQueryChanged does still fire — with "", the same
        // state the field started in, not with the leaked character. Same behavior as L9's
        // original AppPickerActivity code this was extracted from.
        field.onWindowFocusGained()
        field.editText.setText("x")
        assertEquals("", lastQuery)
    }

    @Test
    fun `a text change well after window focus is not discarded`() {
        field.onWindowFocusGained()
        Thread.sleep(950)
        field.editText.setText("andreas")
        assertEquals("andreas", lastQuery)
    }

    @Test
    fun `only the first text change is subject to the leaked-character guard`() {
        field.onWindowFocusGained()
        field.editText.setText("x") // discarded, but consumes the "first change" slot
        field.editText.setText("andreas") // arrives immediately after, must not be discarded
        assertEquals("andreas", lastQuery)
    }

    @Test
    fun `clear empties the field`() {
        field.editText.setText("andreas")
        field.clear()
        assertEquals("", field.query)
    }

    @Test
    fun `handleBack returns true without a caller action when the IME consumes it`() {
        val handled = field.handleBack(KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK, 0)) { true }
        assertTrue(handled)
    }

    @Test
    fun `handleBack returns false on ACTION_DOWN when the IME does not consume it`() {
        val handled = field.handleBack(KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK, 0)) { false }
        assertFalse(handled)
    }

    @Test
    fun `handleBack returns true on ACTION_UP even when not consumed, so only DOWN triggers the caller action`() {
        val handled = field.handleBack(KeyEvent(0, 0, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK, 0)) { false }
        assertTrue(handled)
    }
}
