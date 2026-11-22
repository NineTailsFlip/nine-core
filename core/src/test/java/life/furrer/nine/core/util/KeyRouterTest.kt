package life.furrer.nine.core.util

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class KeyRouterTest {

    @Test
    fun `dpad up and down`() {
        assertEquals(NineAction.Up, KeyRouter.map(KeyEvent.KEYCODE_DPAD_UP, ' '))
        assertEquals(NineAction.Down, KeyRouter.map(KeyEvent.KEYCODE_DPAD_DOWN, ' '))
    }

    @Test
    fun `dpad left and right`() {
        assertEquals(NineAction.Left, KeyRouter.map(KeyEvent.KEYCODE_DPAD_LEFT, ' '))
        assertEquals(NineAction.Right, KeyRouter.map(KeyEvent.KEYCODE_DPAD_RIGHT, ' '))
    }

    @Test
    fun `dpad center and enter both mean select`() {
        assertEquals(NineAction.Select, KeyRouter.map(KeyEvent.KEYCODE_DPAD_CENTER, ' '))
        assertEquals(NineAction.Select, KeyRouter.map(KeyEvent.KEYCODE_ENTER, ' '))
    }

    @Test
    fun `back maps to Back`() {
        assertEquals(NineAction.Back, KeyRouter.map(KeyEvent.KEYCODE_BACK, ' '))
    }

    @Test
    fun `star and pound map to Star and Hash`() {
        assertEquals(NineAction.Star, KeyRouter.map(KeyEvent.KEYCODE_STAR, ' '))
        assertEquals(NineAction.Hash, KeyRouter.map(KeyEvent.KEYCODE_POUND, ' '))
    }

    @Test
    fun `digits 0 through 9 map to their own value`() {
        val keycodesToChars = mapOf(
            KeyEvent.KEYCODE_0 to '0',
            KeyEvent.KEYCODE_1 to '1',
            KeyEvent.KEYCODE_2 to '2',
            KeyEvent.KEYCODE_3 to '3',
            KeyEvent.KEYCODE_4 to '4',
            KeyEvent.KEYCODE_5 to '5',
            KeyEvent.KEYCODE_6 to '6',
            KeyEvent.KEYCODE_7 to '7',
            KeyEvent.KEYCODE_8 to '8',
            KeyEvent.KEYCODE_9 to '9',
        )
        keycodesToChars.forEach { (keyCode, char) ->
            assertEquals(NineAction.Digit(char.digitToInt()), KeyRouter.map(keyCode, char))
        }
    }

    @Test
    fun `digit keycode with a non-digit mapped character yields no action`() {
        // Defensive case: an unusual keymap could map a "digit" keycode to a non-numeric
        // character. KeyRouter should not crash or guess — it should decline to act.
        assertNull(KeyRouter.map(KeyEvent.KEYCODE_5, 'x'))
    }

    @Test
    fun `unmapped keycodes yield no action`() {
        assertNull(KeyRouter.map(KeyEvent.KEYCODE_VOLUME_UP, ' '))
    }
}
