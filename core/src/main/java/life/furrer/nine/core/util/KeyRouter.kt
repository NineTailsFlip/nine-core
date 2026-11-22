package life.furrer.nine.core.util

import android.view.KeyEvent

/** The universal key vocabulary every Nine app shares. Apps translate this into their own
 *  semantics (L9's Star -> reorder-mode toggle, Digit(n) -> launch slot n) — that translation is
 *  app meaning, not key routing, so it stays in each app. */
sealed interface NineAction {
    data object Up : NineAction
    data object Down : NineAction
    data object Left : NineAction
    data object Right : NineAction
    data object Select : NineAction
    data object Back : NineAction
    data object Star : NineAction
    data object Hash : NineAction
    data class Digit(val n: Int) : NineAction
}

object KeyRouter {
    /** [number] is the key's mapped character (KeyEvent.getNumber()), passed in rather than a
     *  KeyEvent so this stays a pure function testable without any Android dependency. */
    fun map(keyCode: Int, number: Char): NineAction? = when (keyCode) {
        KeyEvent.KEYCODE_DPAD_UP -> NineAction.Up
        KeyEvent.KEYCODE_DPAD_DOWN -> NineAction.Down
        KeyEvent.KEYCODE_DPAD_LEFT -> NineAction.Left
        KeyEvent.KEYCODE_DPAD_RIGHT -> NineAction.Right
        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> NineAction.Select
        KeyEvent.KEYCODE_BACK -> NineAction.Back
        KeyEvent.KEYCODE_STAR -> NineAction.Star
        KeyEvent.KEYCODE_POUND -> NineAction.Hash
        in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> {
            val digit = number.digitToIntOrNull()
            if (digit == null) null else NineAction.Digit(digit)
        }
        else -> null
    }
}
