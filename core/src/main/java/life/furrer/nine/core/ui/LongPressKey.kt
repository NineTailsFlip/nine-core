package life.furrer.nine.core.ui

import android.os.Handler
import android.os.Looper
import android.view.ViewConfiguration

/** The tap-vs-hold distinction for one key, tracked by hand: a delayed callback posted on DOWN,
 *  cancelled and checked on UP. This duplicates the framework's own `onKeyLongPress`, which would
 *  normally be the right tool — but `onKeyLongPress` only fires via the *default* key dispatch,
 *  and the screens that first needed this (P9's dial screen for `*`/`0`/Back, `:contacts`' own
 *  list for hold-`*` → Settings) reserve those keys in their own `dispatchKeyEvent` override,
 *  bypassing that default dispatch entirely before `onKeyLongPress` would ever see them. Every
 *  screen's tap-`*`/hold-`*` now uses it, so the suite has one notion of a hold.
 *
 *  Owns its own [Handler] on the main looper so callers don't need to supply one. Uses
 *  [ViewConfiguration.getLongPressTimeout] so a hold here means the same thing the platform means
 *  everywhere else. */
class LongPressKey(private val onTap: () -> Unit, private val onHold: () -> Unit) {
    private val handler = Handler(Looper.getMainLooper())
    private var seenDown = false
    private var fired = false
    private val runnable = Runnable {
        fired = true
        onHold()
    }

    /** Pass the DOWN's [KeyEvent.getRepeatCount]: auto-repeats are ignored, as is a DOWN while
     *  one is already in flight. A repeat counted as a fresh press would reset [fired] and re-post
     *  the hold, so a long hold fired the hold again and its UP read as a tap (P9's hold-`0`
     *  typing `+0`). Checking the repeat count, not just [seenDown], matters after [reset]:
     *  confirmed on-device that once a hold-`*` has started Settings and `onPause` reset this, the
     *  list window still gets the key's remaining repeats and its UP for a few hundred ms, which
     *  then opened the menu behind Settings. */
    fun onDown(repeatCount: Int = 0) {
        if (repeatCount > 0 || seenDown) return
        seenDown = true
        fired = false
        handler.postDelayed(runnable, ViewConfiguration.getLongPressTimeout().toLong())
    }

    fun onUp() {
        handler.removeCallbacks(runnable)
        if (seenDown && !fired) onTap()
        seenDown = false
    }

    /** Forgets an in-flight DOWN without firing either callback — for a screen that stops seeing
     *  events mid-hold (e.g. `onPause`) and must not let a much-later, unrelated UP read as a tap.
     *  The same defensive guard `ContactsActivity`'s hand-rolled version used to provide with its
     *  own `seenKeyDowns` set, now owned here instead. */
    fun reset() {
        handler.removeCallbacks(runnable)
        seenDown = false
    }
}
